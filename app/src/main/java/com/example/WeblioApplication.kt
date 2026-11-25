package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.Coil
import com.example.data.WeblioRepository
import com.example.data.db.WeblioDatabase
import com.example.data.prefs.BrowserPreferences
import com.example.engine.AdBlockEngine
import com.example.engine.BackupRestoreManager
import com.example.engine.ExtensionEngine
import com.example.engine.FilterUpdateWorker
import com.example.engine.WeblioDownloadManager
import com.example.security.AppIntegrityManager
import com.example.security.DownloadQuarantineManager
import com.example.security.DownloadSecurityScanner
import com.example.security.SecureUpdateVerifier
import com.example.security.ThreatIntelligenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class WeblioApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _trimMemoryEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val trimMemoryEvents: SharedFlow<Int> = _trimMemoryEvents.asSharedFlow()

    lateinit var database: WeblioDatabase
        private set
    lateinit var repository: WeblioRepository
        private set
    lateinit var preferences: BrowserPreferences
        private set
    lateinit var adBlockEngine: AdBlockEngine
        private set
    lateinit var extensionEngine: ExtensionEngine
        private set
    lateinit var threatEngine: ThreatIntelligenceEngine
        private set
    lateinit var quarantineManager: DownloadQuarantineManager
        private set
    lateinit var downloadScanner: DownloadSecurityScanner
        private set
    lateinit var downloadManager: WeblioDownloadManager
        private set
    lateinit var integrityManager: AppIntegrityManager
        private set
    lateinit var updateVerifier: SecureUpdateVerifier
        private set
    lateinit var backupManager: BackupRestoreManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = WeblioDatabase.getInstance(this)
        repository = WeblioRepository(database)
        preferences = BrowserPreferences(this)
        adBlockEngine = AdBlockEngine(repository)
        extensionEngine = ExtensionEngine(this, repository)
        threatEngine = ThreatIntelligenceEngine(repository)
        quarantineManager = DownloadQuarantineManager(this)
        downloadScanner = DownloadSecurityScanner(repository)
        downloadManager = WeblioDownloadManager(this, repository, quarantineManager, downloadScanner)
        integrityManager = AppIntegrityManager(this)
        updateVerifier = SecureUpdateVerifier(this)
        backupManager = BackupRestoreManager(repository, preferences)

        appScope.launch {
            runCatching { Coil.imageLoader(this@WeblioApplication) }
            downloadManager.cleanupAbandonedQuarantine()
            scheduleFilterUpdates()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        _trimMemoryEvents.tryEmit(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
        }
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        _trimMemoryEvents.tryEmit(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        runCatching { Coil.imageLoader(this).memoryCache?.clear() }
    }

    private fun scheduleFilterUpdates() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<FilterUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "weblio_filter_updates",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
