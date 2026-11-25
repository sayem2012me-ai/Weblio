package com.example.engine

import com.example.data.WeblioRepository
import com.example.data.db.AdBlockRuleEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.FilterRuleType
import com.example.data.db.QuickLinkEntity
import com.example.data.prefs.BrowserPreferences
import com.example.data.prefs.BrowserSettings
import com.example.data.security.KeystoreCryptoManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local encrypted Backup & Restore engine + Netscape HTML Bookmarks import/export.
 * Sensitive backups (including passwords) are ALWAYS AES-256-GCM encrypted with a user passphrase.
 * Never outputs unencrypted passwords.
 */
class BackupRestoreManager(
    private val repository: WeblioRepository,
    private val preferences: BrowserPreferences
) {
    sealed class RestoreResult {
        data class Success(val summary: String) : RestoreResult()
        data class Error(val message: String) : RestoreResult()
    }

    suspend fun exportEncryptedBackup(
        profileId: Long,
        passphrase: String,
        includePasswords: Boolean
    ): Result<String> {
        if (passphrase.length < 6) {
            return Result.failure(IllegalArgumentException("Backup passphrase must be at least 6 characters."))
        }
        return runCatching {
            val settings = preferences.settingsFlow.first()
            val bookmarks = repository.observeBookmarks(profileId).first()
            val quickLinks = repository.observeQuickLinks(profileId).first()
            val customRules = repository.observeAdBlockRules().first()
                .filter { it.sourceList == "Custom User Rules" }
            val passwords = if (includePasswords) {
                repository.observePasswords(profileId).first().mapNotNull { p ->
                    repository.decryptPassword(p)?.let { plain -> Triple(p.originHost, p.username, plain) }
                }
            } else emptyList()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val payload = JSONObject().apply {
                    put("schemaVersion", BACKUP_SCHEMA_VERSION)
                    put("createdAt", System.currentTimeMillis())
                    put("settings", JSONObject().apply {
                        put("searchEngine", settings.defaultSearchEngine.name)
                        put("homepageUrl", settings.homepageUrl)
                        put("themeMode", settings.themeMode.name)
                        put("adBlockerEnabled", settings.adBlockerEnabled)
                        put("trackerProtectionEnabled", settings.trackerProtectionEnabled)
                        put("dnsFilterMode", settings.dnsFilterMode.name)
                        put("customDohUrl", settings.customDohUrl)
                    })
                    put("bookmarks", JSONArray().apply {
                        bookmarks.forEach { bm ->
                            put(JSONObject().apply {
                                put("title", bm.title)
                                put("url", bm.url)
                                put("folder", bm.folder)
                            })
                        }
                    })
                    put("quickLinks", JSONArray().apply {
                        quickLinks.forEach { ql ->
                            put(JSONObject().apply {
                                put("title", ql.title)
                                put("url", ql.url)
                                put("iconKey", ql.iconKey)
                            })
                        }
                    })
                    put("customRules", JSONArray().apply {
                        customRules.forEach { r ->
                            put(JSONObject().apply {
                                put("pattern", r.pattern)
                                put("ruleType", r.ruleType)
                            })
                        }
                    })
                    if (includePasswords) {
                        put("passwords", JSONArray().apply {
                            passwords.forEach { (host, user, plain) ->
                                put(JSONObject().apply {
                                    put("host", host)
                                    put("username", user)
                                    put("secret", plain) // Encrypted inside outer AES-GCM envelope
                                })
                            }
                        })
                    }
                }

                val (cipher, salt, iv) = KeystoreCryptoManager.encryptWithPassphrase(payload.toString(), passphrase)
                JSONObject().apply {
                    put("format", MAGIC_FORMAT)
                    put("version", BACKUP_SCHEMA_VERSION)
                    put("salt", salt)
                    put("iv", iv)
                    put("cipherText", cipher)
                }.toString(2)
            }
        }
    }

    suspend fun importEncryptedBackup(
        profileId: Long,
        rawEnvelope: String,
        passphrase: String
    ): RestoreResult {
        return try {
            val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val env = JSONObject(rawEnvelope)
                if (env.optString("format") != MAGIC_FORMAT) {
                    return@withContext null to RestoreResult.Error("Unrecognized backup file format.")
                }
                val version = env.optInt("version", -1)
                if (version < 1 || version > BACKUP_SCHEMA_VERSION) {
                    return@withContext null to RestoreResult.Error("Incompatible backup version ($version).")
                }

                val decryptedJson = KeystoreCryptoManager.decryptWithPassphrase(
                    cipherTextBase64 = env.getString("cipherText"),
                    saltBase64 = env.getString("salt"),
                    ivBase64 = env.getString("iv"),
                    passphrase = passphrase
                )
                val parsed = JSONObject(decryptedJson)
                if (parsed.optInt("schemaVersion") != version) {
                    return@withContext null to RestoreResult.Error("Backup integrity verification failed.")
                }
                parsed to null
            }
            data.second?.let { return it }
            val json = data.first!!

            json.optJSONObject("settings")?.let { s ->
                val searchEngine = runCatching {
                    com.example.data.prefs.SearchEngine.valueOf(s.getString("searchEngine"))
                }.getOrNull()
                val hp = if (s.has("homepageUrl")) {
                    s.getString("homepageUrl").trim().takeIf {
                        it == "weblio://home" || it.startsWith("https://", true) || it.startsWith("http://", true)
                    }
                } else null
                val theme = runCatching {
                    com.example.ui.theme.AppThemeMode.valueOf(s.getString("themeMode"))
                }.getOrNull()
                val adBlock = if (s.has("adBlockerEnabled")) s.getBoolean("adBlockerEnabled") else null
                val tracker = if (s.has("trackerProtectionEnabled")) s.getBoolean("trackerProtectionEnabled") else null
                val dnsMode = runCatching {
                    com.example.data.prefs.DnsFilterMode.valueOf(s.getString("dnsFilterMode"))
                }.getOrNull()
                val doh = if (s.has("customDohUrl")) {
                    s.getString("customDohUrl").trim().takeIf { it.isEmpty() || it.startsWith("https://", true) }
                } else null

                preferences.applyBackupSettings(
                    searchEngine = searchEngine,
                    homepageUrl = hp,
                    themeMode = theme,
                    adBlockerEnabled = adBlock,
                    trackerProtectionEnabled = tracker,
                    dnsFilterMode = dnsMode,
                    customDohUrl = doh
                )
            }

            var bmCount = 0
            json.optJSONArray("bookmarks")?.let { arr ->
                val maxItems = minOf(arr.length(), 2000)
                for (i in 0 until maxItems) {
                    val o = arr.getJSONObject(i)
                    val bmUrl = o.getString("url").trim()
                    if (bmUrl.startsWith("https://", true) || bmUrl.startsWith("http://", true)) {
                        repository.addBookmark(profileId, o.getString("title"), bmUrl, o.optString("folder", "General"))
                        bmCount++
                    }
                }
            }

            var ruleCount = 0
            json.optJSONArray("customRules")?.let { arr ->
                val maxItems = minOf(arr.length(), 2000)
                for (i in 0 until maxItems) {
                    val o = arr.getJSONObject(i)
                    val pattern = o.getString("pattern").trim()
                    if (pattern.isNotBlank() && pattern.length <= 512) {
                        val type = runCatching { FilterRuleType.valueOf(o.getString("ruleType")) }.getOrDefault(FilterRuleType.NETWORK_BLOCK)
                        repository.addAdBlockRule(pattern, type, "Custom User Rules")
                        ruleCount++
                    }
                }
            }

            var pwCount = 0
            json.optJSONArray("passwords")?.let { arr ->
                val maxItems = minOf(arr.length(), 2000)
                for (i in 0 until maxItems) {
                    val o = arr.getJSONObject(i)
                    val host = o.getString("host").trim()
                    val user = o.getString("username").trim()
                    val secret = o.getString("secret")
                    if (host.isNotBlank() && user.isNotBlank() && secret.isNotEmpty()) {
                        repository.savePassword(profileId, host, user, secret)
                        pwCount++
                    }
                }
            }

            RestoreResult.Success("Restored $bmCount bookmarks, $ruleCount rules, and $pwCount encrypted credentials.")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (err: Throwable) {
            RestoreResult.Error("Decryption failed: check your passphrase or file integrity (${err.javaClass.simpleName}).")
        }
    }

    suspend fun exportBookmarksHtml(profileId: Long): String {
        val bookmarks = repository.observeBookmarks(profileId).first()
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Weblo Bookmarks</TITLE>\n<H1>Weblo Bookmarks</H1>\n<DL><p>\n")
        bookmarks.groupBy { it.folder }.forEach { (folder, items) ->
            sb.append("  <DT><H3>").append(escapeHtml(folder)).append("</H3>\n  <DL><p>\n")
            items.forEach { bm ->
                sb.append("    <DT><A HREF=\"").append(escapeHtml(bm.url)).append("\">")
                    .append(escapeHtml(bm.title)).append("</A>\n")
            }
            sb.append("  </DL><p>\n")
        }
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    suspend fun importBookmarksHtml(profileId: Long, html: String): Int {
        val linkRegex = Regex("<A[^>]+HREF=\"([^\"]+)\"[^>]*>(.*?)</A>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        var imported = 0
        for (match in linkRegex.findAll(html)) {
            val url = match.groupValues[1].trim()
            val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim().ifBlank { url }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                repository.addBookmark(profileId, title, url, "Imported")
                imported++
            }
        }
        return imported
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        const val MAGIC_FORMAT = "WEBLIO_ENCRYPTED_BACKUP"
        const val BACKUP_SCHEMA_VERSION = 1
    }
}
