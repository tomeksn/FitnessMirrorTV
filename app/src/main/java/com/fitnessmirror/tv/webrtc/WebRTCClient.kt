package com.fitnessmirror.tv.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.PeerConnection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WebRTC client for receiving video stream from FitnessMirror phone app.
 * Acts as answerer - receives offer from phone and creates answer.
 */
class WebRTCClient(
    private val context: Context,
    private val callback: WebRTCClientCallback
) {
    companion object {
        private const val TAG = "WebRTCClient"
    }

    interface WebRTCClientCallback {
        fun onLocalDescription(sdp: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
        fun onConnectionStateChange(state: PeerConnectionState)
        fun onVideoTrackReceived(videoTrack: VideoTrack)
        fun onError(error: String)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var surfaceViewRenderer: SurfaceViewRenderer? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    /**
     * Initialize WebRTC components
     * @param renderer The SurfaceViewRenderer to display received video
     */
    fun initialize(renderer: SurfaceViewRenderer) {
        Log.d(TAG, "Initializing WebRTC client")

        try {
            surfaceViewRenderer = renderer

            // Create EGL context
            eglBase = EglBase.create()

            // Initialize PeerConnectionFactory
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initializationOptions)

            // Create PeerConnectionFactory with video decoder
            val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase!!.eglBaseContext,
                true,  // Enable Intel VP8 encoder
                true   // Enable H264 high profile
            )

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(decoderFactory)
                .setVideoEncoderFactory(encoderFactory)
                .createPeerConnectionFactory()

            // Initialize SurfaceViewRenderer
            renderer.init(eglBase!!.eglBaseContext, null)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            renderer.setMirror(true)  // Mirror for front camera view

            Log.d(TAG, "WebRTC client initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC client", e)
            callback.onError("WebRTC initialization failed: ${e.message}")
        }
    }

    /**
     * Handle incoming SDP offer from phone
     * Creates peer connection if needed and generates answer
     */
    fun handleOffer(sdp: String) {
        Log.d(TAG, "Handling SDP offer")

        coroutineScope.launch {
            try {
                // Create peer connection if not exists
                if (peerConnection == null) {
                    createPeerConnection()
                }

                // Set remote description (offer)
                val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Remote description (offer) set successfully")
                        createAnswer()
                    }

                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set remote description: $error")
                        callback.onError("Failed to set remote description: $error")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, offer)

            } catch (e: Exception) {
                Log.e(TAG, "Error handling offer", e)
                callback.onError("Error handling offer: ${e.message}")
            }
        }
    }

    /**
     * Create SDP answer after receiving offer
     */
    private fun createAnswer() {
        Log.d(TAG, "Creating SDP answer")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Answer created successfully")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description (answer) set successfully")
                        callback.onLocalDescription(sdp)
                    }

                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set local description: $error")
                        callback.onError("Failed to set local description: $error")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create answer: $error")
                callback.onError("Failed to create answer: $error")
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Create and configure peer connection
     */
    private fun createPeerConnection() {
        Log.d(TAG, "Creating peer connection")

        // ICE servers configuration (same as phone app)
        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        // RTCConfiguration
        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        // Create peer connection
        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "ICE candidate generated")
                    callback.onIceCandidate(candidate)
                }

                override fun onConnectionChange(newState: PeerConnectionState) {
                    Log.d(TAG, "Connection state changed: $newState")
                    callback.onConnectionStateChange(newState)
                }

                override fun onIceConnectionChange(newState: IceConnectionState) {
                    Log.d(TAG, "ICE connection state: $newState")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE connection receiving change: $receiving")
                }

                override fun onIceGatheringChange(newState: IceGatheringState) {
                    Log.d(TAG, "ICE gathering state: $newState")
                }

                override fun onAddStream(stream: MediaStream) {
                    Log.d(TAG, "Stream added: ${stream.id}")
                    // Handle incoming video stream
                    if (stream.videoTracks.isNotEmpty()) {
                        val videoTrack = stream.videoTracks[0]
                        Log.d(TAG, "Video track received")
                        handleVideoTrack(videoTrack)
                    }
                }

                override fun onRemoveStream(stream: MediaStream) {
                    Log.d(TAG, "Stream removed: ${stream.id}")
                }

                override fun onDataChannel(dataChannel: DataChannel) {
                    Log.d(TAG, "Data channel received: ${dataChannel.label()}")
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }

                override fun onSignalingChange(newState: SignalingState) {
                    Log.d(TAG, "Signaling state: $newState")
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                    Log.d(TAG, "ICE candidates removed: ${candidates.size}")
                }

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    Log.d(TAG, "Track added via onAddTrack")
                    val track = receiver.track()
                    if (track is VideoTrack) {
                        Log.d(TAG, "Video track received via onAddTrack")
                        handleVideoTrack(track)
                    }
                }
            }
        )

        Log.d(TAG, "Peer connection created")
    }

    /**
     * Handle received video track - add to renderer
     */
    private fun handleVideoTrack(videoTrack: VideoTrack) {
        coroutineScope.launch {
            try {
                videoTrack.setEnabled(true)
                surfaceViewRenderer?.let { renderer ->
                    videoTrack.addSink(renderer)
                    Log.d(TAG, "Video track added to renderer")
                }
                callback.onVideoTrackReceived(videoTrack)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling video track", e)
            }
        }
    }

    /**
     * Add ICE candidate received from phone
     */
    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        Log.d(TAG, "Adding ICE candidate")
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return peerConnection?.connectionState() == PeerConnectionState.CONNECTED
    }

    /**
     * Cleanup and release all WebRTC resources
     */
    fun close() {
        Log.d(TAG, "Closing WebRTC client")

        try {
            // Clear renderer
            surfaceViewRenderer?.release()
            surfaceViewRenderer = null

            // Close peer connection
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null

            // Dispose factory
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            // Release EGL context
            eglBase?.release()
            eglBase = null

            Log.d(TAG, "WebRTC client closed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebRTC client", e)
        }
    }
}
