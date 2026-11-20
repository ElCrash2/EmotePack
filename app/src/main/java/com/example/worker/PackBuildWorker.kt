package com.example.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.EmotePackApplication
import com.example.R
import com.example.conversion.ConversionOutcome
import com.example.conversion.StickerConverter
import com.example.data.local.ItemConversionStatus
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

                // Reuse already completed and valid local WebP file (never download twice)
                val existingPath = item.finalWebpPath
                if (item.status == ItemConversionStatus.COMPLETED && !existingPath.isNullOrBlank()) {
                    val file = File(existingPath)
                    if (StickerConverter.validateExistingWebp(file, item.isAnimated) != null) {
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

                // 1. Download highest available quality variant
                repo.updateItem(item.copy(status = ItemConversionStatus.DOWNLOADING, errorMessage = null))
                val urlsToTry = listOf(item.highResUrl) +
                    item.fallbackUrlsCsv.split("|").filter { it.isNotBlank() }

                val downloadedBytes = downloadFirstAvailable(httpClient, urlsToTry)
                if (downloadedBytes == null) {
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

                if (isStopped) {
                    wasCancelled = true
                    break
                }

                // 2. Convert to WhatsApp-compliant 512x512 WebP (preserving transparency & animation)
                repo.updateItem(item.copy(status = ItemConversionStatus.CONVERTING))
                val allowTrim = trimAll || item.id in trimItemIds
                val outcome = StickerConverter.convert(
                    inputBytes = downloadedBytes,
                    expectAnimated = item.isAnimated,
                    allowTrimOver10s = allowTrim
                )

                when (outcome) {
                    is ConversionOutcome.Success -> {
                        repo.updateItem(item.copy(status = ItemConversionStatus.VALIDATING))
                        val fileName = item.stickerFileName ?: "sticker_${item.emoteId}.webp"
                        val outFile = repo.storage.getStickerFile(collectionId, fileName)
                        outFile.writeBytes(outcome.webpBytes)

                        repo.updateItem(
                            item.copy(
                                isAnimated = outcome.isAnimated,
                                finalWebpPath = outFile.absolutePath,
                                stickerFileName = fileName,
                                fileSizeBytes = outcome.webpBytes.size.toLong(),
                                width = outcome.inspection.width,
                                height = outcome.inspection.height,
                                frameCount = outcome.inspection.frameCount,
                                durationMs = outcome.inspection.totalDurationMs,
                                hasAlpha = outcome.inspection.hasAlpha,
                                status = ItemConversionStatus.COMPLETED,
                                errorMessage = null
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

    private fun downloadFirstAvailable(client: okhttp3.OkHttpClient, urls: List<String>): ByteArray? {
        for (url in urls.distinct()) {
            if (isStopped) return null
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            return bytes
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
