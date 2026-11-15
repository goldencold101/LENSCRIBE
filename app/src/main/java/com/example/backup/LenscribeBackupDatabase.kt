package com.example.backup

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BackupScanEntity::class, ScannedDocumentEntity::class],
    version = 2,
    exportSchema = false
)
abstract class LenscribeBackupDatabase : RoomDatabase() {

    abstract fun backupScanDao(): BackupScanDao
    abstract fun scannedDocumentDao(): ScannedDocumentDao

    companion object {
        @Volatile
        private var INSTANCE: LenscribeBackupDatabase? = null

        fun getInstance(context: Context): LenscribeBackupDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LenscribeBackupDatabase::class.java,
                    "lenscribe_backup.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
