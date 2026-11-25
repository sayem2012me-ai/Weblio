package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        TabEntity::class,
        ClosedTabEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        PasswordEntity::class,
        QuickLinkEntity::class,
        ExtensionEntity::class,
        AdBlockRuleEntity::class,
        FilterSubscriptionEntity::class,
        SitePermissionEntity::class,
        SecurityEventEntity::class,
        ThreatIndicatorEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class WeblioDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun tabDao(): TabDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun passwordDao(): PasswordDao
    abstract fun quickLinkDao(): QuickLinkDao
    abstract fun extensionDao(): ExtensionDao
    abstract fun adBlockDao(): AdBlockDao
    abstract fun sitePermissionDao(): SitePermissionDao
    abstract fun securityEventDao(): SecurityEventDao

    companion object {
        private val instances = java.util.WeakHashMap<Context, WeblioDatabase>()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN securityVerdict TEXT NOT NULL DEFAULT 'UNSCANNED'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN threatCategory TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN threatReason TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN scanSummary TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN userOverridden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN quarantined INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE extensions ADD COLUMN contentHashSha256 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extensions ADD COLUMN riskLevel TEXT NOT NULL DEFAULT 'LOW'")
                db.execSQL("ALTER TABLE extensions ADD COLUMN permissionWarningsJson TEXT NOT NULL DEFAULT '[]'")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS security_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        category TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        targetHostOrFile TEXT NOT NULL,
                        actionTaken TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_security_events_createdAt ON security_events(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_security_events_category ON security_events(category)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS threat_indicators (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        indicator TEXT NOT NULL,
                        indicatorType TEXT NOT NULL,
                        category TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        source TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_threat_indicators_indicator_indicatorType ON threat_indicators(indicator, indicatorType)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN publicUri TEXT")
            }
        }

        fun getInstance(context: Context): WeblioDatabase {
            val appCtx = context.applicationContext ?: context
            synchronized(this) {
                val existing = instances[appCtx]
                if (existing != null) return existing
                return Room.databaseBuilder(
                    appCtx,
                    WeblioDatabase::class.java,
                    "weblio_browser.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instances[appCtx] = it }
            }
        }
    }
}
