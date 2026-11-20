package com.example.whatsapp

import android.content.Context
import com.example.R
import com.example.conversion.StickerConverter
import com.example.data.local.ItemConversionStatus
import com.example.data.local.LocalStorageManager
import com.example.data.local.StickerItemEntity
import com.example.data.local.WhatsAppSubpackEntity
import java.io.File

data class SubpackBuildResult(
    val subpacks: List<WhatsAppSubpackEntity>,
    val updatedItems: List<StickerItemEntity>
)

object SubpackBuilder {

    const val MIN_STICKERS_PER_PACK = 3
    const val MAX_STICKERS_PER_PACK = 30

    /**
     * Partitions a mixed collection into WhatsApp-compatible homogeneous subpacks
     * (all animated or all static), splitting >30 stickers cleanly without leaving
     * orphan 1-2 sticker tail packs when avoidable, and generates 96x96 PNG tray icons.
     */
    fun buildSubpacks(
        context: Context,
        storage: LocalStorageManager,
        collectionId: String,
        collectionName: String,
        completedItems: List<StickerItemEntity>,
        existingSubpacks: List<WhatsAppSubpackEntity>
    ): SubpackBuildResult {
        val animated = completedItems.filter { it.isAnimated && it.status == ItemConversionStatus.COMPLETED }
        val staticItems = completedItems.filter { !it.isAnimated && it.status == ItemConversionStatus.COMPLETED }

        val existingVersionMax = existingSubpacks.maxOfOrNull { it.imageDataVersion } ?: 0
        val nextVersion = existingVersionMax + 1

        val subpacks = mutableListOf<WhatsAppSubpackEntity>()
        val updatedItems = mutableListOf<StickerItemEntity>()

        val animSuffix = context.getString(R.string.subpack_type_animated)
        val staticSuffix = context.getString(R.string.subpack_type_static)

        val animatedChunks = chunkStickers(animated)
        animatedChunks.forEachIndexed { idx, chunk ->
            val subpackId = "${collectionId.take(12)}_anim_${idx + 1}"
            val label = buildSubpackDisplayName(collectionName, animSuffix, idx, animatedChunks.size)
            val trayFile = storage.getTrayFile(collectionId, subpackId)
            val firstFile = chunk.firstOrNull()?.finalWebpPath?.let { File(it) }
            if (firstFile != null && firstFile.exists()) {
                StickerConverter.generateTrayIcon(firstFile)?.let { trayBytes ->
                    trayFile.writeBytes(trayBytes)
                }
            }
            subpacks.add(
                WhatsAppSubpackEntity(
                    id = subpackId,
                    collectionId = collectionId,
                    displayName = label,
                    isAnimated = true,
                    trayIconPath = trayFile.absolutePath,
                    imageDataVersion = nextVersion,
                    stickerCount = chunk.size,
                    isWhatsAppReady = chunk.size >= MIN_STICKERS_PER_PACK,
                    addedToWhatsApp = false
                )
            )
            chunk.forEach { item ->
                updatedItems.add(item.copy(subpackId = subpackId))
            }
        }

        val staticChunks = chunkStickers(staticItems)
        staticChunks.forEachIndexed { idx, chunk ->
            val subpackId = "${collectionId.take(12)}_stat_${idx + 1}"
            val label = buildSubpackDisplayName(collectionName, staticSuffix, idx, staticChunks.size)
            val trayFile = storage.getTrayFile(collectionId, subpackId)
            val firstFile = chunk.firstOrNull()?.finalWebpPath?.let { File(it) }
            if (firstFile != null && firstFile.exists()) {
                StickerConverter.generateTrayIcon(firstFile)?.let { trayBytes ->
                    trayFile.writeBytes(trayBytes)
                }
            }
            subpacks.add(
                WhatsAppSubpackEntity(
                    id = subpackId,
                    collectionId = collectionId,
                    displayName = label,
                    isAnimated = false,
                    trayIconPath = trayFile.absolutePath,
                    imageDataVersion = nextVersion,
                    stickerCount = chunk.size,
                    isWhatsAppReady = chunk.size >= MIN_STICKERS_PER_PACK,
                    addedToWhatsApp = false
                )
            )
            chunk.forEach { item ->
                updatedItems.add(item.copy(subpackId = subpackId))
            }
        }

        return SubpackBuildResult(subpacks, updatedItems)
    }

    private fun buildSubpackDisplayName(
        baseName: String,
        typeLabel: String,
        chunkIndex: Int,
        totalChunksOfType: Int
    ): String {
        val base = "$baseName - $typeLabel"
        return if (totalChunksOfType > 1) "$base (${chunkIndex + 1})" else base
    }

    /**
     * Splits into chunks of max 30 stickers. If the remainder would be 1 or 2 stickers
     * (below WhatsApp's minimum of 3) and total >= 6, borrows from the previous chunk
     * so every chunk has 3..30 stickers.
     */
    fun <T> chunkStickers(items: List<T>): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        if (items.size <= MAX_STICKERS_PER_PACK) return listOf(items)

        val chunks = items.chunked(MAX_STICKERS_PER_PACK).map { it.toMutableList() }.toMutableList()
        val last = chunks.last()
        if (last.size < MIN_STICKERS_PER_PACK && chunks.size >= 2) {
            val prev = chunks[chunks.size - 2]
            while (last.size < MIN_STICKERS_PER_PACK && prev.size > MIN_STICKERS_PER_PACK) {
                val moved = prev.removeAt(prev.lastIndex)
                last.add(0, moved)
            }
        }
        return chunks
    }

    /**
     * Helper to preview subpack counts before download starts.
     */
    fun estimateSubpackCounts(animatedCount: Int, staticCount: Int): Pair<Int, Int> {
        val animPacks = if (animatedCount == 0) 0 else (animatedCount + MAX_STICKERS_PER_PACK - 1) / MAX_STICKERS_PER_PACK
        val statPacks = if (staticCount == 0) 0 else (staticCount + MAX_STICKERS_PER_PACK - 1) / MAX_STICKERS_PER_PACK
        return animPacks to statPacks
    }
}
