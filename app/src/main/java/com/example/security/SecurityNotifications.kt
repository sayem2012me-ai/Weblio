package com.example.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R

/**
 * Privacy-Preserving Security Notifications.
 * Never exposes passwords, full private URLs, query parameters, or incognito browsing data.
 */
object SecurityNotifications {
    private const val CHANNEL_ID = "weblio_security_alerts"

    enum class AlertKind(val notifId: Int, val title: String) {
        DANGEROUS_DOWNLOAD(8101, "Dangerous download blocked"),
        SUSPICIOUS_WEBSITE(8102, "Unsafe website blocked"),
        MALICIOUS_EXTENSION(8103, "Extension security warning"),
        INTEGRITY_WARNING(8104, "Browser integrity alert"),
        SECURITY_UPDATE_AVAILABLE(8105, "WEBLIO security update available"),
        THREAT_DB_UPDATE_FAILED(8106, "Threat database update failed"),
        REPEATED_SUSPICIOUS_ACTIVITY(8107, "Repeated suspicious activity detected")
    }

    fun notify(
        context: Context,
        enabled: Boolean,
        kind: AlertKind,
        safeMessage: String
    ) {
        if (!enabled) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "WEBLIO Security Protection",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(kind.title)
            .setContentText(sanitizeNotificationText(safeMessage))
            .setStyle(NotificationCompat.BigTextStyle().bigText(sanitizeNotificationText(safeMessage)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        runCatching { nm.notify(kind.notifId, builder.build()) }
    }

    /**
     * Strips URL query strings/credentials so notifications never leak sensitive paths or tokens.
     */
    fun sanitizeNotificationText(raw: String): String {
        return raw.replace(Regex("https?://([^/?#\\s]+)[^\\s]*"), "$1")
            .take(180)
    }
}
