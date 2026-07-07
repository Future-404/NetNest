package com.pwa.shell.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PwaEntity::class, UserScriptEntity::class, ScriptStorageEntity::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pwaDao(): PwaDao
    abstract fun userScriptDao(): UserScriptDao
    abstract fun scriptStorageDao(): ScriptStorageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `user_scripts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `pwaId` INTEGER NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `enabled` INTEGER NOT NULL, 
                        `matchPatterns` TEXT NOT NULL, 
                        `runAt` TEXT NOT NULL, 
                        `code` TEXT NOT NULL, 
                        `rawSource` TEXT NOT NULL, 
                        `importSource` TEXT NOT NULL, 
                        `sortOrder` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        FOREIGN KEY(`pwaId`) REFERENCES `pwas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `script_storage` (
                        `pwaId` INTEGER NOT NULL, 
                        `storageKey` TEXT NOT NULL, 
                        `storageValue` TEXT NOT NULL, 
                        PRIMARY KEY(`pwaId`, `storageKey`)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_scripts_pwaId` ON `user_scripts` (`pwaId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_scripts_pwaId_name` ON `user_scripts` (`pwaId`, `name`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pwa_shell_database"
                )
                .addMigrations(MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
