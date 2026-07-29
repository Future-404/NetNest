package com.pwa.shell.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PwaEntity::class,
        PwaFolderEntity::class,
        UserScriptEntity::class,
        ScriptStorageEntity::class,
        PendingWebProfileDeletionEntity::class
    ],
    version = 11,
    exportSchema = false
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pwaDao(): PwaDao
    abstract fun pwaFolderDao(): PwaFolderDao
    abstract fun userScriptDao(): UserScriptDao
    abstract fun scriptStorageDao(): ScriptStorageDao
    abstract fun pendingWebProfileDeletionDao(): PendingWebProfileDeletionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_6 = migrationTo6(1)
        val MIGRATION_2_6 = migrationTo6(2)
        val MIGRATION_3_6 = migrationTo6(3)
        val MIGRATION_4_6 = migrationTo6(4)

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createScriptTables(db)
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pwas` ADD COLUMN `customUserAgent` TEXT")
                db.execSQL("ALTER TABLE `pwas` ADD COLUMN `customLanguage` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `pwas` ADD COLUMN `customPlatform` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `pwas` ADD COLUMN `screenWidth` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pwas` ADD COLUMN `screenHeight` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pwas` ADD COLUMN `deviceScaleFactor` REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pwas` ADD COLUMN `securityPromptEnabled` INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pwas` ADD COLUMN `webProfileId` TEXT")
                db.execSQL(
                    "ALTER TABLE `pwas` ADD COLUMN `usedSharedCompatibility` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_web_profile_deletions` (
                        `profileName` TEXT NOT NULL,
                        `requestedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`profileName`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE `script_storage_v9` (
                        `pwaId` INTEGER NOT NULL,
                        `storageKey` TEXT NOT NULL,
                        `storageValue` TEXT NOT NULL,
                        PRIMARY KEY(`pwaId`, `storageKey`),
                        FOREIGN KEY(`pwaId`) REFERENCES `pwas`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `script_storage_v9` (`pwaId`, `storageKey`, `storageValue`)
                    SELECT `pwaId`, `storageKey`, `storageValue`
                    FROM `script_storage`
                    WHERE `pwaId` IN (SELECT `id` FROM `pwas`)
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `script_storage`")
                db.execSQL("ALTER TABLE `script_storage_v9` RENAME TO `script_storage`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_script_storage_pwaId` ON `script_storage` (`pwaId`)"
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pwas` ADD COLUMN `showSwitcherHandle` INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pwas` ADD COLUMN `folderId` INTEGER")
                db.execSQL(
                    "ALTER TABLE `pwas` ADD COLUMN `folderOrder` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pwas_folderId` ON `pwas` (`folderId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pwa_folders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `displayOrder` INTEGER NOT NULL,
                        `addedTime` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private fun migrationTo6(fromVersion: Int) = object : Migration(fromVersion, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `pwas_v6` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `iconPath` TEXT NOT NULL,
                        `themeColor` TEXT,
                        `displayOrder` INTEGER NOT NULL,
                        `addedTime` INTEGER NOT NULL,
                        `useChromeUa` INTEGER NOT NULL,
                        `useDevConsole` INTEGER NOT NULL,
                        `useFullscreen` INTEGER NOT NULL,
                        `securityMode` INTEGER NOT NULL,
                        `trustedDomains` TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                val useChromeUa = if (fromVersion >= 2) "`useChromeUa`" else "1"
                val useDevConsole = if (fromVersion >= 3) "`useDevConsole`" else "0"
                val useFullscreen = if (fromVersion >= 4) "`useFullscreen`" else "0"
                val securityMode = if (fromVersion >= 5) "`securityMode`" else "1"
                val trustedDomains = if (fromVersion >= 5) "`trustedDomains`" else "''"
                db.execSQL(
                    """
                    INSERT INTO `pwas_v6` (
                        `id`, `name`, `url`, `iconPath`, `themeColor`, `displayOrder`, `addedTime`,
                        `useChromeUa`, `useDevConsole`, `useFullscreen`, `securityMode`, `trustedDomains`
                    )
                    SELECT
                        `id`, `name`, `url`, `iconPath`, `themeColor`, `displayOrder`, `addedTime`,
                        $useChromeUa, $useDevConsole, $useFullscreen, $securityMode, $trustedDomains
                    FROM `pwas`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `pwas`")
                db.execSQL("ALTER TABLE `pwas_v6` RENAME TO `pwas`")
                createScriptTables(db)
            }
        }

        private fun createScriptTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
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
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `script_storage` (
                    `pwaId` INTEGER NOT NULL,
                    `storageKey` TEXT NOT NULL,
                    `storageValue` TEXT NOT NULL,
                    PRIMARY KEY(`pwaId`, `storageKey`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_scripts_pwaId` ON `user_scripts` (`pwaId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_scripts_pwaId_name` ON `user_scripts` (`pwaId`, `name`)")
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pwa_shell_database"
                )
                .addMigrations(
                    MIGRATION_1_6,
                    MIGRATION_2_6,
                    MIGRATION_3_6,
                    MIGRATION_4_6,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
