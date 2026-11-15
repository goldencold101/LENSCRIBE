package com.example.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupScanDao {

    @Query("SELECT * FROM backup_scans WHERE userId = :userId ORDER BY sequenceNumber DESC, updatedAtMillis DESC")
    fun observeUserBackups(userId: String): Flow<List<BackupScanEntity>>

    @Query("SELECT * FROM backup_scans WHERE userId = :userId ORDER BY sequenceNumber ASC, updatedAtMillis ASC")
    suspend fun getUserBackupsOnce(userId: String): List<BackupScanEntity>

    @Query("SELECT COALESCE(MAX(sequenceNumber), 0) FROM backup_scans WHERE userId = :userId")
    suspend fun getMaxSequenceNumber(userId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<BackupScanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: BackupScanEntity)

    @Query("DELETE FROM backup_scans WHERE userId = :userId AND id = :scanId")
    suspend fun deleteById(userId: String, scanId: String)

    @Query("DELETE FROM backup_scans WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
