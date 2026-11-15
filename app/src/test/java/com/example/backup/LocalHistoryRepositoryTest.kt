package com.example.backup

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalHistoryRepositoryTest {

    private lateinit var database: LenscribeBackupDatabase
    private lateinit var imageDir: File
    private lateinit var repository: LocalHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            LenscribeBackupDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        imageDir = File(context.cacheDir, "test_scan_images_${System.nanoTime()}")
        repository = LocalHistoryRepository(database.scannedDocumentDao(), imageDir)
    }

    @After
    fun tearDown() {
        database.close()
        imageDir.deleteRecursively()
    }

    @Test
    fun saveSearchUpdateAndDelete_managesEntitiesAndCapturedImageFile() = runBlocking {
        val preview = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val saved = repository.saveLocalScan(
            userId = "user_1",
            cloudScanId = "scan_abc",
            title = "Coffee Receipt",
            rawText = "Flat white 4.50\nTotal 4.50",
            capturedPreview = preview,
            timestamp = 1_700_000_000L
        )

        assertTrue(saved.id > 0L)
        assertNotNull(saved.capturedImagePath)
        val savedFile = File(saved.capturedImagePath!!)
        assertTrue(savedFile.exists())

        val all = repository.observeLocalHistory("user_1").first()
        assertEquals(1, all.size)
        assertEquals("Coffee Receipt", all[0].title)
        assertEquals("Flat white 4.50\nTotal 4.50", all[0].rawText)

        val searchMatches = repository.searchLocalHistory("user_1", "Flat white").first()
        assertEquals(1, searchMatches.size)

        val updated = repository.updateLocalScan(saved, "Cafe Receipt #2", "Flat white 4.50\nTip 1.00")
        assertEquals("Cafe Receipt #2", updated.title)

        repository.deleteLocalScan("user_1", updated)
        assertTrue(repository.observeLocalHistory("user_1").first().isEmpty())
        assertFalse(savedFile.exists())
    }
}
