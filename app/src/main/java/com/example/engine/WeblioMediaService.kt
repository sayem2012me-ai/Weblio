package com.example.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

/**
 * Foreground MediaSession service providing media notifications and hardware media button controls
 * when HTML5 audio/video plays in WEBLIO tabs.
 */
class WeblioMediaService : Service() {

    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "WeblioMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    sendBroadcast(Intent(BROADCAST_MEDIA_ACTION).putExtra("cmd", "play").setPackage(packageName))
                    updatePlaybackState(true)
                }

                override fun onPause() {
                    sendBroadcast(Intent(BROADCAST_MEDIA_ACTION).putExtra("cmd", "pause").setPackage(packageName))
                    updatePlaybackState(false)
                }

                override fun onStop() {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                sendBroadcast(Intent(BROADCAST_MEDIA_ACTION).putExtra("cmd", "toggle_play").setPackage(packageName))
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                sendBroadcast(Intent(BROADCAST_MEDIA_ACTION).putExtra("cmd", "toggle_mute").setPackage(packageName))
                return START_NOT_STICKY
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Weblo Media"
        val host = intent?.getStringExtra(EXTRA_HOST) ?: "Playing in background"
        val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, true) ?: true
        val isMuted = intent?.getBooleanExtra(EXTRA_IS_MUTED, false) ?: false

        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, host)
                .build()
        )
        updatePlaybackState(isPlaying)
        postMediaNotification(title, host, isPlaying, isMuted)
        return START_STICKY
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun postMediaNotification(title: String, host: String, isPlaying: Boolean, isMuted: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Weblo Media Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WeblioMediaService::class.java).setAction(ACTION_TOGGLE_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val muteIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WeblioMediaService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(if (isMuted) "$host • Muted" else host)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(
                if (isMuted) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode,
                if (isMuted) "Unmute" else "Mute",
                muteIntent
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setOngoing(isPlaying)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.engine.MEDIA_START"
        const val ACTION_STOP = "com.example.engine.MEDIA_STOP"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.example.engine.MEDIA_TOGGLE_PLAY"
        const val ACTION_TOGGLE_MUTE = "com.example.engine.MEDIA_TOGGLE_MUTE"
        const val BROADCAST_MEDIA_ACTION = "com.example.engine.MEDIA_CMD"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_IS_MUTED = "extra_is_muted"
        private const val CHANNEL_ID = "weblio_media_playback"
        private const val NOTIF_ID = 5109
    }
}
