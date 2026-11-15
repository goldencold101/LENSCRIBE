package com.example.backup

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scanned_documents",
    indices = [
        Index(value = ["userId", "timestamp"]),
        Index(value = ["cloudScanId"])
    ]
)
data class ScannedDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: String,
    val cloudScanId: String = "",
    val title: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val capturedImagePath: String? = null
)
