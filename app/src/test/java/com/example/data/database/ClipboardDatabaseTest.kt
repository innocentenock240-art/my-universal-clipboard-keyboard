package com.example.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.dao.ClipboardItemDao
import com.example.data.database.entity.ClipboardItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardDatabaseTest {

    private lateinit var database: ClipboardDatabase
    private lateinit var dao: ClipboardItemDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.clipboardItemDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun testInsertAndRetrieveItem() = runBlocking {
        val item = ClipboardItemEntity(
            id = "test_1",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Hello Room Database",
            createdAt = 1000L,
            expiresAt = 2000L,
            hash = "",
            isFavorite = false,
            isPinned = false
        )
        dao.insertItem(item)

        val retrieved = dao.getItemById("test_1")
        assertNotNull(retrieved)
        assertEquals("Hello Room Database", retrieved?.content)
    }

    @Test
    fun testObserveItemsOrderedNewestFirst() = runBlocking {
        val item1 = ClipboardItemEntity(
            id = "item_1",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Older Item",
            createdAt = 1000L,
            expiresAt = 5000L,
            hash = "",
            isFavorite = false,
            isPinned = false
        )
        val item2 = ClipboardItemEntity(
            id = "item_2",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Newer Item",
            createdAt = 2000L,
            expiresAt = 5000L,
            hash = "",
            isFavorite = false,
            isPinned = false
        )

        dao.insertItem(item1)
        dao.insertItem(item2)

        val items = dao.observeAllItems().first()
        assertEquals(2, items.size)
        assertEquals("item_2", items[0].id) // Newer first
        assertEquals("item_1", items[1].id)
    }

    @Test
    fun testDeleteItem() = runBlocking {
        val item = ClipboardItemEntity(
            id = "item_to_delete",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Delete Me",
            createdAt = 1000L,
            expiresAt = 5000L,
            hash = "",
            isFavorite = false,
            isPinned = false
        )
        dao.insertItem(item)
        dao.deleteItemById("item_to_delete")

        val retrieved = dao.getItemById("item_to_delete")
        assertNull(retrieved)
    }

    @Test
    fun testUpdateFavorite() = runBlocking {
        val item = ClipboardItemEntity(
            id = "fav_item",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Favorite Test",
            createdAt = 1000L,
            expiresAt = 5000L,
            hash = "",
            isFavorite = false,
            isPinned = false
        )
        dao.insertItem(item)
        dao.updateFavorite("fav_item", true)

        val updated = dao.getItemById("fav_item")
        assertTrue(updated?.isFavorite == true)
    }

    @Test
    fun testUpdatePin() = runBlocking {
        val item = ClipboardItemEntity(
            id = "pin_item",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Pin Test",
            createdAt = 1000L,
            expiresAt = 5000L,
            hash = "",
            isFavorite = false,
            isPinned = false
        )
        dao.insertItem(item)
        dao.updatePin("pin_item", true)

        val updated = dao.getItemById("pin_item")
        assertTrue(updated?.isPinned == true)
    }

    @Test
    fun testDeleteExpiredItems() = runBlocking {
        val expiredItem = ClipboardItemEntity(
            id = "expired",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Expired Content",
            createdAt = 1000L,
            expiresAt = 2000L,
            hash = "",
            isFavorite = false,
            isPinned = false
        )
        val validItem = ClipboardItemEntity(
            id = "valid",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Valid Content",
            createdAt = 1000L,
            expiresAt = 10000L,
            hash = "",
            isFavorite = false,
            isPinned = false
        )

        dao.insertItem(expiredItem)
        dao.insertItem(validItem)

        val currentTime = 3000L
        val deletedCount = dao.deleteExpiredItems(currentTime)

        assertEquals(1, deletedCount)
        assertNull(dao.getItemById("expired"))
        assertNotNull(dao.getItemById("valid"))
    }

    @Test
    fun testClearAllItems() = runBlocking {
        val item1 = ClipboardItemEntity(
            id = "item_1",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Sample 1",
            createdAt = 1000L,
            expiresAt = 5000L,
            hash = "",
            isFavorite = false,
            isPinned = false
        )
        dao.insertItem(item1)
        dao.clearAll()

        val items = dao.observeAllItems().first()
        assertTrue(items.isEmpty())
    }

    @Test
    fun testDeleteItemsByIds() = runBlocking {
        val item1 = ClipboardItemEntity(
            id = "bulk_1",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Bulk 1",
            createdAt = 1000L,
            expiresAt = 5000L,
            hash = "h1",
            isFavorite = false,
            isPinned = false
        )
        val item2 = ClipboardItemEntity(
            id = "bulk_2",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Bulk 2",
            createdAt = 2000L,
            expiresAt = 5000L,
            hash = "h2",
            isFavorite = false,
            isPinned = false
        )
        val item3 = ClipboardItemEntity(
            id = "bulk_3",
            sourceDeviceId = "dev_local",
            sourceDeviceName = "Test Phone",
            type = "TEXT",
            content = "Bulk 3",
            createdAt = 3000L,
            expiresAt = 5000L,
            hash = "h3",
            isFavorite = false,
            isPinned = false
        )

        dao.insertItem(item1)
        dao.insertItem(item2)
        dao.insertItem(item3)

        dao.deleteItemsByIds(listOf("bulk_1", "bulk_3"))

        val remaining = dao.observeAllItems().first()
        assertEquals(1, remaining.size)
        assertEquals("bulk_2", remaining[0].id)
    }
}
