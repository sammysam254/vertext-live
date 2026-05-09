package com.vertext.screenshare

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*

class ScreenShareService : Service() {

    companion object {
        const val CHANNEL_ID        = "ScreenShareChannel"
        const val NOTIFICATION_ID   = 1001
        const val ACTION_START      = "ACTION_START"
        const val ACTION_STOP       = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        const val EXTRA_BACKEND_URL = "BACKEND_URL"
        const val EXTRA_PIN         = "PIN"
        const val TAG               = "ScreenShareService"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // WebRTC
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null

    // Signaling
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient()

    // MediaProjection
    private var mediaProjection: MediaProjection? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode  = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData  = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val backendUrl  = intent.getStringExtra(EXTRA_BACKEND_URL) ?: ""
                val pin         = intent.getStringExtra(EXTRA_PIN) ?: ""
                startForeground(NOTIFICATION_ID, buildNotification())
                if (resultData != null) initStreaming(resultCode, resultData, backendUrl, pin)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_STICKY
    }

    private fun initStreaming(resultCode: Int, resultData: Intent, backendUrl: String, pin: String) {
        scope.launch {
            try {
                // Init EGL and WebRTC
                eglBase = EglBase.create()
                val options = PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)

                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
                    .createPeerConnectionFactory()

                // Get screen dimensions
                val metrics = DisplayMetrics()
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                wm.defaultDisplay.getMetrics(metrics)
                val width  = minOf(metrics.widthPixels, 1280)
                val height = minOf(metrics.heightPixels, 720)

                // MediaProjection screen capturer
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = pm.getMediaProjection(resultCode, resultData)

                videoCapturer = ScreenCapturerAndroid(
                    resultData,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "MediaProjection stopped")
                            stopStreaming()
                        }
                    }
                )

                val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
                val videoSource = peerConnectionFactory!!.createVideoSource(true)
                videoCapturer!!.initialize(surfaceTextureHelper, applicationContext, videoSource.capturerObserver)
                videoCapturer!!.startCapture(width, height, 30)

                localVideoTrack = peerConnectionFactory!!.createVideoTrack("screen_track", videoSource)

                // Create PeerConnection
                val config = PeerConnection.RTCConfiguration(iceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }

                peerConnection = peerConnectionFactory!!.createPeerConnection(config, object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        val msg = JSONObject().apply {
                            put("type", "ice")
                            put("candidate", JSONObject().apply {
                                put("sdpMid", candidate.sdpMid)
                                put("sdpMLineIndex", candidate.sdpMLineIndex)
                                put("candidate", candidate.sdp)
                            })
                        }
                        webSocket?.send(msg.toString())
                    }
                    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                        Log.d(TAG, "Connection state: $state")
                    }
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
                    override fun onIceConnectionReceivingChange(b: Boolean) {}
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
                    override fun onSignalingChange(s: PeerConnection.SignalingState) {}
                    override fun onAddStream(s: MediaStream) {}
                    override fun onRemoveStream(s: MediaStream) {}
                    override fun onDataChannel(d: DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(r: RtpReceiver, s: Array<MediaStream>) {}
                    override fun onIceCandidatesRemoved(c: Array<IceCandidate>) {}
                })

                peerConnection?.addTrack(localVideoTrack!!)

                // Connect WebSocket signaling
                val wsUrl = backendUrl
                    .replace("https://", "wss://")
                    .replace("http://", "ws://") + "/ws/$pin/streamer"

                val request = Request.Builder().url(wsUrl).build()
                webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        Log.d(TAG, "Signaling connected")
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        scope.launch { handleSignal(text) }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "WebSocket error: ${t.message}")
                    }
                })

                Log.d(TAG, "Streaming initialised, waiting for viewers")
            } catch (e: Exception) {
                Log.e(TAG, "Init error: ${e.message}", e)
                stopSelf()
            }
        }
    }

    private suspend fun handleSignal(text: String) {
        val msg = JSONObject(text)
        when (msg.getString("type")) {
            "viewer_joined" -> {
                // New viewer connected — create and send offer
                val offerOptions = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                }
                peerConnection?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val offer = JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", JSONObject().apply {
                                        put("type", sdp.type.canonicalForm())
                                        put("sdp", sdp.description)
                                    })
                                }
                                webSocket?.send(offer.toString())
                            }
                            override fun onSetFailure(s: String?) { Log.e(TAG, "setLocalDesc failed: $s") }
                            override fun onCreateSuccess(s: SessionDescription?) {}
                            override fun onCreateFailure(s: String?) {}
                        }, sdp)
                    }
                    override fun onCreateFailure(s: String?) { Log.e(TAG, "createOffer failed: $s") }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(s: String?) {}
                }, offerOptions)
            }
            "answer" -> {
                val sdpJson = msg.getJSONObject("sdp")
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(sdpJson.getString("type")),
                    sdpJson.getString("sdp")
                )
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() { Log.d(TAG, "Remote description set") }
                    override fun onSetFailure(s: String?) { Log.e(TAG, "setRemoteDesc failed: $s") }
                    override fun onCreateSuccess(s: SessionDescription?) {}
                    override fun onCreateFailure(s: String?) {}
                }, sdp)
            }
            "ice" -> {
                val c = msg.getJSONObject("candidate")
                val candidate = IceCandidate(
                    c.getString("sdpMid"),
                    c.getInt("sdpMLineIndex"),
                    c.getString("candidate")
                )
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    private fun stopStreaming() {
        scope.launch {
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                localVideoTrack?.dispose()
                peerConnection?.close()
                peerConnectionFactory?.dispose()
                eglBase?.release()
                webSocket?.close(1000, "Stream ended")
                mediaProjection?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Stop error: ${e.message}")
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
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, ScreenShareService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vertext Live — Streaming")
            .setContentText("Your screen is live. Tap to stop.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setOngoing(true).build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
