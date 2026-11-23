package com.example.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.AppConfig
import com.example.EmotePackApplication
import com.example.R
import com.example.conversion.ConversionOutcome
import com.example.conversion.StickerConversionMode
import com.example.conversion.StickerConverter
import com.example.data.local.CollectionRepository
import com.example.data.local.ConversionModeType
import com.example.data.local.EmoteCacheEntity
import com.example.data.local.ItemConversionStatus
import com.example.data.local.StickerItemEntity
import com.example.whatsapp.WhatsAppPackBuilder
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import java.io.File

class PackBuildWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_COLLECTION_ID = "collection_id"
        const val KEY_TRIM_ALL_OVER_10S = "trim_all_over_10s"
        const val KEY_TRIM_ITEM_IDS = "trim_item_ids"
        const val CHANNEL_ID = "emotepack_downloads"
        const val NOTIFICATION_ID = 4102
    }

    override suspend fun doWork(): Result {
        val collectionId = inputData.getString(KEY_COLLECTION_ID) ?: return Result.failure()
        val trimAll = inputData.getBoolean(KEY_TRIM_ALL_OVER_10S, false)
        val trimItemIds = inputData.getStringArray(KEY_TRIM_ITEM_IDS)?.toSet() ?: emptySet()

        val app = applicationContext as EmotePackApplication
        val repo = app.collectionRepository
        val httpClient = app.okHttpClient

        val details = repo.getCollectionWithDetails(collectionId) ?: return Result.failure()
        val collectionName = details.collection.name
        val total = details.items.size
        val packHasAnyAnimated = details.items.any { it.isAnimated }

        try {
            setForeground(createForegroundInfo(collectionName, 0, total))
        } catch (_: Exception) {
            // Foreground notification may be skipped if POST_NOTIFICATIONS is denied on API 33+
        }

        var processed = details.items.count { it.status == ItemConversionStatus.COMPLETED }
        var wasCancelled = false

        try {
            for (item in details.items) {
                if (isStopped) {
                    wasCancelled = true
                    break
                }

                val targetModeStr = WhatsAppPackBuilder.targetConversionMode(
                    isEmoteAnimated = item.isAnimated,
                    packHasAnyAnimated = packHasAnyAnimated
                )
                val targetMode = when (targetModeStr) {
                    ConversionModeType.ANIMATED -> StickerConversionMode.ANIMATED
                    ConversionModeType.FAKE_VID -> StickerConversionMode.FAKE_VID
                    else -> StickerConversionMode.STATIC
                }
                val expectAnimatedOutput = targetMode != StickerConversionMode.STATIC

                // Fast reuse if already COMPLETED in the exact target mode and valid on disk
                val existingPath = item.finalWebpPath
                if (item.status == ItemConversionStatus.COMPLETED &&
                    item.conversionMode == targetModeStr &&
                    !existingPath.isNullOrBlank()
                ) {
                    val file = File(existingPath)
                    if (StickerConverter.validateExistingWebp(file, expectAnimatedOutput) != null) {
                        continue
                    }
                }

                if (item.status != ItemConversionStatus.PENDING &&
                    item.status != ItemConversionStatus.DOWNLOADING &&
                    item.status != ItemConversionStatus.CONVERTING &&
                    item.status != ItemConversionStatus.VALIDATING &&
                    !(item.status == ItemConversionStatus.NEEDS_TRIM && (trimAll || item.id in trimItemIds))
                ) {
                    continue
                }

                val effectiveVersionKey = item.versionKey.ifBlank {
                    "${item.versionUpdatedAt}_${item.highResUrl.hashCode()}"
                }
                val cacheKey = "${item.emoteId}_$effectiveVersionKey"
                val cacheEntry = repo.getEmoteCache(cacheKey)
                    ?: if (item.versionUpdatedAt == 0L) repo.getLatestEmoteCache(item.emoteId) else null

                // Step A: Try reusing an already converted shared-cache variant for targetMode
                if (cacheEntry != null && cacheEntry.converterVersion == AppConfig.CONVERTER_VERSION) {
                    val cachedVariantPath = when (targetMode) {
                        StickerConversionMode.STATIC -> cacheEntry.staticWebpPath
                        StickerConversionMode.ANIMATED -> cacheEntry.animatedWebpPath
                        StickerConversionMode.FAKE_VID -> cacheEntry.fakeVidWebpPath
                    }
                    val variantFile = cachedVariantPath?.let(::File)
                    val info = if (variantFile != null && variantFile.exists()) {
                        StickerConverter.validateExistingWebp(variantFile, expectAnimatedOutput)
                    } else null

                    if (info != null && variantFile != null) {
                        repo.touchEmoteCache(cacheEntry.cacheKey)
                        repo.updateItem(
                            item.copy(
                                finalWebpPath = variantFile.absolutePath,
                                stickerFileName = item.stickerFileName ?: "sticker_${item.emoteId}.webp",
                                fileSizeBytes = variantFile.length(),
                                width = info.width,
                                height = info.height,
                                frameCount = info.frameCount,
                                durationMs = info.totalDurationMs,
                                hasAlpha = info.hasAlpha,
                                status = ItemConversionStatus.COMPLETED,
                                errorMessage = null,
                                versionKey = effectiveVersionKey,
                                conversionMode = targetModeStr
                            )
                        )
                        processed++
                        updateNotificationSafe(collectionName, processed, total)
                        continue
                    }
                }

                // Step B: Obtain source bytes from local original cache (or offline previous file),
                // or download from 7TV only if not cached locally for this version
                var sourceBytes: ByteArray? = readLocalOriginalOrFallback(cacheEntry, item)
                var originalFile: File? = cacheEntry?.originalFilePath?.let(::File)?.takeIf { it.exists() }

                if (sourceBytes == null) {
                    repo.updateItem(item.copy(status = ItemConversionStatus.DOWNLOADING, errorMessage = null))
                    val urlsToTry = listOf(item.highResUrl) +
                        item.fallbackUrlsCsv.split("|").filter { it.isNotBlank() }

                    val downloaded = downloadFirstAvailable(httpClient, urlsToTry)
                    if (downloaded == null) {
                        repo.updateItem(
                            item.copy(
                                status = ItemConversionStatus.FAILED,
                                errorMessage = applicationContext.getString(R.string.error_network)
                            )
                        )
                        processed++
                        updateNotificationSafe(collectionName, processed, total)
                        continue
                    }
                    sourceBytes = downloaded.bytes
                    val savedOrig = repo.storage.getSharedOriginalFile(
                        emoteId = item.emoteId,
                        versionKey = effectiveVersionKey,
                        ext = downloaded.extension
                    )
                    savedOrig.writeBytes(downloaded.bytes)
                    originalFile = savedOrig
                }

                if (isStopped) {
                    wasCancelled = true
                    break
                }

                // Step C: Convert locally to target mode (STATIC, ANIMATED, or FAKE_VID)
                repo.updateItem(item.copy(status = ItemConversionStatus.CONVERTING))
                val allowTrim = trimAll || item.id in trimItemIds
                val outcome = StickerConverter.convert(
                    inputBytes = sourceBytes,
                    mode = targetMode,
                    allowTrimOver10s = allowTrim
                )

                when (outcome) {
                    is ConversionOutcome.Success -> {
                        repo.updateItem(item.copy(status = ItemConversionStatus.VALIDATING))
                        val finalModeName = outcome.conversionMode.name
                        val variantFile = repo.storage.getSharedVariantFile(
                            emoteId = item.emoteId,
                            versionKey = effectiveVersionKey,
                            mode = finalModeName
                        )
                        variantFile.writeBytes(outcome.webpBytes)

                        updateSharedCacheEntry(
                            repo = repo,
                            existing = cacheEntry,
                            cacheKey = cacheKey,
                            item = item,
                            versionKey = effectiveVersionKey,
                            originalFile = originalFile,
                            mode = outcome.conversionMode,
                            variantFile = variantFile
                        )

                        // Preserve item.isAnimated as the ORIGINAL 7TV animated state!
                        repo.updateItem(
                            item.copy(
                                finalWebpPath = variantFile.absolutePath,
                                stickerFileName = item.stickerFileName ?: "sticker_${item.emoteId}.webp",
                                fileSizeBytes = outcome.webpBytes.size.toLong(),
                                width = outcome.inspection.width,
                                height = outcome.inspection.height,
                                frameCount = outcome.inspection.frameCount,
                                durationMs = outcome.inspection.totalDurationMs,
                                hasAlpha = outcome.inspection.hasAlpha,
                                status = ItemConversionStatus.COMPLETED,
                                errorMessage = null,
                                versionKey = effectiveVersionKey,
                                conversionMode = finalModeName
                            )
                        )
                    }
                    is ConversionOutcome.NeedsTrimConfirmation -> {
                        repo.updateItem(
                            item.copy(
                                durationMs = outcome.totalDurationMs,
                                status = ItemConversionStatus.NEEDS_TRIM,
                                errorMessage = null
                            )
                        )
                    }
                    is ConversionOutcome.Failure -> {
                        repo.updateItem(
                            item.copy(
                                status = ItemConversionStatus.FAILED,
                                errorMessage = outcome.reason
                            )
                        )
                    }
                }

                processed++
                updateNotificationSafe(collectionName, processed, total)
            }
        } catch (_: CancellationException) {
            wasCancelled = true
        }

        if (wasCancelled || isStopped) {
            val latest = repo.getCollectionWithDetails(collectionId)
            latest?.items?.filter {
                it.status == ItemConversionStatus.PENDING ||
                    it.status == ItemConversionStatus.DOWNLOADING ||
                    it.status == ItemConversionStatus.CONVERTING ||
                    it.status == ItemConversionStatus.VALIDATING
            }?.forEach { pending ->
                repo.updateItem(pending.copy(status = ItemConversionStatus.CANCELLED))
            }
            repo.finalizeCollectionBuild(collectionId, cancelled = true)
            return Result.success()
        }

        repo.finalizeCollectionBuild(collectionId, cancelled = false)
        return Result.success()
    }

    private fun readLocalOriginalOrFallback(
        cacheEntry: EmoteCacheEntity?,
        item: StickerItemEntity
    ): ByteArray? {
        val origFile = cacheEntry?.originalFilePath?.let(::File)
        if (origFile != null && origFile.exists() && origFile.length() > 0L) {
            return origFile.readBytes()
        }
        // Offline conversion fallback if converting a previously saved static sticker into FAKE_VID
        if (!item.isAnimated && !item.finalWebpPath.isNullOrBlank()) {
            val prevFile = File(item.finalWebpPath)
            if (prevFile.exists() && prevFile.length() > 0L) {
                return prevFile.readBytes()
            }
        }
        return null
    }

    private suspend fun updateSharedCacheEntry(
        repo: CollectionRepository,
        existing: EmoteCacheEntity?,
        cacheKey: String,
        item: StickerItemEntity,
        versionKey: String,
        originalFile: File?,
        mode: StickerConversionMode,
        variantFile: File
    ) {
        val now = System.currentTimeMillis()
        val base = existing ?: EmoteCacheEntity(
            cacheKey = cacheKey,
            emoteId = item.emoteId,
            versionKey = versionKey,
            updatedAt = item.versionUpdatedAt,
            sourceUrl = item.highResUrl,
            originalFormat = originalFile?.extension.orEmpty(),
            originalFilePath = originalFile?.absolutePath,
            staticWebpPath = null,
            animatedWebpPath = null,
            fakeVidWebpPath = null,
            converterVersion = AppConfig.CONVERTER_VERSION,
            createdAt = now,
            lastUsedAt = now
        )
        val updated = when (mode) {
            StickerConversionMode.STATIC -> base.copy(
                originalFilePath = originalFile?.absolutePath ?: base.originalFilePath,
                staticWebpPath = variantFile.absolutePath,
                converterVersion = AppConfig.CONVERTER_VERSION,
                lastUsedAt = now
            )
            StickerConversionMode.ANIMATED -> base.copy(
                originalFilePath = originalFile?.absolutePath ?: base.originalFilePath,
                animatedWebpPath = variantFile.absolutePath,
                converterVersion = AppConfig.CONVERTER_VERSION,
                lastUsedAt = now
            )
            StickerConversionMode.FAKE_VID -> base.copy(
                originalFilePath = originalFile?.absolutePath ?: base.originalFilePath,
                fakeVidWebpPath = variantFile.absolutePath,
                converterVersion = AppConfig.CONVERTER_VERSION,
                lastUsedAt = now
            )
        }
        repo.upsertEmoteCache(updated)
    }

    private data class DownloadedPayload(val bytes: ByteArray, val extension: String)

    private fun downloadFirstAvailable(client: okhttp3.OkHttpClient, urls: List<String>): DownloadedPayload? {
        for (url in urls.distinct()) {
            if (isStopped) return null
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val ext = url.substringAfterLast('.', "webp")
                                .substringBefore('?')
                                .lowercase()
                                .ifBlank { "webp" }
                            return DownloadedPayload(bytes, ext)
                        }
                    }
                }
            } catch (_: Exception) {
                // Try next fallback variant
            }
        }
        return null
    }

    private suspend fun updateNotificationSafe(name: String, processed: Int, total: Int) {
        try {
            setForeground(createForegroundInfo(name, processed, total))
        } catch (_: Exception) {
        }
    }

    private fun createForegroundInfo(collectionName: String, processed: Int, total: Int): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sticker_add)
            .setContentTitle(applicationContext.getString(R.string.notification_progress_title, collectionName))
            .setContentText(applicationContext.getString(R.string.notification_progress_body, processed, total))
            .setProgress(total.coerceAtLeast(1), processed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
