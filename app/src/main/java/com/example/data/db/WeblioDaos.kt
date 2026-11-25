package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY isGuest ASC, id ASC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProfileEntity?

    @Query("SELECT * FROM profiles ORDER BY id ASC")
    suspend fun getAllOnce(): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Delete
    suspend fun delete(profile: ProfileEntity)
}

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs WHERE profileId = :profileId ORDER BY position ASC, id ASC")
    fun observeTabs(profileId: Long): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs WHERE profileId = :profileId ORDER BY lastActiveAt DESC")
    suspend fun getTabsOnce(profileId: Long): List<TabEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tab: TabEntity): Long

    @Update
    suspend fun update(tab: TabEntity)

    @Query("UPDATE tabs SET title = :title WHERE id = :tabId")
    suspend fun updateTitle(tabId: Long, title: String)

    @Query("UPDATE tabs SET url = :url, title = :title, lastActiveAt = :lastActiveAt WHERE id = :tabId")
    suspend fun updateUrlAndTitle(tabId: Long, url: String, title: String, lastActiveAt: Long)

    @Query("DELETE FROM tabs WHERE id = :tabId")
    suspend fun deleteById(tabId: Long)

    @Query("DELETE FROM tabs WHERE profileId = :profileId AND isIncognito = :isIncognito")
    suspend fun deleteAllByType(profileId: Long, isIncognito: Boolean)

    @Query("DELETE FROM tabs WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)

    @Query("SELECT * FROM closed_tabs WHERE profileId = :profileId ORDER BY closedAt DESC LIMIT 20")
    fun observeClosedTabs(profileId: Long): Flow<List<ClosedTabEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClosedTab(tab: ClosedTabEntity): Long

    @Query("DELETE FROM closed_tabs WHERE id = :id")
    suspend fun deleteClosedTab(id: Long)

    @Query("DELETE FROM closed_tabs WHERE profileId = :profileId")
    suspend fun clearClosedTabs(profileId: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE profileId = :profileId ORDER BY createdAt DESC")
    fun observeBookmarks(profileId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE profileId = :profileId")
    suspend fun getOnce(profileId: Long): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE profileId = :profileId AND url = :url")
    suspend fun deleteByUrl(profileId: Long, url: String)

    @Query("DELETE FROM bookmarks WHERE profileId = :profileId AND url = :url AND folder = :folder")
    suspend fun deleteByUrlAndFolder(profileId: Long, url: String, folder: String)

    @Query("DELETE FROM bookmarks WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY visitedAt DESC LIMIT 500")
    fun observeHistory(profileId: Long): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history WHERE profileId = :profileId AND visitedAt >= :sinceMillis")
    suspend fun deleteSince(profileId: Long, sinceMillis: Long)

    @Query("DELETE FROM history WHERE profileId = :profileId")
    suspend fun clearAllForProfile(profileId: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE profileId = :profileId ORDER BY createdAt DESC")
    fun observeDownloads(profileId: Long): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity): Long

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: Long)
}

@Dao
interface PasswordDao {
    @Query("SELECT * FROM passwords WHERE profileId = :profileId ORDER BY updatedAt DESC")
    fun observePasswords(profileId: Long): Flow<List<PasswordEntity>>

    @Query("SELECT * FROM passwords WHERE profileId = :profileId")
    suspend fun getOnce(profileId: Long): List<PasswordEntity>

    @Query("SELECT * FROM passwords WHERE profileId = :profileId AND originHost = :host")
    suspend fun findByHost(profileId: Long, host: String): List<PasswordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(password: PasswordEntity): Long

    @Update
    suspend fun update(password: PasswordEntity)

    @Delete
    suspend fun delete(password: PasswordEntity)

    @Query("DELETE FROM passwords WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: Long)
}

@Dao
interface QuickLinkDao {
    @Query("SELECT * FROM quick_links WHERE profileId = :profileId ORDER BY position ASC, id ASC")
    fun observeQuickLinks(profileId: Long): Flow<List<QuickLinkEntity>>

    @Query("SELECT * FROM quick_links WHERE profileId = :profileId ORDER BY position ASC")
    suspend fun getOnce(profileId: Long): List<QuickLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: QuickLinkEntity): Long

    @Update
    suspend fun update(link: QuickLinkEntity)

    @Delete
    suspend fun delete(link: QuickLinkEntity)
}

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extensions WHERE profileId = :profileId ORDER BY installedAt DESC")
    fun observeExtensions(profileId: Long): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions WHERE profileId = :profileId")
    suspend fun getOnce(profileId: Long): List<ExtensionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ext: ExtensionEntity): Long

    @Update
    suspend fun update(ext: ExtensionEntity)

    @Delete
    suspend fun delete(ext: ExtensionEntity)

    @Query("DELETE FROM extensions WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)
}

