package com.example.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedDocumentDao {

    @Query("SELECT * FROM scanned_documents WHERE userId = :userId ORDER BY timestamp DESC")
    fun observeHistory(userId: String): Flow<List<ScannedDocumentEntity>>

    @Query(
        """
        SELECT * FROM scanned_documents
        WHERE userId = :userId
          AND (title LIKE '%' || :query || '%' OR rawText LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
        """
    )
    fun searchHistory(userId: String, query: String): Flow<List<ScannedDocumentEntity>>

    @Query("SELECT * FROM scanned_documents WHERE userId = :userId ORDER BY timestamp DESC")
    suspend fun getHistoryOnce(userId: String): List<ScannedDocumentEntity>

    @Query("SELECT * FROM scanned_documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ScannedDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: ScannedDocumentEntity): Long

    @Update
    suspend fun update(document: ScannedDocumentEntity)

    @Query("DELETE FROM scanned_documents WHERE userId = :userId AND id = :id")
    suspend fun deleteById(userId: String, id: Long)

    @Query("DELETE FROM scanned_documents WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
}
