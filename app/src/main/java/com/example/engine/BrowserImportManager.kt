package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.data.WeblioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class BrowserFamily {
    CHROME,
    EDGE,
    FIREFOX,
    BRAVE,
    OPERA,
    SAMSUNG,
    DUCKDUCKGO,
    VIVALDI,
    KIWI,
    YANDEX,
    GENERIC
}

enum class ImportDataType(val label: String) {
    HISTORY("History & Sites"),
    BOOKMARKS("Bookmarks"),
    PASSWORDS("Passwords"),
    TOP_SITES("Top Sites")
}

data class InstalledBrowserInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val family: BrowserFamily,
    val exportGuideSteps: List<String>
)

data class ImportDataSummary(
    val sourceName: String,
    val historyCount: Int,
    val bookmarkCount: Int,
    val passwordCount: Int,
    val quickLinkCount: Int
) {
    val totalCount: Int
        get() = historyCount + bookmarkCount + passwordCount + quickLinkCount

    fun toUserMessage(): String {
        if (totalCount == 0) {
            return "No new entries found to import from $sourceName."
        }
        val parts = buildList {
            if (historyCount > 0) add("$historyCount visited sites")
            if (bookmarkCount > 0) add("$bookmarkCount bookmarks")
            if (passwordCount > 0) add("$passwordCount passwords")
            if (quickLinkCount > 0) add("$quickLinkCount top sites")
        }
        return "Imported ${parts.joinToString(", ")} from $sourceName."
    }
}

/**
 * Discovers installed system browsers (via Play-compliant <queries> ACTION_VIEW https)
 * and imports browsing history, bookmarks, passwords, and top sites from standard
 * browser export formats (JSON, Netscape HTML, CSV, and copied link lists).
 */