@Dao
interface AdBlockDao {
    @Query("SELECT * FROM adblock_rules ORDER BY id DESC")
    fun observeRules(): Flow<List<AdBlockRuleEntity>>

    @Query("SELECT * FROM adblock_rules WHERE enabled = 1")
    suspend fun getActiveRulesOnce(): List<AdBlockRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AdBlockRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<AdBlockRuleEntity>)

    @Update
    suspend fun updateRule(rule: AdBlockRuleEntity)

    @Delete
    suspend fun deleteRule(rule: AdBlockRuleEntity)

    @Query("DELETE FROM adblock_rules WHERE sourceList = :sourceList")
    suspend fun deleteBySource(sourceList: String)

    @Query("UPDATE adblock_rules SET hitCount = hitCount + 1 WHERE id = :ruleId")
    suspend fun incrementHit(ruleId: Long)

    @Query("UPDATE adblock_rules SET hitCount = hitCount + :delta WHERE id = :ruleId")
    suspend fun incrementHitBy(ruleId: Long, delta: Long)

    @Query("SELECT * FROM adblock_subscriptions ORDER BY id ASC")
    fun observeSubscriptions(): Flow<List<FilterSubscriptionEntity>>

    @Query("SELECT * FROM adblock_subscriptions")
    suspend fun getSubscriptionsOnce(): List<FilterSubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(sub: FilterSubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(sub: FilterSubscriptionEntity)

    @Delete
    suspend fun deleteSubscription(sub: FilterSubscriptionEntity)
}

@Dao
interface SitePermissionDao {
    @Query("SELECT * FROM site_permissions WHERE profileId = :profileId ORDER BY originHost ASC")
    fun observeSitePermissions(profileId: Long): Flow<List<SitePermissionEntity>>

    @Query("SELECT * FROM site_permissions WHERE profileId = :profileId AND originHost = :host LIMIT 1")
    suspend fun getForHost(profileId: Long, host: String): SitePermissionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(permission: SitePermissionEntity): Long

    @Delete
    suspend fun delete(permission: SitePermissionEntity)

    @Query("DELETE FROM site_permissions WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)
}

@Dao
interface SecurityEventDao {
    @Query("SELECT * FROM security_events ORDER BY createdAt DESC LIMIT 200")
    fun observeEvents(): Flow<List<SecurityEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SecurityEventEntity): Long

    @Query("DELETE FROM security_events")
    suspend fun clearEvents()

    @Query("SELECT * FROM threat_indicators WHERE indicator = :indicator AND indicatorType = :type LIMIT 1")
    suspend fun findThreat(indicator: String, type: String): ThreatIndicatorEntity?

    @Query("SELECT COUNT(*) FROM threat_indicators")
    suspend fun countThreats(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThreats(items: List<ThreatIndicatorEntity>)

    @Query("DELETE FROM threat_indicators WHERE updatedAt < :olderThanMillis")
    suspend fun pruneExpiredThreats(olderThanMillis: Long)
}

