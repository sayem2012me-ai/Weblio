package com.example.engine

import com.example.data.prefs.SearchEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches live OpenSearch autocomplete suggestions for the selected SearchEngine over HTTPS.
 */
class SearchSuggestionEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(3000, TimeUnit.MILLISECONDS)
        .build()
) {
    fun fetchSuggestions(query: String, engine: SearchEngine): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        if (looksLikeExplicitUrl(trimmed)) return emptyList()

        val url = buildSuggestUrl(trimmed, engine)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json, text/javascript, */*")
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                parseOpenSearchSuggestions(body, trimmed)
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        fun buildSuggestUrl(query: String, engine: SearchEngine): String {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            return when (engine) {
                SearchEngine.GOOGLE -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded"
                SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/ac/?q=$encoded&type=list"
                SearchEngine.BRAVE -> "https://search.brave.com/api/suggest?q=$encoded"
                SearchEngine.BING -> "https://api.bing.com/osjson.aspx?query=$encoded"
                SearchEngine.ECOSIA -> "https://ac.ecosia.org/autocomplete?q=$encoded&type=list"
            }
        }

        fun parseOpenSearchSuggestions(rawJson: String, originalQuery: String): List<String> {
            val trimmedBody = rawJson.trim()
            if (trimmedBody.isEmpty()) return emptyList()
            val normalizedInput = originalQuery.trim()

            return runCatching {
                val results = mutableListOf<String>()
                val seen = HashSet<String>()
                if (normalizedInput.isNotBlank()) {
                    seen.add(normalizedInput.lowercase())
                }

                if (trimmedBody.startsWith("[")) {
                    val root = Json.parseToJsonElement(trimmedBody).jsonArray
                    // Standard OpenSearch format: [ "query", [ "s1", "s2", ... ] ]
                    val secondEl = root.getOrNull(1)
                    if (secondEl != null && runCatching { secondEl.jsonArray }.isSuccess) {
                        val arr = secondEl.jsonArray
                        for (el in arr) {
                            val item = runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()?.trim().orEmpty()
                            if (item.isNotBlank() && seen.add(item.lowercase())) {
                                results.add(item)
                            }
                        }
                    } else {
                        // Alternate format: [ { "phrase": "..." }, ... ]
                        for (el in root) {
                            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
                            val item = (obj["phrase"] ?: obj["q"])
                                ?.let { p -> runCatching { p.jsonPrimitive.contentOrNull }.getOrNull() }
                                ?.trim()
                                .orEmpty()
                            if (item.isNotBlank() && seen.add(item.lowercase())) {
                                results.add(item)
                            }
                        }
                    }
                }
                results.take(10)
            }.getOrDefault(emptyList())
        }

        fun looksLikeExplicitUrl(input: String): Boolean {
            val trimmed = input.trim()
            if (trimmed.contains(" ")) return false
            return trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("about:", ignoreCase = true) ||
                trimmed.startsWith("weblio://", ignoreCase = true)
        }
    }
}
