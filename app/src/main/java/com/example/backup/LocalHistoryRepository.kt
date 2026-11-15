package com.example.backup

import android.graphics.Bitmap
import com.example.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class LocalHistoryRepository(
    private val dao: ScannedDocumentDao,
    private val imageStorageDir: File? = null
) {

    fun observeLocalHistory(userId: String): Flow<List<ScannedDocumentEntity>> {
        return dao.observeHistory(userId)
    }

    fun searchLocalHistory(userId: String, query: String): Flow<List<ScannedDocumentEntity>> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) {
            dao.observeHistory(userId)
        } else {
            dao.searchHistory(userId, trimmed)
        }
    }

    suspend fun saveLocalScan(
        userId: String,
        cloudScanId: String = "",
        title: String,
        rawText: String,
        capturedPreview: Bitmap? = null,
        timestamp: Long = System.currentTimeMillis()
    ): ScannedDocumentEntity = withContext(Dispatchers.IO) {
        val imagePath = if (capturedPreview != null && imageStorageDir != null) {
            persistPreviewBitmap(capturedPreview)
        } else {
            null
        }
        val entity = ScannedDocumentEntity(
            userId = userId,
            cloudScanId = cloudScanId,
            title = SecurityGuard.sanitizeSafeText(title).trim().ifBlank { "Untitled Scan" }.take(120),
            rawText = SecurityGuard.sanitizeSafeText(rawText).take(100000),
            timestamp = timestamp,
            capturedImagePath = imagePath
        )
        val rowId = dao.insert(entity)
        entity.copy(id = rowId)
    }

    suspend fun updateLocalScan(
        document: ScannedDocumentEntity,
        newTitle: String,
        newRawText: String
    ): ScannedDocumentEntity = withContext(Dispatchers.IO) {
        val updated = document.copy(
            title = SecurityGuard.sanitizeSafeText(newTitle).trim().ifBlank { "Untitled Scan" }.take(120),
            rawText = SecurityGuard.sanitizeSafeText(newRawText).take(100000),
            timestamp = System.currentTimeMillis()
        )
        dao.update(updated)
        updated
    }

    suspend fun deleteLocalScan(userId: String, document: ScannedDocumentEntity) = withContext(Dispatchers.IO) {
        document.capturedImagePath?.let { path ->
            runCatching { File(path).delete() }
        }
        dao.deleteById(userId, document.id)
    }

    suspend fun clearUserLocalHistory(userId: String) = withContext(Dispatchers.IO) {
        val existing = dao.getHistoryOnce(userId)
        existing.forEach { doc ->
            doc.capturedImagePath?.let { path ->
                runCatching { File(path).delete() }
            }
        }
        dao.clearForUser(userId)
    }

    private fun persistPreviewBitmap(bitmap: Bitmap): String? {
        val dir = imageStorageDir ?: return null
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "scan_${UUID.randomUUID().toString().replace("-", "").take(16)}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.flush()
            }
            file.absolutePath
        }.getOrNull()
    }
}