class BrowserImportManager(
    private val repository: WeblioRepository
) {
    fun discoverInstalledBrowsers(context: Context): List<InstalledBrowserInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val resolveInfos = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
        }.getOrElse { emptyList() }

        val seenPackages = HashSet<String>()
        val results = ArrayList<InstalledBrowserInfo>()
        for (info in resolveInfos) {
            val activityInfo = info.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            if (pkg == context.packageName || !seenPackages.add(pkg)) continue
            val label = runCatching { info.loadLabel(pm).toString().trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: pkg.substringAfterLast('.')
            val icon = runCatching { info.loadIcon(pm) }.getOrNull()
            val family = detectBrowserFamily(pkg, label)
            results.add(
                InstalledBrowserInfo(
                    packageName = pkg,
                    appName = label,
                    icon = icon,
                    family = family,
                    exportGuideSteps = buildGuideSteps(label, family)
                )
            )
        }

        return results.sortedWith(
            compareBy<InstalledBrowserInfo> { familyPriority(it.family) }
                .thenBy { it.appName.lowercase() }
        )
    }

    suspend fun importFromUris(
        context: Context,
        profileId: Long,
        uris: List<Uri>,
        sourceName: String,
        selectedTypes: Set<ImportDataType> = ImportDataType.entries.toSet()
    ): ImportDataSummary = withContext(Dispatchers.IO) {
        var totalHistory = 0
        var totalBookmarks = 0
        var totalPasswords = 0
        var totalQuickLinks = 0

        for (uri in uris) {
            val fileName = resolveDisplayName(context, uri)
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            }.getOrNull().orEmpty()

            if (text.isBlank()) continue
            val partial = parseAndImport(
                profileId = profileId,
                rawText = text,
                sourceName = sourceName,
                selectedTypes = selectedTypes,
                fileNameHint = fileName
            )
            totalHistory += partial.historyCount
            totalBookmarks += partial.bookmarkCount
            totalPasswords += partial.passwordCount
            totalQuickLinks += partial.quickLinkCount
        }

        ImportDataSummary(
            sourceName = sourceName,
            historyCount = totalHistory,
            bookmarkCount = totalBookmarks,
            passwordCount = totalPasswords,
            quickLinkCount = totalQuickLinks
        )
    }

    suspend fun parseAndImport(
        profileId: Long,
        rawText: String,
        sourceName: String,
        selectedTypes: Set<ImportDataType> = ImportDataType.entries.toSet(),
        fileNameHint: String = ""
    ): ImportDataSummary = withContext(Dispatchers.Default) {
        val cleanText = rawText.removePrefix("\uFEFF").trim()
        if (cleanText.isEmpty()) {
            return@withContext ImportDataSummary(sourceName, 0, 0, 0, 0)
        }

        val parsed = ParsedBrowserData()
        val hintLower = fileNameHint.lowercase()

        when {
            cleanText.startsWith("{") || cleanText.startsWith("[") -> {
                parseJson(cleanText, sourceName, hintLower, parsed)
            }
            cleanText.contains("<a ", ignoreCase = true) ||
                cleanText.contains("NETSCAPE-Bookmark-file", ignoreCase = true) ||
                cleanText.contains("<html", ignoreCase = true) -> {
                parseHtml(cleanText, sourceName, hintLower, parsed)
            }
            looksLikeCsv(cleanText) -> {
                parseCsv(cleanText, sourceName, hintLower, parsed)
            }
            else -> {
                parsePlainUrls(cleanText, sourceName, parsed)
            }
        }

        withContext(Dispatchers.IO) {
            val historyAdded = if (ImportDataType.HISTORY in selectedTypes && parsed.history.isNotEmpty()) {
                repository.importHistoryEntries(
                    profileId = profileId,
                    entries = parsed.history.sortedByDescending { it.visitedAt }
                        .map { Triple(it.url, it.title, it.visitedAt) }
                )
            } else 0

            val bookmarksAdded = if (ImportDataType.BOOKMARKS in selectedTypes && parsed.bookmarks.isNotEmpty()) {
                repository.importBookmarksDeduplicated(
                    profileId = profileId,
                    items = parsed.bookmarks.map { Triple(it.url, it.title, it.folder) }
                )
            } else 0

            var passwordsAdded = 0
            if (ImportDataType.PASSWORDS in selectedTypes && parsed.passwords.isNotEmpty()) {
                for (pw in parsed.passwords.take(2000)) {
                    val saved = runCatching {
                        repository.savePassword(profileId, pw.host, pw.username, pw.password)
                        true
                    }.getOrDefault(false)
                    if (saved) passwordsAdded++
                }
            }

            val topSiteCandidates = buildList {
                parsed.quickLinks.forEach { add(it) }
                if (isEmpty()) {
                    // Derive top unique sites from most recent history or bookmarks
                    val byFrequency = parsed.history.groupingBy { normalizeHostRoot(it.url) }.eachCount()
                    parsed.history
                        .distinctBy { normalizeHostRoot(it.url) }
                        .sortedByDescending { byFrequency[normalizeHostRoot(it.url)] ?: 0 }
                        .take(6)
                        .forEach { h -> add(shortSiteTitle(h.title, h.url) to h.url) }
                    if (isEmpty()) {
                        parsed.bookmarks.take(6).forEach { b ->
                            add(shortSiteTitle(b.title, b.url) to b.url)
                        }
                    }
                }
            }
            val quickLinksAdded = if (ImportDataType.TOP_SITES in selectedTypes && topSiteCandidates.isNotEmpty()) {
                repository.importQuickLinksDeduplicated(profileId, topSiteCandidates, maxAdditions = 6)
            } else 0

            ImportDataSummary(
                sourceName = sourceName,
                historyCount = historyAdded,
                bookmarkCount = bookmarksAdded,
                passwordCount = passwordsAdded,
                quickLinkCount = quickLinksAdded
            )
        }
    }

    private fun parseJson(
        rawJson: String,
        sourceName: String,
        hintLower: String,
        out: ParsedBrowserData
    ) {
        if (rawJson.startsWith("[")) {
            val arr = runCatching { JSONArray(rawJson) }.getOrNull() ?: return
            parseGenericJsonArray(arr, sourceName, hintLower, out)
            return
        }
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return

        // 1. Google Takeout / Chrome History JSON: {"Browser History": [ ... ]}
        val browserHistoryArr = root.optJSONArray("Browser History")
            ?: root.optJSONArray("History")
            ?: root.optJSONArray("history")
        if (browserHistoryArr != null) {
            for (i in 0 until browserHistoryArr.length()) {
                val obj = browserHistoryArr.optJSONObject(i) ?: continue
                val url = obj.optString("url").ifBlank { obj.optString("uri") }.trim()
                if (!isHttpUrl(url)) continue
                val title = obj.optString("title").ifBlank { obj.optString("name") }.trim().ifBlank { url }
                val ts = extractTimestampMillis(obj)
                out.history.add(ImportedHistoryItem(url, title, ts))
            }
        }

        // 2. Chromium Bookmarks JSON: {"roots": {"bookmark_bar": {...}, "other": {...}}}
        val roots = root.optJSONObject("roots")
        if (roots != null) {
            val rootKeys = roots.keys()
            while (rootKeys.hasNext()) {
                val key = rootKeys.next()
                roots.optJSONObject(key)?.let { node ->
                    val folderName = node.optString("name").ifBlank { sourceName }
                    walkBookmarkTree(node, folderName, out)
                }
            }
        }

        // 3. Firefox JSON Backup tree (root has children / uri / typeCode)
        if (root.has("children") || root.has("uri")) {
            val isHistoryTree = hintLower.contains("history") ||
                root.optString("title").equals("History", ignoreCase = true)
            walkFirefoxTree(root, sourceName, isHistoryTree, out)
        }

        // 4. Explicit bookmarks / passwords / quickLinks arrays
        root.optJSONArray("bookmarks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").ifBlank { obj.optString("uri") }.trim()
                if (!isHttpUrl(url)) continue
                val title = obj.optString("title").ifBlank { obj.optString("name") }.trim().ifBlank { url }
                val folder = obj.optString("folder").trim().ifBlank { sourceName }
                out.bookmarks.add(ImportedBookmarkItem(url, title, folder))
            }
        }
        root.optJSONArray("passwords")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val urlOrHost = obj.optString("url").ifBlank { obj.optString("originHost") }.ifBlank { obj.optString("host") }
                val host = extractHostOrClean(urlOrHost)
                val user = obj.optString("username").ifBlank { obj.optString("user") }.trim()
                val secret = obj.optString("password").ifBlank { obj.optString("secret") }
                if (host.isNotBlank() && secret.isNotEmpty()) {
                    out.passwords.add(ImportedPasswordItem(host, user.ifBlank { host }, secret))
                }
            }
        }
        root.optJSONArray("quickLinks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                val title = obj.optString("title").trim()
                if (isHttpUrl(url)) {
                    out.quickLinks.add(shortSiteTitle(title, url) to url)
                }
            }
        }
    }

    private fun parseGenericJsonArray(
        arr: JSONArray,
        sourceName: String,
        hintLower: String,
        out: ParsedBrowserData
    ) {
        val defaultToHistory = hintLower.contains("history") || hintLower.contains("visit")
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj == null) {
                val str = arr.optString(i).trim()
                if (isHttpUrl(str)) {
                    out.history.add(ImportedHistoryItem(str, str, 0L))
                }
                continue
            }
            val secret = obj.optString("password").ifBlank { obj.optString("secret") }
            val rawUrl = obj.optString("url")
                .ifBlank { obj.optString("uri") }
                .ifBlank { obj.optString("originHost") }
                .ifBlank { obj.optString("host") }
                .trim()

            if (secret.isNotEmpty() && rawUrl.isNotBlank()) {
                val host = extractHostOrClean(rawUrl)
                val user = obj.optString("username").ifBlank { obj.optString("user") }.trim()
                if (host.isNotBlank()) {
                    out.passwords.add(ImportedPasswordItem(host, user.ifBlank { host }, secret))
                }
                continue
            }
            if (!isHttpUrl(rawUrl)) continue
            val title = obj.optString("title").ifBlank { obj.optString("name") }.trim().ifBlank { rawUrl }
            val ts = extractTimestampMillis(obj)
            if (defaultToHistory || ts > 0L || obj.has("visitCount") || obj.has("visit_count")) {
                out.history.add(ImportedHistoryItem(rawUrl, title, ts))
            } else {
                val folder = obj.optString("folder").trim().ifBlank { sourceName }
                out.bookmarks.add(ImportedBookmarkItem(rawUrl, title, folder))
            }
        }
    }

    private fun walkBookmarkTree(node: JSONObject, currentFolder: String, out: ParsedBrowserData) {
        val type = node.optString("type").lowercase()
        val url = node.optString("url").trim()
        val name = node.optString("name").trim()
        if ((type == "url" || url.isNotEmpty()) && isHttpUrl(url)) {
            out.bookmarks.add(ImportedBookmarkItem(url, name.ifBlank { url }, currentFolder))
            return
        }
        val nextFolder = name.ifBlank { currentFolder }
        val children = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            children.optJSONObject(i)?.let { child ->
                walkBookmarkTree(child, nextFolder, out)
            }
        }
    }

    private fun walkFirefoxTree(
        node: JSONObject,
        currentFolder: String,
        isHistoryTree: Boolean,
        out: ParsedBrowserData
    ) {
        val uri = node.optString("uri").ifBlank { node.optString("url") }.trim()
        val title = node.optString("title").trim()
        if (isHttpUrl(uri)) {
            val ts = extractTimestampMillis(node)
            if (isHistoryTree) {
                out.history.add(ImportedHistoryItem(uri, title.ifBlank { uri }, ts))
            } else {
                out.bookmarks.add(ImportedBookmarkItem(uri, title.ifBlank { uri }, currentFolder))
            }
        }
        val nextFolder = if (title.isNotBlank()) title else currentFolder
        val children = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            children.optJSONObject(i)?.let { child ->
                val childIsHistory = isHistoryTree || title.equals("History", ignoreCase = true)
                walkFirefoxTree(child, nextFolder, childIsHistory, out)
            }
        }
    }

    private fun parseHtml(
        html: String,
        sourceName: String,
        hintLower: String,
        out: ParsedBrowserData
    ) {
        val isHistoryExport = hintLower.contains("history") ||
            Regex("<(?:TITLE|H1)[^>]*>[^<]*History[^<]*</(?:TITLE|H1)>", RegexOption.IGNORE_CASE).containsMatchIn(html)

        val tokenRegex = Regex(
            pattern = "<H3[^>]*>(.*?)</H3>|<A\\s+([^>]*)>(.*?)</A>",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val hrefRegex = Regex("""HREF\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val addDateRegex = Regex("""(?:ADD_DATE|LAST_VISIT)\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)

        var currentFolder = sourceName
        for (match in tokenRegex.findAll(html)) {
            val heading = match.groupValues[1]
            if (heading.isNotEmpty()) {
                val cleanedFolder = stripTagsAndUnescape(heading)
                if (cleanedFolder.isNotBlank()) {
                    currentFolder = cleanedFolder
                }
                continue
            }
            val attrs = match.groupValues[2]
            val linkInner = match.groupValues[3]
            val href = hrefRegex.find(attrs)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (!isHttpUrl(href)) continue
            val title = stripTagsAndUnescape(linkInner).ifBlank { href }
            val rawDate = addDateRegex.find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val ts = normalizeTimestamp(rawDate)

            if (isHistoryExport || attrs.contains("LAST_VISIT", ignoreCase = true)) {
                out.history.add(ImportedHistoryItem(href, title, ts))
            } else {
                out.bookmarks.add(ImportedBookmarkItem(href, title, currentFolder))
            }
        }
    }

    private fun parseCsv(
        csvText: String,
        sourceName: String,
        hintLower: String,
        out: ParsedBrowserData
    ) {
        val rows = parseCsvRows(csvText)
        if (rows.size < 2) {
            parsePlainUrls(csvText, sourceName, out)
            return
        }
        val header = rows.first().map { it.trim().lowercase() }
        val urlIdx = header.indexOfFirst {
            it == "url" || it == "origin" || it == "website" || it == "site" || it == "uri" || it == "hostname"
        }
        val titleIdx = header.indexOfFirst { it == "title" || it == "name" }
        val userIdx = header.indexOfFirst { it == "username" || it == "user" || it == "login" }
        val passIdx = header.indexOfFirst { it == "password" || it == "pass" || it == "secret" }
        val timeIdx = header.indexOfFirst {
            it.contains("visit_time") || it == "visited_at" || it == "date" || it == "timestamp" || it == "time"
        }
        val folderIdx = header.indexOfFirst { it == "folder" || it == "category" }

        if (passIdx >= 0 && urlIdx >= 0) {
            for (row in rows.drop(1)) {
                val rawUrl = row.getOrNull(urlIdx)?.trim().orEmpty()
                val secret = row.getOrNull(passIdx).orEmpty()
                if (rawUrl.isBlank() || secret.isEmpty()) continue
                val host = extractHostOrClean(rawUrl)
                val user = row.getOrNull(userIdx)?.trim().orEmpty().ifBlank { host }
                if (host.isNotBlank()) {
                    out.passwords.add(ImportedPasswordItem(host, user, secret))
                }
            }
            return
        }

        if (urlIdx >= 0) {
            val isBookmarkCsv = folderIdx >= 0 || hintLower.contains("bookmark")
            for (row in rows.drop(1)) {
                val url = row.getOrNull(urlIdx)?.trim().orEmpty()
                if (!isHttpUrl(url)) continue
                val title = row.getOrNull(titleIdx)?.trim().orEmpty().ifBlank { url }
                if (isBookmarkCsv) {
                    val folder = row.getOrNull(folderIdx)?.trim().orEmpty().ifBlank { sourceName }
                    out.bookmarks.add(ImportedBookmarkItem(url, title, folder))
                } else {
                    val rawTs = row.getOrNull(timeIdx)?.trim()?.toLongOrNull() ?: 0L
                    out.history.add(ImportedHistoryItem(url, title, normalizeTimestamp(rawTs)))
                }
            }
            return
        }

        parsePlainUrls(csvText, sourceName, out)
    }

    private fun parsePlainUrls(text: String, sourceName: String, out: ParsedBrowserData) {
        val urlRegex = Regex("""https?://[^\s<>"'\])]+""", RegexOption.IGNORE_CASE)
        val seen = HashSet<String>()
        for (match in urlRegex.findAll(text)) {
            val clean = match.value.trimEnd('.', ',', ';')
            if (isHttpUrl(clean) && seen.add(clean)) {
                val hostTitle = Uri.parse(clean).host ?: clean
                out.history.add(ImportedHistoryItem(clean, hostTitle, 0L))
                out.bookmarks.add(ImportedBookmarkItem(clean, hostTitle, sourceName))
            }
        }
    }

    private fun looksLikeCsv(text: String): Boolean {
        val firstLine = text.lineSequence().firstOrNull()?.lowercase().orEmpty()
        if (!firstLine.contains(",")) return false
        return firstLine.contains("url") ||
            firstLine.contains("password") ||
            firstLine.contains("username") ||
            firstLine.contains("origin")
    }

    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var currentRow = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            cell.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        cell.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == ',' -> {
                    currentRow.add(cell.toString())
                    cell.setLength(0)
                }
                ch == '\r' -> Unit
                ch == '\n' -> {
                    currentRow.add(cell.toString())
                    cell.setLength(0)
                    if (currentRow.any { it.isNotBlank() }) {
                        rows.add(currentRow)
                    }
                    currentRow = ArrayList()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(cell.toString())
            if (currentRow.any { it.isNotBlank() }) {
                rows.add(currentRow)
            }
        }
        return rows
    }

    private fun extractTimestampMillis(obj: JSONObject): Long {
        val candidates = listOf(
            obj.optLong("time_usec", 0L),
            obj.optLong("lastVisitTime", 0L),
            obj.optLong("visitedAt", 0L),
            obj.optLong("dateAdded", 0L),
            obj.optLong("date_added", 0L),
            obj.optLong("timestamp", 0L)
        )
        val raw = candidates.firstOrNull { it > 0L } ?: 0L
        return normalizeTimestamp(raw)
    }

    private fun normalizeTimestamp(raw: Long): Long {
        if (raw <= 0L) return 0L
        return when {
            // Chrome/WebKit Windows epoch (since 1601-01-01) in microseconds (~1.3e16)
            raw >= 10_000_000_000_000_000L -> ((raw / 1000L) - 11_644_473_600_000L).coerceAtLeast(0L)
            // Unix epoch in microseconds (~1.7e15)
            raw >= 1_000_000_000_000_000L -> raw / 1000L
            // Unix epoch in milliseconds (~1.7e12)
            raw >= 100_000_000_000L -> raw
            // Unix epoch in seconds (~1.7e9)
            else -> raw * 1000L
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    }

    private fun extractHostOrClean(urlOrHost: String): String {
        val trimmed = urlOrHost.trim()
        if (trimmed.isBlank()) return ""
        val parsedHost = runCatching { Uri.parse(trimmed).host }.getOrNull()
        return if (!parsedHost.isNullOrBlank()) {
            parsedHost
        } else {
            trimmed.removePrefix("https://").removePrefix("http://").substringBefore("/").trim()
        }
    }

    private fun normalizeHostRoot(url: String): String {
        return runCatching { Uri.parse(url).host?.removePrefix("www.")?.lowercase() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: url.lowercase()
    }

    private fun shortSiteTitle(title: String, url: String): String {
        val clean = title.trim()
        if (clean.isNotBlank() && !clean.startsWith("http", ignoreCase = true)) {
            return clean.take(28)
        }
        return runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull().orEmpty().ifBlank { clean }
    }

    private fun isHttpUrl(url: String): Boolean {
        return url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    }

    private fun stripTagsAndUnescape(input: String): String {
        return input
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .trim()
    }

    private fun detectBrowserFamily(pkg: String, label: String): BrowserFamily {
        val p = pkg.lowercase()
        val l = label.lowercase()
        return when {
            p.contains("chrome") || l.contains("chrome") -> BrowserFamily.CHROME
            p.contains("emmx") || l.contains("edge") -> BrowserFamily.EDGE
            p.contains("firefox") || p.contains("fenix") || p.contains("mozilla") || l.contains("firefox") -> BrowserFamily.FIREFOX
            p.contains("brave") || l.contains("brave") -> BrowserFamily.BRAVE
            p.contains("opera") || l.contains("opera") -> BrowserFamily.OPERA
            p.contains("sbrowser") || p.contains("sec.android.app.sbrowser") || l.contains("samsung internet") -> BrowserFamily.SAMSUNG
            p.contains("duckduckgo") || l.contains("duckduckgo") -> BrowserFamily.DUCKDUCKGO
            p.contains("vivaldi") || l.contains("vivaldi") -> BrowserFamily.VIVALDI
            p.contains("kiwibrowser") || l.contains("kiwi") -> BrowserFamily.KIWI
            p.contains("yandex") || l.contains("yandex") -> BrowserFamily.YANDEX
            else -> BrowserFamily.GENERIC
        }
    }

    private fun familyPriority(family: BrowserFamily): Int = when (family) {
        BrowserFamily.CHROME -> 0
        BrowserFamily.SAMSUNG -> 1
        BrowserFamily.FIREFOX -> 2
        BrowserFamily.EDGE -> 3
        BrowserFamily.BRAVE -> 4
        BrowserFamily.OPERA -> 5
        BrowserFamily.DUCKDUCKGO -> 6
        BrowserFamily.VIVALDI -> 7
        BrowserFamily.KIWI -> 8
        BrowserFamily.YANDEX -> 9
        BrowserFamily.GENERIC -> 10
    }

    private fun buildGuideSteps(appName: String, family: BrowserFamily): List<String> = when (family) {
        BrowserFamily.CHROME, BrowserFamily.BRAVE, BrowserFamily.EDGE, BrowserFamily.KIWI -> listOf(
            "Open $appName -> Bookmarks / Passwords -> Export as HTML or CSV (or Google Takeout Browser History JSON).",
            "Return to Weblo and tap \"Choose Export File(s) & Import\" to select one or multiple files.",
            "Visited sites, bookmarks, top sites, and encrypted passwords are imported in one step."
        )
        BrowserFamily.FIREFOX -> listOf(
            "Open $appName or Firefox Sync -> export Bookmarks (.html / .json) or Logins (.csv).",
            "Return here and tap \"Choose Export File(s) & Import\" to bring all sites, bookmarks, and passwords into Weblo."
        )
        BrowserFamily.SAMSUNG, BrowserFamily.OPERA, BrowserFamily.VIVALDI -> listOf(
            "Open $appName -> Settings / Bookmarks -> Export bookmarks or saved data.",
            "Select the exported HTML, JSON, or CSV file below to import into your active Weblo profile."
        )
        else -> listOf(
            "Export bookmarks (.html / .json), history (.json / .csv), or passwords (.csv) from $appName.",
            "Tap \"Choose Export File(s) & Import\" below — or paste copied website links directly."
        )
    }

    private data class ImportedHistoryItem(val url: String, val title: String, val visitedAt: Long)
    private data class ImportedBookmarkItem(val url: String, val title: String, val folder: String)
    private data class ImportedPasswordItem(val host: String, val username: String, val password: String)

    private class ParsedBrowserData {
        val history = ArrayList<ImportedHistoryItem>()
        val bookmarks = ArrayList<ImportedBookmarkItem>()
        val passwords = ArrayList<ImportedPasswordItem>()
        val quickLinks = ArrayList<Pair<String, String>>()
    }
}
