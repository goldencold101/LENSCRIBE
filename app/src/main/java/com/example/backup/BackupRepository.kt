package com.example.backup

import com.example.data.LenscribeRepository
import com.example.model.ScanDocument
import com.example.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class BackupRepository(private val dao: BackupScanDao) {

    fun observeBackups(userId: String): Flow<List<BackupScanEntity>> {
        return dao.observeUserBackups(userId)
    }

    suspend fun mirrorCloudScans(userId: String, cloudScans: List<ScanDocument>): Int = withContext(Dispatchers.IO) {
        val eligible = cloudScans.filter { !it.zeroRetention && it.id.isNotBlank() }
        if (eligible.isEmpty()) return@withContext 0

        val existingMap = dao.getUserBackupsOnce(userId).associateBy { it.id }
        var nextSeq = (dao.getMaxSequenceNumber(userId) + 1L).coerceAtLeast(1L)
        val now = System.currentTimeMillis()

        val entities = eligible
            .sortedBy { it.createdAt?.toDate()?.time ?: it.updatedAt?.toDate()?.time ?: now }
            .map { doc ->
                val prev = existingMap[doc.id]
                val seq = prev?.sequenceNumber ?: nextSeq++
                doc.toBackupEntity(
                    userId = userId,
                    sequenceNumber = seq,
                    backedUpAtMillis = now,
                    cloudSynced = true
                )
            }
        dao.upsertAll(entities)
        entities.size
    }

    suspend fun saveFailoverBackup(
        userId: String,
        scan: ScanDocument,
        cloudSynced: Boolean
    ): BackupScanEntity = withContext(Dispatchers.IO) {
        val existingMap = dao.getUserBackupsOnce(userId).associateBy { it.id }
        val resolvedId = scan.id.ifBlank {
            "scan_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        }
        val seq = existingMap[resolvedId]?.sequenceNumber
            ?: (dao.getMaxSequenceNumber(userId) + 1L).coerceAtLeast(1L)
        val entity = scan.copy(id = resolvedId).toBackupEntity(
            userId = userId,
            sequenceNumber = seq,
            backedUpAtMillis = System.currentTimeMillis(),
            cloudSynced = cloudSynced
        )
        dao.upsert(entity)
        entity
    }

    suspend fun deleteBackupScan(userId: String, scanId: String) = withContext(Dispatchers.IO) {
        dao.deleteById(userId, scanId)
    }

    suspend fun clearUserBackups(userId: String) = withContext(Dispatchers.IO) {
        dao.clearForUser(userId)
    }

    suspend fun restoreMissingToCloud(
        userId: String,
        existingCloudIds: Set<String>,
        cloudRepository: LenscribeRepository
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val locals = dao.getUserBackupsOnce(userId)
            val missing = locals.filter { it.id !in existingCloudIds || !it.cloudSynced }
            var restoredCount = 0
            for (item in missing) {
                val doc = item.toScanDocument()
                val res = cloudRepository.saveScan(doc)
                if (res.isSuccess) {
                    dao.upsert(item.copy(cloudSynced = true, backedUpAtMillis = System.currentTimeMillis()))
                    restoredCount++
                }
            }
            restoredCount
        }
    }

    suspend fun exportPortableJson(userId: String): ByteArray = withContext(Dispatchers.IO) {
        val items = dao.getUserBackupsOnce(userId)
        val scansJson = JSONArray()
        items.forEach { item ->
            scansJson.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("sequenceNumber", item.sequenceNumber)
                    put("title", SecurityGuard.sanitizeSafeText(item.title))
                    put("markdown", SecurityGuard.sanitizeSafeText(item.markdown))
                    put("mode", item.mode)
                    put("preset", item.preset)
                    put("pageCount", item.pageCount)
                    put("hasTable", item.hasTable)
                    put("lowConfidenceTerms", JSONArray(runCatching { JSONArray(item.lowConfidenceTermsJson) }.getOrNull()?.let { arr ->
                        List(arr.length()) { i -> arr.optString(i, "") }
                    } ?: emptyList<String>()))
                    put("updatedAtMillis", item.updatedAtMillis)
                }
            )
        }
        val root = JSONObject().apply {
            put("app", "Lenscribe")
            put("schemaVersion", 1)
            put("exportedAtMillis", System.currentTimeMillis())
            put("count", items.size)
            put("scans", scansJson)
        }
        root.toString(2).toByteArray(Charsets.UTF_8)
    }

    suspend fun importPortableJson(userId: String, jsonBytes: ByteArray): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            require(jsonBytes.size <= MAX_BACKUP_BYTES) {
                "Backup file exceeds 2 MB safety limit."
            }
            val root = JSONObject(String(jsonBytes, Charsets.UTF_8))
            val scansArray = root.optJSONArray("scans")
                ?: throw IllegalArgumentException("Invalid backup format: missing scans array.")
            require(scansArray.length() <= MAX_IMPORT_ITEMS) {
                "Backup contains more than $MAX_IMPORT_ITEMS scans."
            }

            var nextSeq = (dao.getMaxSequenceNumber(userId) + 1L).coerceAtLeast(1L)
            val now = System.currentTimeMillis()
            val validModes = setOf("fast", "precision", "table")
            val validPresets = setOf("layout", "table", "line_breaks", "translate_en", "clean_punctuation", "code_math")
            val imported = mutableListOf<BackupScanEntity>()

            for (i in 0 until scansArray.length()) {
                val obj = scansArray.optJSONObject(i) ?: continue
                val rawId = obj.optString("id", "").trim()
                val safeId = if (rawId.matches(Regex("^[a-zA-Z0-9_\\-]{4,128}$"))) {
                    rawId
                } else {
                    "scan_${UUID.randomUUID().toString().replace("-", "").take(20)}"
                }
                val title = SecurityGuard.sanitizeSafeText(obj.optString("title", "Imported Scan"))
                    .trim()
                    .ifBlank { "Imported Scan" }
                    .take(120)
                val markdown = SecurityGuard.sanitizeSafeText(obj.optString("markdown", ""))
                    .ifBlank { continue }
                    .take(100000)
                val mode = obj.optString("mode", "fast").takeIf { it in validModes } ?: "fast"
                val preset = obj.optString("preset", "layout").takeIf { it in validPresets } ?: "layout"
                val pageCount = obj.optLong("pageCount", 1L).coerceIn(1L, 50L)
                val hasTable = obj.optBoolean("hasTable", markdown.contains("| ---"))
                val seq = obj.optLong("sequenceNumber", 0L).takeIf { it > 0L } ?: nextSeq++
                if (seq >= nextSeq) nextSeq = seq + 1L

                val termsArr = obj.optJSONArray("lowConfidenceTerms")
                val safeTerms = buildList {
                    if (termsArr != null) {
                        for (t in 0 until minOf(termsArr.length(), 30)) {
                            val term = SecurityGuard.sanitizeSafeText(termsArr.optString(t, "")).trim()
                            if (term.isNotEmpty()) add(term)
                        }
                    }
                }

                imported.add(
                    BackupScanEntity(
                        id = safeId,
                        userId = userId,
                        sequenceNumber = seq,
                        title = title,
                        markdown = markdown,
                        mode = mode,
                        preset = preset,
                        pageCount = pageCount,
                        hasTable = hasTable,
                        lowConfidenceTermsJson = JSONArray(safeTerms).toString(),
                        updatedAtMillis = obj.optLong("updatedAtMillis", now).coerceAtLeast(1L),
                        backedUpAtMillis = now,
                        cloudSynced = false
                    )
                )
            }

            if (imported.isNotEmpty()) {
                dao.upsertAll(imported)
            }
            imported.size
        }
    }

    private fun ScanDocument.toBackupEntity(
        userId: String,
        sequenceNumber: Long,
        backedUpAtMillis: Long,
        cloudSynced: Boolean
    ): BackupScanEntity {
        val termsJson = JSONArray(lowConfidenceTerms.take(30)).toString()
        val updated = updatedAt?.toDate()?.time ?: createdAt?.toDate()?.time ?: backedUpAtMillis
        return BackupScanEntity(
            id = id,
            userId = userId,
            sequenceNumber = sequenceNumber,
            title = SecurityGuard.sanitizeSafeText(title).take(120),
            markdown = SecurityGuard.sanitizeSafeText(markdown).take(100000),
            mode = mode,
            preset = preset,
            pageCount = pageCount.coerceIn(1L, 50L),
            hasTable = hasTable,
            lowConfidenceTermsJson = termsJson,
            updatedAtMillis = updated,
            backedUpAtMillis = backedUpAtMillis,
            cloudSynced = cloudSynced
        )
    }

    private fun BackupScanEntity.toScanDocument(): ScanDocument {
        val terms = runCatching {
            val arr = JSONArray(lowConfidenceTermsJson)
            List(arr.length()) { i -> arr.optString(i, "") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        return ScanDocument(
            id = id,
            userId = userId,
            title = title,
            markdown = markdown,
            mode = mode,
            preset = preset,
            pageCount = pageCount,
            hasTable = hasTable,
            zeroRetention = false,
            lowConfidenceTerms = terms
        )
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 2 * 1024 * 1024 // 2 MB
        const val MAX_IMPORT_ITEMS = 200
    }
}
