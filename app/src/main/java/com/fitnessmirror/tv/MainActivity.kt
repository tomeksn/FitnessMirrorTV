package com.fitnessmirror.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import com.fitnessmirror.tv.network.PhoneDiscovery
import com.fitnessmirror.tv.network.SignalingClient
import com.fitnessmirror.tv.webrtc.WebRTCClient
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity(),
    PhoneDiscovery.DiscoveryListener,
    SignalingClient.SignalingListener,
    WebRTCClient.WebRTCClientCallback {

    companion object {
        private const val TAG = "FitnessMirrorTV"
    }

    // UI Components
    private lateinit var youtubePlayerView: YouTubePlayerView
    private lateinit var cameraSurface: SurfaceViewRenderer
    private lateinit var statusOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var latencyText: TextView

    // Stats Timer
    private var statsHandler: Handler? = null
    private var statsRunnable: Runnable? = null
    private val STATS_UPDATE_INTERVAL_MS = 1000L

    // YouTube Player
    private var youtubePlayer: YouTubePlayer? = null
    private var pendingVideoId: String? = null
    private var pendingVideoTime: Float = 0f

    // Network & WebRTC
    private var phoneDiscovery: PhoneDiscovery? = null
    private var signalingClient: SignalingClient? = null
    private var webrtcClient: WebRTCClient? = null

    // State
    private var isConnected = false
    private var connectedPhoneIp: String? = null
    private var connectionRetryCount = 0
    private val MAX_CONNECTION_RETRIES = 5

    // Adaptive quality control (framesDecoded delta — avoids false positives from low render fps)
    private var lastFramesDecoded = 0L
    private var consecutiveGoodDecodedCount = 0
    private var lastQualityChangeTime = 0L
    private val QUALITY_DECREASE_COOLDOWN_MS = 15_000L  // min 15s between decreases
    private val QUALITY_INCREASE_COOLDOWN_MS = 30_000L   // min 30s between increases

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        initializeYouTubePlayer()
        initializeWebRTC()
        startPhoneDiscovery()
    }

    private fun initializeViews() {
        youtubePlayerView = findViewById(R.id.youtube_player)
        cameraSurface = findViewById(R.id.camera_surface)
        statusOverlay = findViewById(R.id.status_overlay)
        statusText = findViewById(R.id.status_text)
        statusDetail = findViewById(R.id.status_detail)
        progressBar = findViewById(R.id.progress_bar)

        lifecycle.addObserver(youtubePlayerView)

        // Latency stats display
        latencyText = findViewById(R.id.latency_text)
    }

    private fun initializeYouTubePlayer() {
        // Disable automatic initialization to use IFramePlayerOptions
        youtubePlayerView.enableAutomaticInitialization = false

        val iFramePlayerOptions = IFramePlayerOptions.Builder(this)
            .controls(1)
            .rel(0)
            .ivLoadPolicy(3)
            .ccLoadPolicy(1)
            .build()

        youtubePlayerView.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(player: YouTubePlayer) {
                Log.d(TAG, "YouTube player ready")
                youtubePlayer = player
                // Load video that arrived before player was ready
                pendingVideoId?.let { videoId ->
                    Log.d(TAG, "Loading buffered video: $videoId at $pendingVideoTime")
                    player.loadVideo(videoId, pendingVideoTime)
                    pendingVideoId = null
                }
            }
        }, iFramePlayerOptions)
    }

    private fun initializeWebRTC() {
        webrtcClient = WebRTCClient(this, this)
        webrtcClient?.initialize(cameraSurface)
    }

    private fun startPhoneDiscovery() {
        Log.d(TAG, "Starting phone discovery")
        updateStatus(getString(R.string.searching_phone), "Nasłuchiwanie UDP broadcast...")

        phoneDiscovery = PhoneDiscovery(this)
        phoneDiscovery?.startListening()
    }

    private fun connectToPhone(ip: String, port: Int) {
        Log.d(TAG, "Connecting to phone at $ip:$port")
        updateStatus(getString(R.string.connecting), "Łączenie z $ip:$port...")

        connectedPhoneIp = ip

        signalingClient = SignalingClient(this)
        signalingClient?.connect(ip, port)
    }

    private fun updateStatus(main: String, detail: String = "") {
        runOnUiThread {
            statusText.text = main
            statusDetail.text = detail
        }
    }

    private fun showCamera() {
        runOnUiThread {
            statusOverlay.visibility = View.GONE
            cameraSurface.visibility = View.VISIBLE
        }
    }

    private fun hideCamera() {
        runOnUiThread {
            statusOverlay.visibility = View.VISIBLE
            cameraSurface.visibility = View.GONE
        }
    }

    private fun startStatsCollection() {
        stopStatsCollection()
        statsHandler = Handler(Looper.getMainLooper())
        statsRunnable = object : Runnable {
            override fun run() {
                collectAndDisplayStats()
                statsHandler?.postDelayed(this, STATS_UPDATE_INTERVAL_MS)
            }
        }
        statsHandler?.post(statsRunnable!!)
        latencyText.visibility = View.VISIBLE
        Log.d(TAG, "Stats collection started")
    }

    private fun stopStatsCollection() {
        statsRunnable?.let { statsHandler?.removeCallbacks(it) }
        statsHandler = null
        statsRunnable = null
        runOnUiThread { latencyText.visibility = View.GONE }
        Log.d(TAG, "Stats collection stopped")
    }

    private fun collectAndDisplayStats() {
        webrtcClient?.getStats { stats ->
            runOnUiThread {
                if (stats != null) {
                    val rtt = stats.rttMs?.let { String.format(Locale.US, "%.0f", it) } ?: "--"
                    val jitter = stats.jitterMs?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
                    val fps = stats.framesPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
                    val dropped = stats.framesDropped?.toString() ?: "--"
                    latencyText.text = "RTT: ${rtt}ms | Jitter: ${jitter}ms\nFPS: $fps | Dropped: $dropped"

                    // Adaptive quality control: use framesDecoded delta (decoded fps ≠ rendered fps)
                    // EglRenderer always renders ~11fps on this TV (structural GPU limit) → false positives
                    // framesDecoded counts actual H264 decoder output — real stall = decodedDelta < 5
                    val currentDecoded = stats.framesDecoded ?: 0L
                    val decodedDelta = currentDecoded - lastFramesDecoded
                    lastFramesDecoded = currentDecoded
                    val now = System.currentTimeMillis()

                    if (decodedDelta < 5) {
                        // Real decoder stall — fewer than 5 frames decoded in this second
                        consecutiveGoodDecodedCount = 0
                        if (now - lastQualityChangeTime > QUALITY_DECREASE_COOLDOWN_MS) {
                            Log.d(TAG, "Decoder stall detected (decoded=$decodedDelta/s) - requesting quality decrease")
                            signalingClient?.sendQualityControl("decrease")
                            lastQualityChangeTime = now
                        }
                    } else if (decodedDelta >= 16) {
                        consecutiveGoodDecodedCount++
                        if (consecutiveGoodDecodedCount >= 8 && now - lastQualityChangeTime > QUALITY_INCREASE_COOLDOWN_MS) {
                            Log.d(TAG, "Decoder stable for 8s (decoded=$decodedDelta/s) - requesting quality increase")
                            signalingClient?.sendQualityControl("increase")
                            lastQualityChangeTime = now
                            consecutiveGoodDecodedCount = 0
                        }
                    } else {
                        // decodedDelta 5-15: neutral zone, stop counting good streaks
                        consecutiveGoodDecodedCount = 0
                    }
                } else {
                    latencyText.text = "Stats: unavailable"
                }
            }
        }
    }

    // PhoneDiscovery.DiscoveryListener
    override fun onPhoneFound(ip: String, port: Int, name: String) {
        Log.d(TAG, "Phone found: $name at $ip:$port")

        // Stop discovery and connect to first found phone
        phoneDiscovery?.stopListening()
        connectToPhone(ip, port)
    }

    override fun onDiscoveryError(error: String) {
        Log.e(TAG, "Discovery error: $error")
        updateStatus("Błąd discovery", error)
    }

    // SignalingClient.SignalingListener
    override fun onConnected() {
        Log.d(TAG, "WebSocket connected")
        updateStatus(getString(R.string.connected), "Oczekiwanie na WebRTC offer...")
        isConnected = true
        connectionRetryCount = 0
    }

    override fun onDisconnected() {
        Log.d(TAG, "WebSocket disconnected")
        isConnected = false
        hideCamera()
        updateStatus(getString(R.string.disconnected), "Ponowne szukanie telefonu...")

        // Restart discovery
        lifecycleScope.launch(Dispatchers.IO) {
            Thread.sleep(2000)
            runOnUiThread {
                startPhoneDiscovery()
            }
        }
    }

    override fun onOfferReceived(sdp: String) {
        Log.d(TAG, "WebRTC offer received")
        updateStatus("Nawiązywanie połączenia", "Przetwarzanie WebRTC offer...")
        webrtcClient?.handleOffer(sdp)
    }

    override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        Log.d(TAG, "ICE candidate received from phone")
        webrtcClient?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    override fun onVideoUrlReceived(videoId: String, currentTime: Float) {
        Log.d(TAG, "Video URL received: $videoId at $currentTime")
        runOnUiThread {
            val player = youtubePlayer
            if (player != null) {
                player.loadVideo(videoId, currentTime)
            } else {
                Log.d(TAG, "YouTube player not ready - buffering video: $videoId")
                pendingVideoId = videoId
                pendingVideoTime = currentTime
            }
        }
    }

    override fun onVideoControlReceived(command: String, value: Float?) {
        Log.d(TAG, "Video control received: $command, value: $value")
        runOnUiThread {
            when (command) {
                "play" -> youtubePlayer?.play()
                "pause" -> youtubePlayer?.pause()
                "seekTo" -> value?.let { youtubePlayer?.seekTo(it) }
                "stop" -> youtubePlayer?.pause()
            }
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "Signaling error: $error")
        isConnected = false

        // Cleanup failed connection
        signalingClient?.disconnect()
        signalingClient = null

        connectionRetryCount++
        if (connectionRetryCount <= MAX_CONNECTION_RETRIES) {
            val delaySec = minOf(connectionRetryCount * 2, 10)
            updateStatus("Błąd połączenia", "$error - ponowna próba za ${delaySec}s (${connectionRetryCount}/$MAX_CONNECTION_RETRIES)")
            Log.d(TAG, "Retrying discovery in ${delaySec}s (attempt $connectionRetryCount/$MAX_CONNECTION_RETRIES)")

            lifecycleScope.launch(Dispatchers.IO) {
                Thread.sleep(delaySec * 1000L)
                runOnUiThread {
                    startPhoneDiscovery()
                }
            }
        } else {
            updateStatus("Błąd połączenia", "Nie udało się połączyć po $MAX_CONNECTION_RETRIES próbach. Uruchom ponownie.")
            Log.e(TAG, "Max connection retries reached ($MAX_CONNECTION_RETRIES)")
        }
    }

    // WebRTCClient.WebRTCClientCallback
    override fun onLocalDescription(sdp: SessionDescription) {
        Log.d(TAG, "Sending SDP answer to phone")
        signalingClient?.sendAnswer(sdp.description)
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "Sending ICE candidate to phone")
        signalingClient?.sendIceCandidate(
            candidate.sdpMid ?: "",
            candidate.sdpMLineIndex,
            candidate.sdp
        )
    }

    override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
        Log.d(TAG, "WebRTC connection state: $state")
        runOnUiThread {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    updateStatus("Połączono", "Stream aktywny")
                    showCamera()
                    startStatsCollection()
                }
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.FAILED -> {
                    stopStatsCollection()
                    hideCamera()
                    updateStatus("Rozłączono", "Utracono połączenie WebRTC")
                }
                PeerConnection.PeerConnectionState.CONNECTING -> {
                    updateStatus("Łączenie WebRTC", "Wymiana ICE candidates...")
                }
                else -> {}
            }
        }
    }

    override fun onVideoTrackReceived(videoTrack: VideoTrack) {
        Log.d(TAG, "Video track received and attached to renderer")
    }

    override fun onDestroy() {
        super.onDestroy()

        stopStatsCollection()
        phoneDiscovery?.stopListening()
        signalingClient?.disconnect()
        webrtcClient?.close()
    }
}
