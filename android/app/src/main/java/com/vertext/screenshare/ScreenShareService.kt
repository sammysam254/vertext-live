package com.vertext.screenshare

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.*

class ScreenShareService : Service() {

    companion object {
        const val CHANNEL_ID          = "ScreenShareChannel"
        const val NOTIFICATION_ID     = 1001
        const val ACTION_START        = "ACTION_START"
        const val ACTION_STOP         = "ACTION_STOP"
        const val EXTRA_RESULT_CODE   = "RESULT_CODE"
        const val EXTRA_RESULT_DATA   = "RESULT_DATA"
        const val EXTRA_LIVEKIT_URL   = "LIVEKIT_URL"
        const val EXTRA_LIVEKIT_TOKEN = "LIVEKIT_TOKEN"
        const val TAG                 = "ScreenShareService"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var room: Room? = null
    private var mediaProjection: MediaProjection? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode   = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData   = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val livekitUrl   = intent.getStringExtra(EXTRA_LIVEKIT_URL) ?: ""
                val livekitToken = intent.getStringExtra(EXTRA_LIVEKIT_TOKEN) ?: ""
                startForeground(NOTIFICATION_ID, buildNotification())
                if (resultData != null) startStreaming(resultCode, resultData, livekitUrl, livekitToken)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming(resultCode: Int, resultData: Intent, livekitUrl: String, token: String) {
        scope.launch {
            try {
                room = LiveKit.create(applicationContext)
                room?.connect(livekitUrl, token)
                Log.d(TAG, "Connected to LiveKit")
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = pm.getMediaProjection(resultCode, resultData)
                room?.localParticipant?.setScreenShareEnabled(
                    enabled = true,
                    mediaProjectionPermissionResultData = resultData
                )
                Log.d(TAG, "Screen track published")
            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}", e)
                stopSelf()
            }
        }
    }

    private fun stopStreaming() {
        scope.launch {
            try {
                room?.localParticipant?.setScreenShareEnabled(false)
                room?.disconnect()
                room = null
                mediaProjection?.stop()
                mediaProjection = null
            } catch (e: Exception) {
                Log.e(TAG, "Stop error: ${e.message}", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Screen Sharing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenShareService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vertext Live — Streaming")
            .setContentText("Your screen is live. Tap to stop.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
