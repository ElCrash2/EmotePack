package com.example.data.local

import android.content.Context
import android.net.Uri
import com.example.conversion.StickerConverter
import com.example.data.model.SearchOrigin
import com.example.data.model.SevenTvEmote
import com.example.whatsapp.SubpackBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class CollectionRepository(
    private val context: Context,
    private val db: AppDatabase,
    val storage: LocalStorageManager
) {

    private val collectionDao = db.collectionDao()
    private val itemDao = db.stickerItemDao()
    private val subpackDao = db.subpackDao()

    fun observeAllCollections(): Flow<List<StickerCollectionEntity>> =
        collectionDao.observeAllCollections()

    fun observeActiveProcessingCollection(): Flow<StickerCollectionEntity?> =
        collectionDao.observeActiveProcessingCollection()

    fun observeCollectionWithDetails(collectionId: String): Flow<CollectionWithDetails?> =
        collectionDao.observeCollectionWithDetails(collectionId)

    suspend fun getCollectionWithDetails(collectionId: String): CollectionWithDetails? =
        collectionDao.getCollectionWithDetails(collectionId)

    suspend fun getAllReadySubpacks(): List<WhatsAppSubpackEntity> =
        subpackDao.getAllReadySubpacks()

    suspend fun getSubpackById(subpackId: String): WhatsAppSubpackEntity? =
        subpackDao.getSubpackById(subpackId)

    suspend fun getCompletedStickersForSubpack(subpackId: String): List<StickerItemEntity> =
        itemDao.getCompletedItemsForSubpack(subpackId)

    suspend fun markSubpackAddedToWhatsApp(subpackId: String, added: Boolean) {
        subpackDao.markAddedToWhatsApp(subpackId, added)
    }

    suspend fun getCollectionsCount(): Int =
        collectionDao.getAllCollectionsWithDetails().size

    /**
     * Prepares a new or edited collection in Room before starting background WorkManager conversion.
     * During an edit, existing valid local WebP files are preserved and reused without re-downloading!
     */
    suspend fun prepareCollectionBuild(
        existingCollectionId: String?,
        collectionName: String,
        origin: SearchOrigin,
        selectedEmotes: List<SevenTvEmote>
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val collectionId = existingCollectionId ?: UUID.randomUUID().toString()
        val existing = if (existingCollectionId != null) {
            collectionDao.getCollectionWithDetails(existingCollectionId)
        } else null

        val existingItemsByEmoteId = existing?.items?.associateBy { it.emoteId } ?: emptyMap()

        val itemsToSave = selectedEmotes.mapIndexed { index, emote ->
            val prev = existingItemsByEmoteId[emote.id]
            val prevFile = prev?.finalWebpPath?.let { File(it) }
            if (prev != null &&
                prev.status == ItemConversionStatus.COMPLETED &&
                prevFile != null &&
                StickerConverter.validateExistingWebp(prevFile, prev.isAnimated) != null
            ) {
                prev.copy(
                    sortOrder = index,
                    emoteName = emote.name
                )
            } else {
                StickerItemEntity(
                    id = prev?.id ?: UUID.randomUUID().toString(),
                    collectionId = collectionId,
                    emoteId = emote.id,
                    emoteName = emote.name,
                    sourceSetId = emote.sourceSetId,
                    sourceSetName = emote.sourceSetName,
                    isAnimated = emote.animated,
                    previewUrl = emote.previewUrl,
                    highResUrl = emote.highResUrl,
                    fallbackUrlsCsv = emote.fallbackUrls.joinToString("|"),
                    finalWebpPath = null,
                    stickerFileName = "sticker_${emote.id}.webp",
                    fileSizeBytes = 0L,
                    width = 0,
                    height = 0,
                    frameCount = 0,
                    durationMs = 0,
                    hasAlpha = true,
                    status = ItemConversionStatus.PENDING,
                    errorMessage = null,
                    subpackId = null,
                    sortOrder = index
                )
            }
        }

        val entity = StickerCollectionEntity(
            id = collectionId,
            name = collectionName.trim(),
            createdAt = existing?.collection?.createdAt ?: now,
            updatedAt = now,
            searchType = origin.type.name,
            searchQuery = origin.query,
            streamerId = origin.streamerId,
            streamerName = origin.streamerName,
            streamerUsername = origin.streamerUsername,
            streamerAvatarUrl = origin.streamerAvatarUrl,
            sourceEmoteSetId = origin.emoteSetId,
            trayIconPath = existing?.collection?.trayIconPath,
            totalCount = itemsToSave.size,
            animatedCount = itemsToSave.count { it.isAnimated },
            staticCount = itemsToSave.count { !it.isAnimated },
            status = CollectionStatus.PROCESSING
        )

        collectionDao.upsert(entity)
        // Note: on edit, remove removed items from DB only (do NOT delete old files until rebuild completes!)
        val keepIds = itemsToSave.map { it.id }.toSet()
        val removedIds = existing?.items?.filter { it.id !in keepIds }?.map { it.id }.orEmpty()
        if (removedIds.isNotEmpty()) {
            itemDao.deleteByIds(removedIds)
        }
        itemDao.insertAll(itemsToSave)
        collectionId
    }

    /**
     * Re-queues failed or needs-trim items for retry without touching completed stickers.
     */
    suspend fun resetItemsForRetry(collectionId: String, includeNeedsTrim: Boolean = false) = withContext(Dispatchers.IO) {
        val details = collectionDao.getCollectionWithDetails(collectionId) ?: return@withContext
        val updated = details.items.map { item ->
            val shouldReset = item.status == ItemConversionStatus.FAILED ||
                item.status == ItemConversionStatus.CANCELLED ||
                (includeNeedsTrim && item.status == ItemConversionStatus.NEEDS_TRIM)
            if (shouldReset) {
                item.copy(status = ItemConversionStatus.PENDING, errorMessage = null)
            } else {
                item
            }
        }
        itemDao.insertAll(updated)
        collectionDao.upsert(
            details.collection.copy(
                status = CollectionStatus.PROCESSING,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun finalizeCollectionBuild(collectionId: String, cancelled: Boolean) = withContext(Dispatchers.IO) {
        val details = collectionDao.getCollectionWithDetails(collectionId) ?: return@withContext
        val completed = details.items.filter { it.status == ItemConversionStatus.COMPLETED }

        if (completed.isEmpty() && cancelled) {
            collectionDao.upsert(
                details.collection.copy(
                    status = CollectionStatus.CANCELLED,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@withContext
        }

        val buildResult = SubpackBuilder.buildSubpacks(
            context = context,
            storage = storage,
            collectionId = collectionId,
            collectionName = details.collection.name,
            completedItems = completed,
            existingSubpacks = details.subpacks
        )

        subpackDao.deleteForCollection(collectionId)
        subpackDao.insertAll(buildResult.subpacks)
        if (buildResult.updatedItems.isNotEmpty()) {
            itemDao.insertAll(buildResult.updatedItems)
        }

        // Now safely clean up orphaned files after new stickers and subpacks are validated!
        val activeStickerPaths = completed.mapNotNull { it.finalWebpPath }.toSet()
        val activeTrayPaths = buildResult.subpacks.map { it.trayIconPath }.toSet()
        storage.cleanupUnusedStickers(collectionId, activeStickerPaths, activeTrayPaths)

        val hasWarnings = details.items.any {
            it.status == ItemConversionStatus.FAILED || it.status == ItemConversionStatus.NEEDS_TRIM
        }
        val anyReadySubpack = buildResult.subpacks.any { it.isWhatsAppReady }
        val finalStatus = when {
            cancelled -> CollectionStatus.CANCELLED
            hasWarnings -> CollectionStatus.PARTIAL_ERROR
            !anyReadySubpack -> CollectionStatus.DRAFT_TOO_FEW
            else -> CollectionStatus.READY
        }

        collectionDao.upsert(
            details.collection.copy(
                updatedAt = System.currentTimeMillis(),
                trayIconPath = buildResult.subpacks.firstOrNull()?.trayIconPath,
                totalCount = completed.size,
                animatedCount = completed.count { it.isAnimated },
                staticCount = completed.count { !it.isAnimated },
                status = finalStatus
            )
        )
    }

    suspend fun updateItem(item: StickerItemEntity) {
        itemDao.update(item)
    }

    suspend fun deleteCollection(collectionId: String) = withContext(Dispatchers.IO) {
        collectionDao.deleteById(collectionId)
        storage.deleteCollectionFiles(collectionId)
    }

    suspend fun exportBackupToUri(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = collectionDao.getAllCollectionsWithDetails()
        storage.exportBackup(uri, all)
    }

    suspend fun importBackupFromUri(uri: Uri): Int = withContext(Dispatchers.IO) {
        val imported = storage.importBackup(uri)
        for (entry in imported) {
            collectionDao.upsert(entry.collection)
            itemDao.deleteForCollection(entry.collection.id)
            itemDao.insertAll(entry.items)
            subpackDao.deleteForCollection(entry.collection.id)
            subpackDao.insertAll(entry.subpacks)
        }
        imported.size
    }
}
