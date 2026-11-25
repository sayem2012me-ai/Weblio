package com.example.engine

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import com.example.data.WeblioRepository
import com.example.data.db.ExtensionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Real Extension & UserScript Engine:
 * - Unpacks .zip WebExtensions and .crx (CRX2/CRX3 header-stripped) archives, parses manifest.json
 * - Parses Tampermonkey-compatible .user.js scripts (==UserScript== metadata)
 * - Matches URL patterns (@match / content_scripts.matches)
 * - Injects content scripts, CSS, and chrome.runtime / chrome.storage.local / GM_* JS bridge
 * - Supports rendering extension action popup HTML inside a sandboxed WebView
 */
class ExtensionEngine(
    private val context: Context,
    private val repository: WeblioRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storagePrefs by lazy {
        context.getSharedPreferences("weblio_ext_storage", Context.MODE_PRIVATE)
    }
    val sessionBridgeToken: String by lazy {
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }
    }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class StoreExtensionRef(
        val storeName: String,
        val extensionId: String,
        val crxDownloadUrl: String
    )

    sealed class InstallOutcome {
        data class Success(val extension: ExtensionEntity) : InstallOutcome()
        data class Failure(val reason: String) : InstallOutcome()
    }

    suspend fun installFromUri(profileId: Long, uri: Uri, displayName: String?): InstallOutcome {
        return runCatching {
            val maxBytes = 25 * 1024 * 1024L
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    total += read
                    if (total > maxBytes) {
                        return InstallOutcome.Failure("Extension file exceeds 25 MB limit.")
                    }
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            } ?: return InstallOutcome.Failure("Unable to read selected file.")

            val lowerName = (displayName ?: uri.lastPathSegment ?: "").lowercase()
            when {
                lowerName.endsWith(".user.js") || lowerName.endsWith(".js") -> {
                    installUserScript(profileId, String(bytes, Charsets.UTF_8), displayName ?: "custom.user.js")
                }
                lowerName.endsWith(".crx") -> {
                    val zipBytes = stripCrxHeader(bytes)
                        ?: return InstallOutcome.Failure("Invalid or unsupported CRX archive header.")
                    installWebExtensionZip(profileId, ByteArrayInputStream(zipBytes), displayName ?: "extension.crx")
                }
                else -> {
                    installWebExtensionZip(profileId, ByteArrayInputStream(bytes), displayName ?: "extension.zip")
                }
            }
        }.getOrElse { err ->
            InstallOutcome.Failure(err.message ?: "Extension installation failed.")
        }
    }

    suspend fun installCrxFromUrl(
        profileId: Long,
        crxUrl: String,
        displayName: String?
    ): InstallOutcome = withContext(Dispatchers.IO) {
        if (!crxUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext InstallOutcome.Failure("Only HTTPS extension downloads are permitted.")
        }
        return@withContext runCatching {
            val req = Request.Builder()
                .url(crxUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@runCatching InstallOutcome.Failure("Store returned HTTP ${resp.code}")
                }
                val body = resp.body ?: return@runCatching InstallOutcome.Failure("Empty store package response")
                val maxBytes = 25 * 1024 * 1024L
                if (body.contentLength() > maxBytes) {
                    return@runCatching InstallOutcome.Failure("Extension package exceeds 25 MB limit")
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        total += read
                        if (total > maxBytes) {
                            return@runCatching InstallOutcome.Failure("Extension package exceeds 25 MB limit")
                        }
                        out.write(buf, 0, read)
                    }
                }
                val bytes = out.toByteArray()
                val zipBytes = stripCrxHeader(bytes)
                    ?: return@runCatching InstallOutcome.Failure("Invalid or unsupported CRX package returned by store.")
                installWebExtensionZip(
                    profileId = profileId,
                    zipStream = ByteArrayInputStream(zipBytes),
                    sourceName = displayName?.takeIf { it.isNotBlank() } ?: "store_extension.crx"
                )
            }
        }.getOrElse { err ->
            InstallOutcome.Failure(err.message ?: "Unable to download extension package.")
        }
    }

    fun cleanupExtensionFiles(ext: ExtensionEntity) {
        val path = ext.installPath ?: return
        if (path.isBlank()) return
        runCatching {
            val dir = File(path).canonicalFile
            val extRoot = File(context.filesDir, "extensions").canonicalFile
            if (dir.path.startsWith(extRoot.path + File.separator)) {
                dir.deleteRecursively()
            }
        }
    }

    suspend fun installUserScript(
        profileId: Long,
        rawScript: String,
        fallbackName: String
    ): InstallOutcome {
        val meta = parseUserScriptMetadata(rawScript)
        val name = meta["name"]?.firstOrNull() ?: fallbackName.removeSuffix(".user.js")
        val version = meta["version"]?.firstOrNull() ?: "1.0.0"
        val description = meta["description"]?.firstOrNull() ?: "Tampermonkey-compatible UserScript"
        val matches = (meta["match"].orEmpty() + meta["include"].orEmpty()).ifEmpty { listOf("<all_urls>") }
        val runAt = meta["run-at"]?.firstOrNull()?.replace("-", "_") ?: "document_end"
        val grants = meta["grant"]?.ifEmpty { listOf("none") } ?: listOf("none")
        val permissionsJson = JSONArray(grants).toString()
        val matchPatternsJson = JSONArray(matches).toString()
        val assessment = assessExtensionSecurity(permissionsJson, matchPatternsJson, rawScript, "")

        val extId = "userscript." + name.lowercase().replace(Regex("[^a-z0-9]+"), "_") + "." + System.currentTimeMillis()
        val ext = ExtensionEntity(
            profileId = profileId,
            extId = extId,
            name = name,
            version = version,
            description = description,
            sourceType = "USERSCRIPT_JS",
            enabled = assessment.riskLevel == "LOW",
            permissionsJson = permissionsJson,
            matchPatternsJson = matchPatternsJson,
            runAt = runAt,
            scriptContent = rawScript,
            contentHashSha256 = assessment.contentHashSha256,
            riskLevel = assessment.riskLevel,
            permissionWarningsJson = JSONArray(assessment.sensitivePermissions + assessment.scriptWarnings).toString(),
            lastError = if (assessment.riskLevel != "LOW") assessment.summary else null
        )
        val id = repository.upsertExtension(ext)
        return InstallOutcome.Success(ext.copy(id = id))
    }

    private suspend fun installWebExtensionZip(
        profileId: Long,
        zipStream: InputStream,
        sourceName: String
    ): InstallOutcome {
        val extId = "webext." + sourceName.lowercase().replace(Regex("[^a-z0-9]+"), "_") + "." + System.currentTimeMillis()
        val targetDir = File(context.filesDir, "extensions/$profileId/$extId").apply { mkdirs() }

        val maxEntries = 512
        val maxTotalUncompressed = 50L * 1024L * 1024L
        var entryCount = 0
        var totalUncompressed = 0L

        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > maxEntries) {
                    targetDir.deleteRecursively()
                    return InstallOutcome.Failure("Archive exceeds maximum supported file count.")
                }
                if (!entry.isDirectory) {
                    val outFile = File(targetDir, entry.name).canonicalFile
                    // Zip-Slip path traversal guard
                    if (!outFile.path.startsWith(targetDir.canonicalPath + File.separator)) {
                        targetDir.deleteRecursively()
                        return InstallOutcome.Failure("Blocked unsafe archive entry path: ${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val read = zis.read(buf)
                            if (read == -1) break
                            totalUncompressed += read
                            if (totalUncompressed > maxTotalUncompressed) {
                                targetDir.deleteRecursively()
                                return InstallOutcome.Failure("Archive uncompressed content exceeds 50 MB limit.")
                            }
                            out.write(buf, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val manifestFile = File(targetDir, "manifest.json")
        if (!manifestFile.exists()) {
            targetDir.deleteRecursively()
            return InstallOutcome.Failure("Archive does not contain a valid manifest.json.")
        }

        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrElse {
            targetDir.deleteRecursively()
            return InstallOutcome.Failure("Malformed manifest.json: ${it.message}")
        }

        val defaultLocale = manifest.optString("default_locale", "en").ifBlank { "en" }
        val localeMessages = loadLocaleMessages(targetDir, defaultLocale)
        val rawName = manifest.optString("name", sourceName)
        val name = resolveLocalizedMsg(rawName, localeMessages).ifBlank { sourceName }
        val version = manifest.optString("version", "1.0.0")
        val rawDesc = manifest.optString("description", "Unpacked WebExtension bundle")
        val description = resolveLocalizedMsg(rawDesc, localeMessages).ifBlank { "Unpacked WebExtension bundle" }
        val permissions = manifest.optJSONArray("permissions") ?: JSONArray()

        // Extract popup HTML if present (MV2 browser_action or MV3 action) with path confinement
        val actionObj = manifest.optJSONObject("action") ?: manifest.optJSONObject("browser_action")
        val rawPopupRel = actionObj?.optString("default_popup", "").orEmpty()
        val confinedPopup = resolveConfinedFile(targetDir, rawPopupRel)
        val popupRel = if (confinedPopup != null && confinedPopup.exists() && confinedPopup.isFile) {
            rawPopupRel.trimStart('/')
        } else {
            ""
        }
        val hasPopup = popupRel.isNotBlank()

        // Gather content_scripts JS & CSS with strict path confinement
        val contentScripts = manifest.optJSONArray("content_scripts")
        val allMatches = mutableListOf<String>()
        val jsBuilder = StringBuilder()
        val cssBuilder = StringBuilder()
        var runAt = "document_end"

        if (contentScripts != null) {
            for (i in 0 until contentScripts.length()) {
                val cs = contentScripts.optJSONObject(i) ?: continue
                runAt = cs.optString("run_at", runAt)
                val matchesArr = cs.optJSONArray("matches")
                if (matchesArr != null) {
                    for (m in 0 until matchesArr.length()) allMatches.add(matchesArr.optString(m))
                }
                val jsArr = cs.optJSONArray("js")
                if (jsArr != null) {
                    for (j in 0 until jsArr.length()) {
                        val jsFile = resolveConfinedFile(targetDir, jsArr.optString(j)) ?: continue
                        if (jsFile.exists() && jsFile.isFile) {
                            jsBuilder.append("\n/* ").append(jsFile.name).append(" */\n")
                            jsBuilder.append(jsFile.readText())
                        }
                    }
                }
                val cssArr = cs.optJSONArray("css")
                if (cssArr != null) {
                    for (c in 0 until cssArr.length()) {
                        val cssFile = resolveConfinedFile(targetDir, cssArr.optString(c)) ?: continue
                        if (cssFile.exists() && cssFile.isFile) {
                            cssBuilder.append("\n").append(cssFile.readText())
                        }
                    }
                }
            }
        }

        val warnings = mutableListOf<String>()
        if (manifest.has("declarative_net_request")) {
            warnings.add("Manifest uses declarative_net_request; network filtering is handled by Weblo AdBlockEngine.")
        }
        if (manifest.optJSONObject("background")?.has("service_worker") == true) {
            warnings.add("MV3 background service_worker detected; content scripts and popup run natively while background worker APIs are shimmed.")
        }

        val permissionsJson = permissions.toString()
        val matchPatternsJson = JSONArray(allMatches.ifEmpty { listOf("<all_urls>") }).toString()
        val scriptStr = jsBuilder.toString()
        val cssStr = cssBuilder.toString()
        val assessment = assessExtensionSecurity(permissionsJson, matchPatternsJson, scriptStr, cssStr)
        if (assessment.riskLevel != "LOW") {
            warnings.add(assessment.summary)
        }

        val entity = ExtensionEntity(
            profileId = profileId,
            extId = extId,
            name = name,
            version = version,
            description = description,
            sourceType = "WEBEXT_ZIP",
            enabled = assessment.riskLevel == "LOW",
            permissionsJson = permissionsJson,
            matchPatternsJson = matchPatternsJson,
            runAt = runAt,
            installPath = targetDir.absolutePath,
            hasPopup = hasPopup,
            popupPath = popupRel,
            scriptContent = scriptStr,
            cssContent = cssStr,
            lastError = warnings.joinToString(" ").ifBlank { null },
            contentHashSha256 = assessment.contentHashSha256,
            riskLevel = assessment.riskLevel,
            permissionWarningsJson = JSONArray(assessment.sensitivePermissions + assessment.scriptWarnings).toString()
        )
        val id = repository.upsertExtension(entity)
        return InstallOutcome.Success(entity.copy(id = id))
    }

    /**
     * Generates combined JavaScript to inject into WebView for all enabled extensions matching `url` and `stage`.
     * Refuses to inject any extension whose stored SHA-256 content hash fails verification.
     */
    fun buildInjectionPayload(
        extensions: List<ExtensionEntity>,
        url: String,
        stage: String, // "document_start" or "document_end"
        onIntegrityViolation: ((ExtensionEntity) -> Unit)? = null
    ): String {
        val matching = extensions.filter { ext ->
            if (!ext.enabled) return@filter false
            if (!verifyExtensionIntegrity(ext)) {
                onIntegrityViolation?.invoke(ext)
                return@filter false
            }
            matchesAnyPattern(url, ext.matchPatternsJson) &&
                (ext.runAt == stage || (stage == "document_end" && ext.runAt == "document_idle"))
        }
        if (matching.isEmpty()) return ""

        val quotedToken = JSONObject.quote(sessionBridgeToken)
        val sb = StringBuilder()
        sb.append(buildRuntimeShimJs())
        for (ext in matching) {
            if (ext.cssContent.isNotBlank() && stage == "document_end") {
                val escapedCss = JSONObject.quote(ext.cssContent)
                sb.append(
                    """
                    ;(function(){
                        var __TOKEN__ = $quotedToken;
                        try {
                            var s = document.createElement('style');
                            s.setAttribute('data-weblio-ext', ${JSONObject.quote(ext.extId)});
                            s.textContent = $escapedCss;
                            (document.head || document.documentElement).appendChild(s);
                        } catch(e) {
                            if (window.WeblioExtBridge) window.WeblioExtBridge.reportError(__TOKEN__, ${JSONObject.quote(ext.extId)}, String(e));
                        }
                    })();
                    """.trimIndent()
                )
            }
            if (ext.scriptContent.isNotBlank()) {
                sb.append(
                    """
                    ;(function(){
                        var __EXT_ID__ = ${JSONObject.quote(ext.extId)};
                        var __TOKEN__ = $quotedToken;
                        var chrome = window.__weblioCreateExtChrome ? window.__weblioCreateExtChrome(__EXT_ID__, ${JSONObject.quote(ext.permissionsJson)}, __TOKEN__) : {};
                        var browser = chrome;
                        var GM_getValue = function(k, defVal) {
                            try {
                                var raw = window.WeblioExtBridge ? window.WeblioExtBridge.getValue(__TOKEN__, __EXT_ID__, String(k)) : null;
                                return raw !== null && raw !== undefined ? JSON.parse(raw) : defVal;
                            } catch(e) { return defVal; }
                        };
                        var GM_setValue = function(k, v) {
                            try {
                                if (window.WeblioExtBridge) window.WeblioExtBridge.setValue(__TOKEN__, __EXT_ID__, String(k), JSON.stringify(v));
                            } catch(e) {}
                        };
                        var GM_addStyle = function(css) {
                            var s = document.createElement('style');
                            s.textContent = css;
                            (document.head || document.documentElement).appendChild(s);
                        };
                        try {
                            ${ext.scriptContent}
                        } catch(err) {
                            if (window.WeblioExtBridge) window.WeblioExtBridge.reportError(__TOKEN__, __EXT_ID__, String(err));
                        }
                    })();
                    """.trimIndent()
                )
            }
        }
        return sb.toString()
    }

    /**
     * Builds an immediate execution payload for a user-triggered extension run on the active tab.
     */
    fun buildForceExecutionPayload(ext: ExtensionEntity): String {
        if (!ext.enabled || !verifyExtensionIntegrity(ext)) return ""
        if (ext.scriptContent.isBlank() && ext.cssContent.isBlank()) return ""
        val quotedToken = JSONObject.quote(sessionBridgeToken)
        val sb = StringBuilder()
        sb.append(buildRuntimeShimJs())
        if (ext.cssContent.isNotBlank()) {
            val escapedCss = JSONObject.quote(ext.cssContent)
            sb.append(
                """
                ;(function(){
                    try {
                        var s = document.createElement('style');
                        s.setAttribute('data-weblio-ext', ${JSONObject.quote(ext.extId)});
                        s.textContent = $escapedCss;
                        (document.head || document.documentElement).appendChild(s);
                    } catch(e) {}
                })();
                """.trimIndent()
            )
        }
        if (ext.scriptContent.isNotBlank()) {
            sb.append(
                """
                ;(function(){
                    var __EXT_ID__ = ${JSONObject.quote(ext.extId)};
                    var __TOKEN__ = $quotedToken;
                    var chrome = window.__weblioCreateExtChrome ? window.__weblioCreateExtChrome(__EXT_ID__, ${JSONObject.quote(ext.permissionsJson)}, __TOKEN__) : {};
                    var browser = chrome;
                    var GM_getValue = function(k, defVal) {
                        try {
                            var raw = window.WeblioExtBridge ? window.WeblioExtBridge.getValue(__TOKEN__, __EXT_ID__, String(k)) : null;
                            return raw !== null && raw !== undefined ? JSON.parse(raw) : defVal;
                        } catch(e) { return defVal; }
                    };
                    var GM_setValue = function(k, v) {
                        try {
                            if (window.WeblioExtBridge) window.WeblioExtBridge.setValue(__TOKEN__, __EXT_ID__, String(k), JSON.stringify(v));
                        } catch(e) {}
                    };
                    var GM_addStyle = function(css) {
                        var s = document.createElement('style');
                        s.textContent = css;
                        (document.head || document.documentElement).appendChild(s);
                    };
                    try {
                        ${ext.scriptContent}
                    } catch(err) {
                        if (window.WeblioExtBridge) window.WeblioExtBridge.reportError(__TOKEN__, __EXT_ID__, String(err));
                    }
                })();
                """.trimIndent()
            )
        }
        return sb.toString()
    }

    /**
     * Injects a synchronous chrome/browser WebExtension API bootstrap inside extension popup HTML
     * before any popup scripts execute.
     */
    fun injectPopupChromeRuntimeHtml(
        rawHtmlBytes: ByteArray,
        ext: ExtensionEntity,
        activeTabUrl: String,
        activeTabTitle: String
    ): ByteArray {
        val html = String(rawHtmlBytes, Charsets.UTF_8)
        val manifestJsonStr = runCatching {
            if (ext.installPath.isNotBlank()) {
                val root = File(ext.installPath)
                val mf = resolveConfinedFile(root, "manifest.json")
                if (mf != null && mf.exists()) mf.readText() else "{}"
            } else "{}"
        }.getOrDefault("{}")
        val localeMessages = runCatching {
            if (ext.installPath.isNotBlank()) {
                val mfObj = JSONObject(manifestJsonStr)
                val defLoc = mfObj.optString("default_locale", "en").ifBlank { "en" }
                loadLocaleMessages(File(ext.installPath), defLoc)
            } else emptyMap()
        }.getOrDefault(emptyMap())

        val messagesJson = JSONObject().apply {
            localeMessages.forEach { (k, v) -> put(k, v) }
        }.toString()

        val bootstrapScript = """
            <script>
            (function(){
                if (window.__weblioPopupChromeReady) return;
                window.__weblioPopupChromeReady = true;
                var extId = ${JSONObject.quote(ext.extId)};
                var manifestObj = {};
                try { manifestObj = JSON.parse(${JSONObject.quote(manifestJsonStr)}); } catch(e) {}
                var i18nMessages = {};
                try { i18nMessages = JSON.parse(${JSONObject.quote(messagesJson)}); } catch(e) {}
                var activeTab = {
                    id: 1,
                    index: 0,
                    windowId: 1,
                    active: true,
                    highlighted: true,
                    currentWindow: true,
                    url: ${JSONObject.quote(activeTabUrl)},
                    title: ${JSONObject.quote(activeTabTitle)},
                    status: 'complete'
                };
                var makeStorageArea = function() {
                    return {
                        get: function(keys, cb) {
                            var res = {};
                            try {
                                if (keys === null || keys === undefined) {
                                    var allRaw = window.WeblioPopupBridge ? window.WeblioPopupBridge.getAllValues(extId) : '{}';
                                    res = JSON.parse(allRaw || '{}');
                                } else {
                                    var defaults = (!Array.isArray(keys) && typeof keys === 'object') ? keys : {};
                                    var arr = Array.isArray(keys) ? keys : (typeof keys === 'string' ? [keys] : Object.keys(defaults));
                                    arr.forEach(function(k) {
                                        var raw = window.WeblioPopupBridge ? window.WeblioPopupBridge.getValue(extId, String(k)) : null;
                                        if (raw !== null && raw !== undefined) {
                                            res[k] = JSON.parse(raw);
                                        } else if (Object.prototype.hasOwnProperty.call(defaults, k)) {
                                            res[k] = defaults[k];
                                        }
                                    });
                                }
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb(res); }, 0);
                            return Promise.resolve(res);
                        },
                        set: function(items, cb) {
                            try {
                                Object.keys(items || {}).forEach(function(k) {
                                    if (window.WeblioPopupBridge) window.WeblioPopupBridge.setValue(extId, String(k), JSON.stringify(items[k]));
                                });
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb(); }, 0);
                            return Promise.resolve();
                        },
                        remove: function(keys, cb) {
                            try {
                                var arr = Array.isArray(keys) ? keys : [keys];
                                arr.forEach(function(k) {
                                    if (window.WeblioPopupBridge) window.WeblioPopupBridge.removeValue(extId, String(k));
                                });
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb(); }, 0);
                            return Promise.resolve();
                        },
                        clear: function(cb) {
                            try {
                                if (window.WeblioPopupBridge) window.WeblioPopupBridge.clearValues(extId);
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb(); }, 0);
                            return Promise.resolve();
                        }
                    };
                };
                var storageArea = makeStorageArea();
                var chromeObj = {
                    runtime: {
                        id: extId,
                        lastError: null,
                        getManifest: function() { return manifestObj; },
                        getURL: function(p) {
                            return 'https://ext.weblio.local/' + String(p || '').replace(/^\/+/, '');
                        },
                        sendMessage: function(msg, cb) {
                            var resp = { ok: true };
                            if (typeof msg === 'function') { cb = msg; }
                            if (cb) setTimeout(function(){ cb(resp); }, 0);
                            return Promise.resolve(resp);
                        },
                        onMessage: { addListener: function(){}, removeListener: function(){} },
                        openOptionsPage: function(cb) { if (cb) cb(); return Promise.resolve(); }
                    },
                    storage: {
                        local: storageArea,
                        sync: storageArea,
                        session: storageArea,
                        onChanged: { addListener: function(){}, removeListener: function(){} }
                    },
                    tabs: {
                        query: function(queryInfo, cb) {
                            var list = [activeTab];
                            if (cb) setTimeout(function(){ cb(list); }, 0);
                            return Promise.resolve(list);
                        },
                        getCurrent: function(cb) {
                            if (cb) setTimeout(function(){ cb(activeTab); }, 0);
                            return Promise.resolve(activeTab);
                        },
                        create: function(props, cb) {
                            if (props && props.url && window.WeblioPopupBridge) {
                                window.WeblioPopupBridge.openTab(String(props.url));
                            }
                            if (cb) setTimeout(function(){ cb(activeTab); }, 0);
                            return Promise.resolve(activeTab);
                        },
                        update: function(tabId, props, cb) {
                            if (typeof tabId === 'object') { cb = props; props = tabId; }
                            if (props && props.url && window.WeblioPopupBridge) {
                                window.WeblioPopupBridge.openTab(String(props.url));
                            }
                            if (cb) setTimeout(function(){ cb(activeTab); }, 0);
                            return Promise.resolve(activeTab);
                        },
                        reload: function(tabId, cb) {
                            if (window.WeblioPopupBridge) window.WeblioPopupBridge.reloadActiveTab();
                            if (typeof tabId === 'function') cb = tabId;
                            if (cb) setTimeout(function(){ cb(); }, 0);
                            return Promise.resolve();
                        },
                        sendMessage: function(tabId, msg, cb) {
                            try {
                                if (window.WeblioPopupBridge) window.WeblioPopupBridge.sendTabMessage(JSON.stringify(msg || {}));
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb({ ok: true }); }, 0);
                            return Promise.resolve({ ok: true });
                        },
                        executeScript: function(tabId, details, cb) {
                            if (typeof tabId === 'object') { cb = details; details = tabId; }
                            try {
                                if (details && window.WeblioPopupBridge) {
                                    if (details.code) window.WeblioPopupBridge.executeActiveTabCode(String(details.code));
                                    else if (details.file) window.WeblioPopupBridge.executeExtensionFile(String(details.file));
                                }
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb([]); }, 0);
                            return Promise.resolve([]);
                        }
                    },
                    scripting: {
                        executeScript: function(injection, cb) {
                            try {
                                if (injection && window.WeblioPopupBridge) {
                                    if (typeof injection.func === 'function') {
                                        var argsJson = JSON.stringify(injection.args || []);
                                        var code = '(' + injection.func.toString() + ').apply(null, ' + argsJson + ');';
                                        window.WeblioPopupBridge.executeActiveTabCode(code);
                                    }
                                    if (Array.isArray(injection.files)) {
                                        injection.files.forEach(function(f) {
                                            window.WeblioPopupBridge.executeExtensionFile(String(f));
                                        });
                                    }
                                }
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb([]); }, 0);
                            return Promise.resolve([]);
                        },
                        insertCSS: function(injection, cb) {
                            try {
                                if (injection && window.WeblioPopupBridge) {
                                    if (injection.css) window.WeblioPopupBridge.insertActiveTabCss(String(injection.css));
                                    if (Array.isArray(injection.files)) {
                                        injection.files.forEach(function(f) {
                                            window.WeblioPopupBridge.insertExtensionCssFile(String(f));
                                        });
                                    }
                                }
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb(); }, 0);
                            return Promise.resolve();
                        }
                    },
                    cookies: {
                        getAll: function(details, cb) {
                            var list = [];
                            try {
                                var raw = window.WeblioPopupBridge ? window.WeblioPopupBridge.getCookiesJson(JSON.stringify(details || {})) : '[]';
                                list = JSON.parse(raw || '[]');
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb(list); }, 0);
                            return Promise.resolve(list);
                        },
                        get: function(details, cb) {
                            var found = null;
                            try {
                                var raw = window.WeblioPopupBridge ? window.WeblioPopupBridge.getCookiesJson(JSON.stringify(details || {})) : '[]';
                                var arr = JSON.parse(raw || '[]');
                                found = arr.find(function(c){ return !details || !details.name || c.name === details.name; }) || null;
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb(found); }, 0);
                            return Promise.resolve(found);
                        },
                        set: function(details, cb) {
                            try {
                                if (window.WeblioPopupBridge) window.WeblioPopupBridge.setCookieJson(JSON.stringify(details || {}));
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb(details || {}); }, 0);
                            return Promise.resolve(details || {});
                        },
                        remove: function(details, cb) {
                            try {
                                if (window.WeblioPopupBridge) window.WeblioPopupBridge.removeCookieJson(JSON.stringify(details || {}));
                            } catch(e) {}
                            if (cb) setTimeout(function(){ cb(details || {}); }, 0);
                            return Promise.resolve(details || {});
                        }
                    },
                    i18n: {
                        getMessage: function(key, subs) {
                            var msg = i18nMessages[key];
                            if (!msg) return key || '';
                            if (Array.isArray(subs)) {
                                subs.forEach(function(s, idx) {
                                    msg = msg.replace(new RegExp('\\$' + (idx + 1), 'g'), String(s));
                                });
                            }
                            return msg;
                        },
                        getUILanguage: function() { return navigator.language || 'en-US'; }
                    },
                    action: {
                        setBadgeText: function(d, cb){ if (cb) cb(); return Promise.resolve(); },
                        setBadgeBackgroundColor: function(d, cb){ if (cb) cb(); return Promise.resolve(); },
                        setIcon: function(d, cb){ if (cb) cb(); return Promise.resolve(); }
                    }
                };
                chromeObj.browserAction = chromeObj.action;
                window.chrome = Object.assign(window.chrome || {}, chromeObj);
                window.browser = window.chrome;
            })();
            </script>
        """.trimIndent()

        val headIdx = html.indexOf("<head>", ignoreCase = true)
        val htmlIdx = html.indexOf("<html>", ignoreCase = true)
        val injectedHtml = when {
            headIdx >= 0 -> {
                val insertAt = headIdx + "<head>".length
                html.substring(0, insertAt) + "\n" + bootstrapScript + html.substring(insertAt)
            }
            htmlIdx >= 0 -> {
                val insertAt = htmlIdx + "<html>".length
                html.substring(0, insertAt) + "<head>" + bootstrapScript + "</head>" + html.substring(insertAt)
            }
            else -> "$bootstrapScript\n$html"
        }
        return injectedHtml.toByteArray(Charsets.UTF_8)
    }

    fun createPopupJsBridge(
        ext: ExtensionEntity,
        activeTabUrl: String,
        onExecuteScript: (String) -> Unit,
        onInsertCss: (String) -> Unit,
        onOpenTab: (String) -> Unit,
        onReloadTab: () -> Unit,
        onSendTabMessage: (String) -> Unit
    ): Any {
        val hasCookiesPermission = extensionHasPermission(ext.permissionsJson, "cookies")
        return object {
            @JavascriptInterface
            fun getValue(extId: String, key: String): String? {
                if (extId != ext.extId || extId.isBlank() || extId == "shared") return null
                return storagePrefs.getString("$extId::$key", null)
            }

            @JavascriptInterface
            fun getAllValues(extId: String): String {
                if (extId != ext.extId || extId.isBlank() || extId == "shared") return "{}"
                val prefix = "$extId::"
                val obj = JSONObject()
                storagePrefs.all.forEach { (k, v) ->
                    if (k.startsWith(prefix) && v is String) {
                        val cleanKey = k.removePrefix(prefix)
                        runCatching { obj.put(cleanKey, org.json.JSONTokener(v).nextValue()) }
                    }
                }
                return obj.toString()
            }

            @JavascriptInterface
            fun setValue(extId: String, key: String, jsonValue: String) {
                if (extId != ext.extId || extId.isBlank() || extId == "shared") return
                storagePrefs.edit().putString("$extId::$key", jsonValue).apply()
            }

            @JavascriptInterface
            fun removeValue(extId: String, key: String) {
                if (extId != ext.extId || extId.isBlank() || extId == "shared") return
                storagePrefs.edit().remove("$extId::$key").apply()
            }

            @JavascriptInterface
            fun clearValues(extId: String) {
                if (extId != ext.extId || extId.isBlank() || extId == "shared") return
                val prefix = "$extId::"
                val editor = storagePrefs.edit()
                storagePrefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
                editor.apply()
            }

            @JavascriptInterface
            fun openTab(url: String) {
                if (url.startsWith("https://", true) || url.startsWith("http://", true)) {
                    onOpenTab(url)
                }
            }

            @JavascriptInterface
            fun reloadActiveTab() {
                if (ext.enabled) onReloadTab()
            }

            @JavascriptInterface
            fun sendTabMessage(msgJson: String) {
                if (ext.enabled && verifyExtensionIntegrity(ext)) {
                    onSendTabMessage(msgJson)
                }
            }

            @JavascriptInterface
            fun executeActiveTabCode(code: String) {
                if (ext.enabled && verifyExtensionIntegrity(ext) && code.isNotBlank()) {
                    onExecuteScript(code)
                }
            }

            @JavascriptInterface
            fun executeExtensionFile(relativePath: String) {
                if (!ext.enabled || !verifyExtensionIntegrity(ext)) return
                val text = readSafeExtensionAsset(ext.installPath, relativePath) ?: return
                onExecuteScript(text)
            }

            @JavascriptInterface
            fun insertActiveTabCss(css: String) {
                if (ext.enabled && verifyExtensionIntegrity(ext) && css.isNotBlank()) {
                    onInsertCss(css)
                }
            }

            @JavascriptInterface
            fun insertExtensionCssFile(relativePath: String) {
                if (!ext.enabled || !verifyExtensionIntegrity(ext)) return
                val text = readSafeExtensionAsset(ext.installPath, relativePath) ?: return
                onInsertCss(text)
            }

            @JavascriptInterface
            fun getCookiesJson(detailsJson: String): String {
                if (!ext.enabled || !hasCookiesPermission || !verifyExtensionIntegrity(ext)) return "[]"
                val details = runCatching { JSONObject(detailsJson) }.getOrNull() ?: JSONObject()
                val targetUrl = details.optString("url", "").ifBlank {
                    val domain = details.optString("domain", "").trimStart('.')
                    if (domain.isNotBlank()) "https://$domain/" else activeTabUrl
                }
                if (!targetUrl.startsWith("http://", true) && !targetUrl.startsWith("https://", true)) {
                    return "[]"
                }
                val parsed = runCatching { Uri.parse(targetUrl) }.getOrNull() ?: return "[]"
                val host = parsed.host.orEmpty()
                val filterName = details.optString("name", "").takeIf { it.isNotBlank() }
                val rawCookie = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull().orEmpty()
                if (rawCookie.isBlank()) return "[]"
                val arr = JSONArray()
                rawCookie.split(";").forEach { pair ->
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        val cName = pair.substring(0, idx).trim()
                        val cVal = pair.substring(idx + 1).trim()
                        if (filterName == null || cName == filterName) {
                            arr.put(
                                JSONObject().apply {
                                    put("name", cName)
                                    put("value", cVal)
                                    put("domain", host)
                                    put("path", "/")
                                    put("secure", targetUrl.startsWith("https://", true))
                                    put("httpOnly", false)
                                }
                            )
                        }
                    }
                }
                return arr.toString()
            }

            @JavascriptInterface
            fun setCookieJson(detailsJson: String) {
                if (!ext.enabled || !hasCookiesPermission || !verifyExtensionIntegrity(ext)) return
                val details = runCatching { JSONObject(detailsJson) }.getOrNull() ?: return
                val targetUrl = details.optString("url", "").ifBlank { activeTabUrl }
                if (!targetUrl.startsWith("http://", true) && !targetUrl.startsWith("https://", true)) return
                val name = details.optString("name", "").trim()
                    .replace(Regex("[;\\r\\n=\\s]"), "")
                val value = details.optString("value", "")
                    .replace(Regex("[;\\r\\n]"), "")
                if (name.isBlank()) return
                val rawPath = details.optString("path", "/").ifBlank { "/" }
                    .replace(Regex("[;\\r\\n]"), "")
                val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
                val cookieStr = "$name=$value; Path=$path" + if (details.optBoolean("secure", false)) "; Secure" else ""
                runCatching { CookieManager.getInstance().setCookie(targetUrl, cookieStr) }
            }

            @JavascriptInterface
            fun removeCookieJson(detailsJson: String) {
                if (!ext.enabled || !hasCookiesPermission || !verifyExtensionIntegrity(ext)) return
                val details = runCatching { JSONObject(detailsJson) }.getOrNull() ?: return
                val targetUrl = details.optString("url", "").ifBlank { activeTabUrl }
                if (!targetUrl.startsWith("http://", true) && !targetUrl.startsWith("https://", true)) return
                val name = details.optString("name", "").trim()
                    .replace(Regex("[;\\r\\n=\\s]"), "")
                if (name.isBlank()) return
                runCatching { CookieManager.getInstance().setCookie(targetUrl, "$name=; Max-Age=0; Path=/") }
            }
        }
    }

    private fun buildRuntimeShimJs(): String = """
        ;(function() {
            if (window.__weblioExtShimLoaded) return;
            window.__weblioExtShimLoaded = true;
            window.__weblioExtMsgListeners = window.__weblioExtMsgListeners || {};
            window.__weblioDispatchExtMessage = function(extId, msg) {
                var list = window.__weblioExtMsgListeners[extId] || [];
                list.forEach(function(fn) {
                    try { fn(msg, { id: extId }, function(){}); } catch(e) {}
                });
            };
            window.__weblioCreateExtChrome = function(extId, permsJson, bridgeToken) {
                window.__weblioExtMsgListeners[extId] = window.__weblioExtMsgListeners[extId] || [];
                var storageApi = {
                    get: function(keys, cb) {
                        var res = {};
                        try {
                            var arr = Array.isArray(keys) ? keys : (typeof keys === 'string' ? [keys] : Object.keys(keys || {}));
                            arr.forEach(function(k) {
                                var v = (window.WeblioExtBridge && bridgeToken) ? window.WeblioExtBridge.getValue(bridgeToken, extId, String(k)) : null;
                                if (v !== null && v !== undefined) res[k] = JSON.parse(v);
                            });
                        } catch(e) {}
                        if (cb) cb(res);
                        return Promise.resolve(res);
                    },
                    set: function(items, cb) {
                        try {
                            Object.keys(items || {}).forEach(function(k) {
                                if (window.WeblioExtBridge && bridgeToken) window.WeblioExtBridge.setValue(bridgeToken, extId, String(k), JSON.stringify(items[k]));
                            });
                        } catch(e) {}
                        if (cb) cb();
                        return Promise.resolve();
                    }
                };
                return {
                    runtime: {
                        id: extId,
                        sendMessage: function(msg, cb) { if (cb) cb({ ok: true }); return Promise.resolve({ ok: true }); },
                        onMessage: {
                            addListener: function(fn) {
                                if (typeof fn === 'function') window.__weblioExtMsgListeners[extId].push(fn);
                            },
                            removeListener: function(fn) {
                                window.__weblioExtMsgListeners[extId] = (window.__weblioExtMsgListeners[extId] || []).filter(function(x){ return x !== fn; });
                            }
                        }
                    },
                    storage: { local: storageApi, sync: storageApi }
                };
            };
        })();
    """.trimIndent()

    inner class ExtensionJsBridge(
        private val expectedToken: String,
        private val onError: (String, String) -> Unit
    ) {
        @JavascriptInterface
        fun getValue(token: String, extId: String, key: String): String? {
            if (token != expectedToken || extId.isBlank() || extId == "shared") return null
            return storagePrefs.getString("$extId::$key", null)
        }

        @JavascriptInterface
        fun setValue(token: String, extId: String, key: String, jsonValue: String) {
            if (token != expectedToken || extId.isBlank() || extId == "shared") return
            storagePrefs.edit().putString("$extId::$key", jsonValue).apply()
        }

        @JavascriptInterface
        fun reportError(token: String, extId: String, errorMsg: String) {
            if (token != expectedToken || extId.isBlank()) return
            scope.launch { onError(extId.take(80), errorMsg.take(240)) }
        }
    }

    fun createJsBridge(onError: (String, String) -> Unit): ExtensionJsBridge {
        return ExtensionJsBridge(sessionBridgeToken, onError)
    }

    private fun readSafeExtensionAsset(installPath: String, relativePath: String): String? {
        if (installPath.isBlank() || relativePath.isBlank()) return null
        return runCatching {
            val target = resolveConfinedFile(File(installPath), relativePath) ?: return null
            if (!target.exists() || !target.isFile) null else target.readText()
        }.getOrNull()
    }

    private fun loadLocaleMessages(installRoot: File, preferredLocale: String): Map<String, String> {
        val safeLocale = preferredLocale.replace(Regex("[^A-Za-z0-9_-]"), "")
        val candidates = listOf(safeLocale, "en", "en_US").filter { it.isNotBlank() }
        for (loc in candidates) {
            val file = resolveConfinedFile(installRoot, "_locales/$loc/messages.json") ?: continue
            if (file.exists() && file.isFile) {
                val parsed = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
                val map = mutableMapOf<String, String>()
                parsed.keys().forEach { key ->
                    val entry = parsed.optJSONObject(key)
                    val msg = entry?.optString("message").orEmpty()
                    if (msg.isNotBlank()) map[key] = msg
                }
                if (map.isNotEmpty()) return map
            }
        }
        return emptyMap()
    }

    private fun resolveLocalizedMsg(raw: String, messages: Map<String, String>): String {
        val match = Regex("^__MSG_([A-Za-z0-9_@]+)__$").find(raw.trim()) ?: return raw
        val key = match.groupValues[1]
        return messages[key] ?: messages.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: ""
    }

    private fun extensionHasPermission(permissionsJson: String, permission: String): Boolean {
        val arr = runCatching { JSONArray(permissionsJson) }.getOrNull() ?: return false
        for (i in 0 until arr.length()) {
            if (arr.optString(i).equals(permission, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        private val SENSITIVE_PERMISSIONS = setOf(
            "passwords", "cookies", "downloads", "clipboardread", "clipboardwrite",
            "camera", "microphone", "geolocation", "webrequest", "webrequestblocking",
            "history", "nativemessaging", "debugger", "<all_urls>"
        )

        fun computeExtensionHash(scriptContent: String, cssContent: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(scriptContent.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(cssContent.toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun verifyExtensionIntegrity(ext: ExtensionEntity): Boolean {
            if (ext.contentHashSha256.isBlank()) return false
            return computeExtensionHash(ext.scriptContent, ext.cssContent)
                .equals(ext.contentHashSha256, ignoreCase = true)
        }

        fun assessExtensionSecurity(
            permissionsJson: String,
            matchPatternsJson: String,
            scriptContent: String,
            cssContent: String
        ): com.example.security.ExtensionSecurityAssessment {
            val sensitive = mutableListOf<String>()
            val perms = runCatching { JSONArray(permissionsJson) }.getOrNull()
            if (perms != null) {
                for (i in 0 until perms.length()) {
                    val p = perms.optString(i)
                    if (p.lowercase() in SENSITIVE_PERMISSIONS || p.contains("://*/") || p == "<all_urls>") {
                        sensitive.add("Sensitive permission: $p")
                    }
                }
            }
            val matches = runCatching { JSONArray(matchPatternsJson) }.getOrNull()
            if (matches != null) {
                for (i in 0 until matches.length()) {
                    val m = matches.optString(i)
                    if (m == "<all_urls>" || m == "*" || m == "*://*/*") {
                        sensitive.add("Broad access to all websites ($m)")
                        break
                    }
                }
            }

            val scriptWarnings = mutableListOf<String>()
            val lower = scriptContent.lowercase()
            if (lower.contains("document.cookie")) scriptWarnings.add("Reads browser document.cookie")
            if (lower.contains("input[type=\"password\"]") || lower.contains("input[type='password']")) {
                scriptWarnings.add("Inspects password form fields")
            }
            if (Regex("eval\\s*\\(|new\\s+function", RegexOption.IGNORE_CASE).containsMatchIn(scriptContent)) {
                scriptWarnings.add("Uses dynamic code evaluation (eval/Function)")
            }
            if ((lower.contains("fetch(") || lower.contains("xmlhttprequest")) &&
                (lower.contains("cookie") || lower.contains("password"))
            ) {
                scriptWarnings.add("Potential credential/cookie exfiltration sink detected")
            }

            val riskLevel = when {
                scriptWarnings.any { it.contains("exfiltration", true) || it.contains("dynamic code", true) } -> "HIGH"
                sensitive.any { it.contains("cookies") || it.contains("passwords") || it.contains("debugger") || it.contains("nativemessaging") } -> "HIGH"
                sensitive.isNotEmpty() || scriptWarnings.isNotEmpty() -> "SENSITIVE"
                else -> "LOW"
            }

            val summary = when (riskLevel) {
                "HIGH" -> "High-risk permissions or suspicious script behavior detected. Review before enabling."
                "SENSITIVE" -> "Extension requests broad site or sensitive data access."
                else -> "Standard content script permissions."
            }

            return com.example.security.ExtensionSecurityAssessment(
                riskLevel = riskLevel,
                sensitivePermissions = sensitive.distinct(),
                scriptWarnings = scriptWarnings.distinct(),
                contentHashSha256 = computeExtensionHash(scriptContent, cssContent),
                summary = summary
            )
        }

        fun parseUserScriptMetadata(script: String): Map<String, List<String>> {
            val result = mutableMapOf<String, MutableList<String>>()
            var inHeader = false
            for (line in script.lineSequence()) {
                val t = line.trim()
                if (t.contains("==UserScript==")) {
                    inHeader = true
                    continue
                }
                if (t.contains("==/UserScript==")) break
                if (!inHeader) continue
                val match = Regex("^//\\s*@([A-Za-z0-9_-]+)\\s+(.+)$").find(t) ?: continue
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2].trim()
                result.getOrPut(key) { mutableListOf() }.add(value)
            }
            return result
        }

        fun matchesAnyPattern(url: String, patternsJson: String): Boolean {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            if (url.startsWith("https://${WeblioDownloadManager.LOCAL_PREVIEW_HOST}/", ignoreCase = true)) return false
            val arr = runCatching { JSONArray(patternsJson) }.getOrNull() ?: return true
            if (arr.length() == 0) return true
            for (i in 0 until arr.length()) {
                val p = arr.optString(i).trim()
                if (p == "<all_urls>" || p == "*" || p == "*://*/*") return true
                val normalized = p.replace(Regex("\\*{2,}"), "*")
                if (normalized.count { it == '*' } > 12) continue
                val regexStr = "^" + normalized.split('*').joinToString(".*") { Regex.escape(it) } + "$"
                if (runCatching { Regex(regexStr, RegexOption.IGNORE_CASE).matches(url) }.getOrDefault(false)) {
                    return true
                }
            }
            return false
        }

        /**
         * Strips CRX2 or CRX3 header magic ("Cr24") to obtain the embedded ZIP archive bytes.
         */
        fun stripCrxHeader(bytes: ByteArray): ByteArray? {
            if (bytes.size < 16) return null
            if (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return bytes
            val magic = String(bytes, 0, 4, Charsets.US_ASCII)
            if (magic != "Cr24") return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.getInt(4)
            return when (version) {
                2 -> {
                    val pubLen = buf.getInt(8)
                    val sigLen = buf.getInt(12)
                    val offset = 16 + pubLen + sigLen
                    if (offset in 16 until bytes.size) bytes.copyOfRange(offset, bytes.size) else null
                }
                3 -> {
                    val headerSize = buf.getInt(8)
                    val offset = 12 + headerSize
                    if (offset in 12 until bytes.size) bytes.copyOfRange(offset, bytes.size) else null
                }
                else -> null
            }
        }

        fun isExtensionStoreUrl(url: String): Boolean {
            val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            val host = parsed.host?.lowercase() ?: return false
            val path = parsed.path?.lowercase().orEmpty()
            return host == "chromewebstore.google.com" ||
                (host == "chrome.google.com" && path.contains("/webstore")) ||
                host == "microsoftedge.microsoft.com" ||
                host == "clients2.google.com" ||
                host == "edge.microsoft.com" ||
                (host == "support.google.com" && path.contains("chrome_webstore")) ||
                host.endsWith("crx4chrome.com") ||
                path.endsWith(".crx")
        }

        fun extractStoreExtensionDownload(url: String): StoreExtensionRef? {
            val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            val host = parsed.host?.lowercase() ?: return null
            val path = parsed.path.orEmpty()
            val idRegex = Regex("/detail/(?:[^/]+/)?([a-p]{32})(?:[/?#]|$)", RegexOption.IGNORE_CASE)
            val match = idRegex.find(path) ?: return null
            val extId = match.groupValues[1].lowercase()
            return when {
                host == "chromewebstore.google.com" || (host == "chrome.google.com" && path.lowercase().contains("/webstore")) -> {
                    StoreExtensionRef(
                        storeName = "Chrome Web Store",
                        extensionId = extId,
                        crxDownloadUrl = "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=131.0.0.0&acceptformat=crx2,crx3&x=id%3D$extId%26uc"
                    )
                }
                host == "microsoftedge.microsoft.com" && path.lowercase().contains("/addons") -> {
                    StoreExtensionRef(
                        storeName = "Microsoft Edge Add-ons",
                        extensionId = extId,
                        crxDownloadUrl = "https://edge.microsoft.com/extensionwebstorebase/v1/crx?response=redirect&x=id%3D$extId%26installsource%3Dondemand%26uc"
                    )
                }
                else -> null
            }
        }

        fun resolveConfinedFile(rootDir: File, relativePath: String): File? {
            if (relativePath.isBlank() || relativePath.contains('\u0000')) return null
            val normalizedRel = relativePath.replace('\\', '/').trimStart('/')
            if (normalizedRel.isBlank()) return null
            return runCatching {
                val root = rootDir.canonicalFile
                val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separatorChar
                val target = File(root, normalizedRel).canonicalFile
                if (target.path.startsWith(rootPrefix)) target else null
            }.getOrNull()
        }
    }
}
