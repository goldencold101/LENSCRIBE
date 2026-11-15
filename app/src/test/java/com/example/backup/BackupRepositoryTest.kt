package com.example.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.model.ScanDocument
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRepositoryTest {

    private lateinit var database: LenscribeBackupDatabase
    private lateinit var repository: BackupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            LenscribeBackupDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        repository = BackupRepository(database.backupScanDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun mirrorCloudScans_assignsSequentialNumbersAndSkipsZeroRetention() = runBlocking {
        val olderDoc = ScanDocument(
            id = "scan_alpha",
            userId = "user_1",
            title = "Receipt Alpha",
            markdown = "# Receipt Alpha",
            createdAt = Timestamp(Date(1_000_000L)),
            zeroRetention = false
        )
        val newerDoc = ScanDocument(
            id = "scan_beta",
            userId = "user_1",
            title = "Receipt Beta",
            markdown = "# Receipt Beta",
            createdAt = Timestamp(Date(2_000_000L)),
            zeroRetention = false
        )
        val zeroRetDoc = ScanDocument(
            id = "scan_ephemeral",
            userId = "user_1",
            title = "Ephemeral",
            markdown = "Private",
            createdAt = Timestamp(Date(3_000_000L)),
            zeroRetention = true
        )

        val count = repository.mirrorCloudScans("user_1", listOf(newerDoc, olderDoc, zeroRetDoc))
        assertEquals(2, count)

        val saved = repository.observeBackups("user_1").first()
        assertEquals(2, saved.size)
        // observeUserBackups orders DESC by sequenceNumber
        assertEquals("scan_beta", saved[0].id)
        assertEquals(2L, saved[0].sequenceNumber)
        assertEquals("scan_alpha", saved[1].id)
        assertEquals(1L, saved[1].sequenceNumber)
        assertTrue(saved.all { it.cloudSynced })
    }

    @Test
    fun saveFailoverBackup_createsSequentialLocalRecordAndExportsPortableJson() = runBlocking {
        val failover = repository.saveFailoverBackup(
            userId = "user_1",
            scan = ScanDocument(
                id = "",
                userId = "user_1",
                title = "Offline Note",
                markdown = "Page 1 text\n\n---\n\nPage 2 text",
                pageCount = 2L
            ),
            cloudSynced = false
        )

        assertTrue(failover.id.startsWith("scan_"))
        assertEquals(1L, failover.sequenceNumber)
        assertFalse(failover.cloudSynced)

        val jsonBytes = repository.exportPortableJson("user_1")
        repository.clearUserBackups("user_1")
        assertTrue(repository.observeBackups("user_1").first().isEmpty())

        val importResult = repository.importPortableJson("user_1", jsonBytes)
        assertTrue(importResult.isSuccess)
        assertEquals(1, importResult.getOrNull())

        val restored = repository.observeBackups("user_1").first()
        assertEquals(1, restored.size)
        assertEquals("Offline Note", restored[0].title)
        assertEquals(1L, restored[0].sequenceNumber)
        assertFalse(restored[0].cloudSynced)
    }

    @Test
    fun importPortableJson_rejectsOversizedAndMalformedPayloads() = runBlocking {
        val oversized = ByteArray(2 * 1024 * 1024 + 10) { 'a'.code.toByte() }
        val oversizedRes = repository.importPortableJson("user_1", oversized)
        assertTrue(oversizedRes.isFailure)

        val malformed = """{"app":"Lenscribe"}""".toByteArray(Charsets.UTF_8)
        val malformedRes = repository.importPortableJson("user_1", malformed)
        assertTrue(malformedRes.isFailure)
    }

    @Test
    fun clearUserBackups_isolatesOtherUsers() = runBlocking {
        repository.saveFailoverBackup(
            userId = "user_a",
            scan = ScanDocument(id = "doc_a", userId = "user_a", title = "A", markdown = "Body A"),
            cloudSynced = true
        )
        repository.saveFailoverBackup(
            userId = "user_b",
            scan = ScanDocument(id = "doc_b", userId = "user_b", title = "B", markdown = "Body B"),
            cloudSynced = true
        )

        repository.clearUserBackups("user_a")
        assertTrue(repository.observeBackups("user_a").first().isEmpty())
        assertEquals(1, repository.observeBackups("user_b").first().size)
    }
}
