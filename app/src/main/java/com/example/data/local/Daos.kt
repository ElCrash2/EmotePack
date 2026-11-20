package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM sticker_collections ORDER BY updatedAt DESC")
    fun observeAllCollections(): Flow<List<StickerCollectionEntity>>

    @Query("SELECT * FROM sticker_collections WHERE status = 'PROCESSING' ORDER BY updatedAt DESC LIMIT 1")
    fun observeActiveProcessingCollection(): Flow<StickerCollectionEntity?>

    @Transaction
    @Query("SELECT * FROM sticker_collections WHERE id = :collectionId")
    fun observeCollectionWithDetails(collectionId: String): Flow<CollectionWithDetails?>

    @Transaction
    @Query("SELECT * FROM sticker_collections WHERE id = :collectionId")
    suspend fun getCollectionWithDetails(collectionId: String): CollectionWithDetails?

    @Transaction
    @Query("SELECT * FROM sticker_collections ORDER BY createdAt ASC")
    suspend fun getAllCollectionsWithDetails(): List<CollectionWithDetails>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(collection: StickerCollectionEntity)

    @Query("DELETE FROM sticker_collections WHERE id = :collectionId")
    suspend fun deleteById(collectionId: String)
}

@Dao
interface StickerItemDao {
    @Query("SELECT * FROM sticker_items WHERE collectionId = :collectionId ORDER BY sortOrder ASC")
    fun observeItemsForCollection(collectionId: String): Flow<List<StickerItemEntity>>

    @Query("SELECT * FROM sticker_items WHERE collectionId = :collectionId ORDER BY sortOrder ASC")
    suspend fun getItemsForCollection(collectionId: String): List<StickerItemEntity>

    @Query("SELECT * FROM sticker_items WHERE subpackId = :subpackId AND status = 'COMPLETED' ORDER BY sortOrder ASC")
    suspend fun getCompletedItemsForSubpack(subpackId: String): List<StickerItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<StickerItemEntity>)

    @Update
    suspend fun update(item: StickerItemEntity)

    @Query("DELETE FROM sticker_items WHERE collectionId = :collectionId")
    suspend fun deleteForCollection(collectionId: String)

    @Query("DELETE FROM sticker_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface SubpackDao {
    @Query("SELECT * FROM whatsapp_subpacks WHERE collectionId = :collectionId ORDER BY isAnimated DESC, displayName ASC")
    fun observeSubpacksForCollection(collectionId: String): Flow<List<WhatsAppSubpackEntity>>

    @Query("SELECT * FROM whatsapp_subpacks WHERE isWhatsAppReady = 1")
    suspend fun getAllReadySubpacks(): List<WhatsAppSubpackEntity>

    @Query("SELECT * FROM whatsapp_subpacks WHERE id = :subpackId")
    suspend fun getSubpackById(subpackId: String): WhatsAppSubpackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(subpacks: List<WhatsAppSubpackEntity>)

    @Query("UPDATE whatsapp_subpacks SET addedToWhatsApp = :added WHERE id = :subpackId")
    suspend fun markAddedToWhatsApp(subpackId: String, added: Boolean)

    @Query("DELETE FROM whatsapp_subpacks WHERE collectionId = :collectionId")
    suspend fun deleteForCollection(collectionId: String)
}
