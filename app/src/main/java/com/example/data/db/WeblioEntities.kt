package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String = "person",
    val isGuest: Boolean = false,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val biometricEnabled: Boolean = false,
    val webProfileName: String = "profile_default",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tabs",
    indices = [Index("profileId")]
)
data class TabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val url: String = "weblio://home",
    val title: String = "New Tab",
    val isIncognito: Boolean = false,
    val groupName: String? = null,
    val desktopMode: Boolean = false,
    val position: Int = 0,
    val lastActiveAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "closed_tabs",
    indices = [Index("profileId")]
)
data class ClosedTabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val url: String,
    val title: String,
    val closedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("profileId"), Index("folder")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val title: String,
    val url: String,
    val folder: String = "General",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "history",
    indices = [Index("profileId"), Index("visitedAt")]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

enum class DownloadStatus {
    QUEUED, DOWNLOADING, SCANNING, QUARANTINED, PAUSED, COMPLETED, FAILED, CANCELLED
}

enum class DownloadFileCategory {
    PDF, APK, ARCHIVE, IMAGE, AUDIO, VIDEO, DOCUMENT, EXECUTABLE, SCRIPT, OTHER
}

@Entity(
    tableName = "downloads",
    indices = [Index("profileId")]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val category: String = DownloadFileCategory.OTHER.name,
    val localPath: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: String = DownloadStatus.QUEUED.name,
    val supportsRange: Boolean = false,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sha256: String = "",
    val securityVerdict: String = "UNSCANNED", // SAFE, SUSPICIOUS, DANGEROUS, UNVERIFIED, CANCELLED, UNSCANNED
    val threatCategory: String? = null,
    val threatReason: String? = null,
    val scanSummary: String? = null,
    val userOverridden: Boolean = false,
    val quarantined: Boolean = false
)

@Entity(
    tableName = "passwords",
    indices = [Index("profileId"), Index("originHost")]
)
data class PasswordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val originHost: String,
    val username: String,
    val encryptedPassword: String,
    val iv: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "quick_links",
    indices = [Index("profileId")]
)
data class QuickLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val title: String,
    val url: String,
    val iconKey: String = "globe",
    val position: Int = 0
)

@Entity(
    tableName = "extensions",
    indices = [Index("profileId"), Index("extId")]
)
data class ExtensionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val extId: String,
    val name: String,
    val version: String,
    val description: String,
    val sourceType: String, // "BUILTIN_ENGINE", "CHROME_WEBEXT", "WEBEXT_ZIP", "USERSCRIPT_JS"
    val enabled: Boolean = true,
    val permissionsJson: String = "[]",
    val matchPatternsJson: String = "[\"<all_urls>\"]",
    val runAt: String = "document_end",
    val installPath: String = "",
    val hasPopup: Boolean = false,
    val popupPath: String = "",
    val scriptContent: String = "",
    val cssContent: String = "",
    val lastError: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
    val contentHashSha256: String = "",
    val riskLevel: String = "LOW", // LOW, SENSITIVE, HIGH
    val permissionWarningsJson: String = "[]"
)

enum class FilterRuleType {
    NETWORK_BLOCK, NETWORK_ALLOW, COSMETIC_HIDE, DNS_BLOCK
}

@Entity(
    tableName = "adblock_rules",
    indices = [Index("ruleType"), Index("enabled")]
)
data class AdBlockRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val ruleType: String = FilterRuleType.NETWORK_BLOCK.name,
    val sourceList: String = "WEBLIO Core Shield",
    val enabled: Boolean = true,
    val hitCount: Long = 0L
)

@Entity(
    tableName = "adblock_subscriptions"
)
data class FilterSubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val ruleCount: Int = 0,
    val lastUpdatedAt: Long = 0L
)

@Entity(
    tableName = "site_permissions",
    indices = [Index(value = ["profileId", "originHost"], unique = true)]
)
data class SitePermissionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val originHost: String,
    val adBlockEnabled: Boolean = true,
    val javascriptEnabled: Boolean = true,
    val desktopMode: Boolean = false,
    val cameraAllowed: String = "ASK",
    val micAllowed: String = "ASK",
    val locationAllowed: String = "ASK",
    val notificationsAllowed: String = "ASK",
    val cookiesAllowed: Boolean = true
)

@Entity(
    tableName = "security_events",
    indices = [Index("createdAt"), Index("category")]
)
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // DOWNLOAD, WEBSITE, EXTENSION, INTEGRITY, UPDATE, NETWORK
    val severity: String, // INFO, WARNING, CRITICAL
    val title: String,
    val summary: String,
    val targetHostOrFile: String = "",
    val actionTaken: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "threat_indicators",
    indices = [Index(value = ["indicator", "indicatorType"], unique = true)]
)
data class ThreatIndicatorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val indicator: String, // lowercase domain, URL hash prefix, or SHA-256 malware hash
    val indicatorType: String, // DOMAIN, HASH_SHA256, URL_SHA256_PREFIX
    val category: String, // PHISHING, MALWARE, SCAM, SHORTENER, EICAR_TEST
    val severity: String, // SUSPICIOUS, DANGEROUS
    val source: String,
    val updatedAt: Long = System.currentTimeMillis()
)
