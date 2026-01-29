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

    // Network & WebRTC
    private var phoneDiscovery: PhoneDiscovery? = null
    private var signalingClient: SignalingClient? = null
    private var webrtcClient: WebRTCClient? = null

    // State
    private var isConnected = false
    private var connectedPhoneIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        // initializeYouTubePlayer()  // DISABLED for WebRTC debugging
        initializeWebRTC()
        startPhoneDiscovery()
    }

    private fun initializeViews() {
        // youtubePlayerView = findViewById(R.id.youtube_player)  // REMOVED for WebRTC debugging
        cameraSurface = findViewById(R.id.camera_surface)
        statusOverlay = findViewById(R.id.status_overlay)
        statusText = findViewById(R.id.status_text)
        statusDetail = findViewById(R.id.status_detail)
        progressBar = findViewById(R.id.progress_bar)

        // Add YouTube player to lifecycle - DISABLED for WebRTC debugging
        // lifecycle.addObserver(youtubePlayerView)

        // Latency stats display
        latencyText = findViewById(R.id.latency_text)
    }

    private fun initializeYouTubePlayer() {
        youtubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(player: YouTubePlayer) {
                Log.d(TAG, "YouTube player ready")
                youtubePlayer = player
            }
        })
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
        Log.d(TAG, "Video URL received: $videoId at $currentTime (YouTube DISABLED)")
        // YouTube player disabled for WebRTC debugging
        // runOnUiThread {
        //     youtubePlayer?.loadVideo(videoId, currentTime)
        // }
    }

    override fun onVideoControlReceived(command: String, value: Float?) {
        Log.d(TAG, "Video control received: $command, value: $value (YouTube DISABLED)")
        // YouTube player disabled for WebRTC debugging
    }

    override fun onError(error: String) {
        Log.e(TAG, "Signaling error: $error")
        updateStatus("Błąd połączenia", error)
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
