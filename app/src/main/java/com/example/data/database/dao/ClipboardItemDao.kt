package com.example.data.database.dao

import androidx.room.*
import com.example.data.database.entity.ClipboardItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardItemDao {

    @Query("SELECT * FROM clipboard_items ORDER BY createdAt DESC")
    fun observeAllItems(): Flow<List<ClipboardItemEntity>>

    @Query("SELECT * FROM clipboard_items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): ClipboardItemEntity?

    @Query("SELECT * FROM clipboard_items WHERE hash = :hash LIMIT 1")
    suspend fun getItemByHash(hash: String): ClipboardItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ClipboardItemEntity)

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun deleteItemById(id: String)

    @Query("DELETE FROM clipboard_items WHERE id IN (:ids)")
    suspend fun deleteItemsByIds(ids: List<String>)

    @Query("DELETE FROM clipboard_items WHERE expiresAt <= :currentTime")
    suspend fun deleteExpiredItems(currentTime: Long): Int

    @Query("DELETE FROM clipboard_items")
    suspend fun clearAll()

    @Query("UPDATE clipboard_items SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE clipboard_items SET isPinned = :isPinned WHERE id = :id")
    suspend fun updatePin(id: String, isPinned: Boolean)
}
