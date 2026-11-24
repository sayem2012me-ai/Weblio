package com.example.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.WeblioRepository
import com.example.data.db.AdBlockRuleEntity
import com.example.data.db.FilterRuleType
import com.example.data.db.WeblioDatabase
import com.example.data.prefs.BrowserPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class FilterUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = WeblioDatabase.getInstance(applicationContext)
        val repo = WeblioRepository(db)
        val prefs = BrowserPreferences(applicationContext)
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val subscriptions = repo.getSubscriptionsOnce().filter { it.enabled }
        var anySuccess = false

        for (sub in subscriptions) {
            if (!sub.url.startsWith("https://", ignoreCase = true)) continue
            runCatching {
                val request = Request.Builder().url(sub.url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyStream = response.body?.byteStream() ?: return@use
                    val maxBytes = 5 * 1024 * 1024
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    bodyStream.use { input ->
                        while (total < maxBytes) {
                            val read = input.read(buf, 0, minOf(buf.size, maxBytes - total))
                            if (read == -1) break
                            out.write(buf, 0, read)
                            total += read
                        }
                    }
                    val body = out.toString(Charsets.UTF_8.name())
                    val parsed = parseFilterList(body, sub.name, maxRules = 1500)
                    if (parsed.isNotEmpty()) {
                        repo.replaceRulesForSource(sub.name, parsed)
                        repo.updateSubscription(
                            sub.copy(
                                ruleCount = parsed.size,
                                lastUpdatedAt = System.currentTimeMillis()
                            )
                        )
                        anySuccess = true
                    }
                }
            }
        }

        if (anySuccess) {
            prefs.setLastFilterUpdate(System.currentTimeMillis())
        }
        return Result.success()
    }

    companion object {
        fun parseFilterList(rawText: String, sourceName: String, maxRules: Int = 1500): List<AdBlockRuleEntity> {
            val rules = mutableListOf<AdBlockRuleEntity>()
            for (line in rawText.lineSequence()) {
                if (rules.size >= maxRules) break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("[") || trimmed.startsWith("# ")) {
                    continue
                }
                when {
                    trimmed.startsWith("##") -> {
                        rules.add(AdBlockRuleEntity(pattern = trimmed, ruleType = FilterRuleType.COSMETIC_HIDE.name, sourceList = sourceName))
                    }
                    trimmed.startsWith("@@") -> {
                        rules.add(AdBlockRuleEntity(pattern = trimmed, ruleType = FilterRuleType.NETWORK_ALLOW.name, sourceList = sourceName))
                    }
                    trimmed.startsWith("0.0.0.0 ") || trimmed.startsWith("127.0.0.1 ") -> {
                        val domain = trimmed.substringAfter(' ').trim().substringBefore('#').trim()
                        if (domain.isNotEmpty() && domain != "localhost") {
                            rules.add(AdBlockRuleEntity(pattern = domain, ruleType = FilterRuleType.DNS_BLOCK.name, sourceList = sourceName))
                        }
                    }
                    trimmed.startsWith("||") && !trimmed.contains("$") -> {
                        rules.add(AdBlockRuleEntity(pattern = trimmed, ruleType = FilterRuleType.NETWORK_BLOCK.name, sourceList = sourceName))
                    }
                }
            }
            return rules
        }
    }
}
