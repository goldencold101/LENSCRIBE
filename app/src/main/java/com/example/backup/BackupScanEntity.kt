package com.example.backup

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "backup_scans",
    indices = [
        Index(value = ["userId", "sequenceNumber"]),
        Index(value = ["userId", "updatedAtMillis"])
    ]
)
data class BackupScanEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val sequenceNumber: Long,
    val title: String,
    val markdown: String,
    val mode: String,
    val preset: String,
    val pageCount: Long,
    val hasTable: Boolean,
    val lowConfidenceTermsJson: String,
    val updatedAtMillis: Long,
    val backedUpAtMillis: Long,
    val cloudSynced: Boolean
)
