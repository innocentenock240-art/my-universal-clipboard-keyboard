package com.example.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.ClipboardItem

@Entity(
    tableName = "clipboard_items",
    indices = [Index(value = ["hash"])]
)
data class ClipboardItemEntity(
    @PrimaryKey val id: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val type: String,
    val content: String,
    val mimeType: String = ClipboardItem.MIME_TEXT_PLAIN,
    val fileName: String? = null,
    val sizeBytes: Long = 0L,
    val createdAt: Long,
    val expiresAt: Long,
    val hash: String,
    val isFavorite: Boolean,
    val isPinned: Boolean
)

fun ClipboardItemEntity.toDomainModel(): ClipboardItem {
    return ClipboardItem(
        id = id,
        sourceDeviceId = sourceDeviceId,
        sourceDeviceName = sourceDeviceName,
        type = type,
        content = content,
        mimeType = mimeType,
        fileName = fileName,
        sizeBytes = sizeBytes,
        createdAt = createdAt,
        expiresAt = expiresAt,
        hash = hash,
        isFavorite = isFavorite,
        isPinned = isPinned
    )
}

fun ClipboardItem.toEntity(): ClipboardItemEntity {
    return ClipboardItemEntity(
        id = id,
        sourceDeviceId = sourceDeviceId,
        sourceDeviceName = sourceDeviceName,
        type = type,
        content = content,
        mimeType = mimeType,
        fileName = fileName,
        sizeBytes = if (sizeBytes > 0) sizeBytes else content.toByteArray(Charsets.UTF_8).size.toLong(),
        createdAt = createdAt,
        expiresAt = expiresAt,
        hash = hash,
        isFavorite = isFavorite,
        isPinned = isPinned
    )
}
