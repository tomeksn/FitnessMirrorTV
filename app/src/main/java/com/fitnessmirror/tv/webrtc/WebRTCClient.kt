package com.fitnessmirror.tv.webrtc

import android.content.Context
import android.media.MediaCodecInfo
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

    /**
     * Data class holding WebRTC statistics for display
     */
    data class WebRTCStats(
        val rttMs: Double?,
        val jitterMs: Double?,
        val packetsLost: Long?,
        val framesDecoded: Long?,
        val framesDropped: Long?,
        val framesPerSecond: Double?
    )

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var surfaceViewRenderer: SurfaceViewRenderer? = null

    // Buffer ICE candidates received before peer connection is created
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

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
            // TV only receives video from phone, doesn't send - minimal encoder config

            // Custom predicate - accept ALL hardware decoders (bypass WebRTC whitelist)
            // WebRTC by default only allows: OMX.qcom., OMX.Exynos., OMX.Intel., OMX.MTK.
            // BeyondTV and other TVs may use: OMX.amlogic., OMX.rk., OMX.hisi., c2.xxx
            val codecAllowedPredicate = object : Predicate<MediaCodecInfo> {
                override fun test(codecInfo: MediaCodecInfo): Boolean {
                    val name = codecInfo.name
                    val isSoftware = name.startsWith("OMX.google.") || name.startsWith("c2.android.")
                    Log.d(TAG, "Codec predicate check: $name -> hardware=${!isSoftware}")
                    return !isSoftware  // Accept all hardware decoders, reject software
                }
            }

            // Hardware decoder factory with permissive predicate
            val hardwareDecoderFactory = HardwareVideoDecoderFactory(
                eglBase!!.eglBaseContext,
                codecAllowedPredicate
            )

            // Software decoder factory as fallback
            val softwareDecoderFactory = SoftwareVideoDecoderFactory()

            // Combined factory: try hardware first, fall back to software
            val decoderFactory = object : VideoDecoderFactory {
                override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
                    Log.d(TAG, "Creating decoder for codec: ${codecInfo.name}")
                    val hwDecoder = hardwareDecoderFactory.createDecoder(codecInfo)
                    if (hwDecoder != null) {
                        Log.d(TAG, "Using hardware decoder for ${codecInfo.name}")
                        return hwDecoder
                    }
                    val swDecoder = softwareDecoderFactory.createDecoder(codecInfo)
                    if (swDecoder != null) {
                        Log.d(TAG, "Using software decoder for ${codecInfo.name}")
                        return swDecoder
                    }
                    Log.w(TAG, "No decoder available for ${codecInfo.name}")
                    return null
                }

                override fun getSupportedCodecs(): Array<VideoCodecInfo> {
                    val hwCodecs = hardwareDecoderFactory.supportedCodecs
                    val swCodecs = softwareDecoderFactory.supportedCodecs
                    Log.d(TAG, "Hardware codecs available: ${hwCodecs.map { it.name }}")
                    Log.d(TAG, "Software codecs available: ${swCodecs.map { it.name }}")
                    // Combine both, hardware first for priority
                    return hwCodecs + swCodecs
                }
            }

            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase!!.eglBaseContext,
                false,  // Disable VP8 encoder (not supported on this TV and not needed for receiving)
                false   // Disable H264 High Profile - Realtek only supports Baseline
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
     * Filter VP8 codec from SDP to force H.264 usage
     * H.264 has better hardware support on TVs
     */
    private fun filterVp8FromSdp(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val filteredLines = mutableListOf<String>()
        var vp8PayloadType: String? = null

        // First pass: find VP8 payload type
        for (line in lines) {
            if (line.contains("a=rtpmap:") && line.contains("VP8/90000")) {
                val match = Regex("a=rtpmap:(\\d+) VP8").find(line)
                vp8PayloadType = match?.groupValues?.get(1)
                Log.d(TAG, "Found VP8 payload type to filter: $vp8PayloadType")
                break
            }
        }

        if (vp8PayloadType == null) {
            Log.d(TAG, "No VP8 codec found in SDP, returning unchanged")
            return sdp
        }

        // Second pass: filter out VP8 lines
        for (line in lines) {
            val skipLine = (
                line.contains("a=rtpmap:$vp8PayloadType ") ||
                line.contains("a=rtcp-fb:$vp8PayloadType ") ||
                line.contains("a=fmtp:$vp8PayloadType ")
            )

            if (!skipLine) {
                if (line.startsWith("m=video") && vp8PayloadType != null) {
                    val filtered = line.replace(" $vp8PayloadType", "")
                    filteredLines.add(filtered)
                } else {
                    filteredLines.add(line)
                }
            }
        }

        val result = filteredLines.joinToString("\r\n")
        Log.d(TAG, "SDP filtered - removed VP8 codec, forcing H.264")
        return result
    }

    /**
     * Filter AV1 codec from SDP to force VP9/H.264 usage
     * AV1 has no hardware decoder on TV (only slow software decoder)
     */
    private fun filterAv1FromSdp(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val filteredLines = mutableListOf<String>()
        var av1PayloadType: String? = null

        // First pass: find AV1 payload type
        for (line in lines) {
            if (line.contains("a=rtpmap:") && line.contains("AV1/90000")) {
                val match = Regex("a=rtpmap:(\\d+) AV1").find(line)
                av1PayloadType = match?.groupValues?.get(1)
                Log.d(TAG, "Found AV1 payload type to filter: $av1PayloadType")
                break
            }
        }

        if (av1PayloadType == null) {
            Log.d(TAG, "No AV1 codec found in SDP, returning unchanged")
            return sdp
        }

        // Second pass: filter out AV1 lines
        for (line in lines) {
            val skipLine = (
                line.contains("a=rtpmap:$av1PayloadType ") ||
                line.contains("a=rtcp-fb:$av1PayloadType ") ||
                line.contains("a=fmtp:$av1PayloadType ")
            )

            if (!skipLine) {
                if (line.startsWith("m=video") && av1PayloadType != null) {
                    val filtered = line.replace(" $av1PayloadType", "")
                    filteredLines.add(filtered)
                } else {
                    filteredLines.add(line)
                }
            }
        }

        val result = filteredLines.joinToString("\r\n")
        Log.d(TAG, "SDP filtered - removed AV1 codec, forcing VP9/H.264")
        return result
    }

    /**
     * Filter VP9 codec from SDP to force H.264 Baseline only
     * VP9 hardware decoding on budget Realtek TVs is often buggy or software-only
     */
    private fun filterVp9FromSdp(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val filteredLines = mutableListOf<String>()
        var vp9PayloadType: String? = null

        // First pass: find VP9 payload type
        for (line in lines) {
            if (line.contains("a=rtpmap:") && line.contains("VP9/90000")) {
                val match = Regex("a=rtpmap:(\\d+) VP9").find(line)
                vp9PayloadType = match?.groupValues?.get(1)
                Log.d(TAG, "Found VP9 payload type to filter: $vp9PayloadType")
                break
            }
        }

        if (vp9PayloadType == null) {
            Log.d(TAG, "No VP9 codec found in SDP, returning unchanged")
            return sdp
        }

        // Second pass: filter out VP9 lines
        for (line in lines) {
            val skipLine = (
                line.contains("a=rtpmap:$vp9PayloadType ") ||
                line.contains("a=rtcp-fb:$vp9PayloadType ") ||
                line.contains("a=fmtp:$vp9PayloadType ")
            )

            if (!skipLine) {
                if (line.startsWith("m=video") && vp9PayloadType != null) {
                    val filtered = line.replace(" $vp9PayloadType", "")
                    filteredLines.add(filtered)
                } else {
                    filteredLines.add(line)
                }
            }
        }

        val result = filteredLines.joinToString("\r\n")
        Log.d(TAG, "SDP filtered - removed VP9 codec, forcing H.264 Baseline only")
        return result
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
                    // Drain any ICE candidates that arrived before peer connection was ready
                    drainPendingIceCandidates()
                }

                // Filter VP8, AV1, VP9 from received offer - force H.264 Baseline only
                // VP8: less efficient than H.264
                // AV1: TV has only software decoder (very slow, causes high latency)
                // VP9: Realtek HW decoding is buggy, causes jitter
                val filteredVp8 = filterVp8FromSdp(sdp)
                val filteredAv1 = filterAv1FromSdp(filteredVp8)
                val filteredSdp = filterVp9FromSdp(filteredAv1)

                // Set remote description (offer) with filtered SDP
                val offer = SessionDescription(SessionDescription.Type.OFFER, filteredSdp)
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

        // LAN-only ICE servers - no TURN needed for local network streaming
        // TURN servers removed - they cause connection instability on LAN
        // by replacing working direct host candidates with slow relay paths
        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        // RTCConfiguration
        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = SdpSemantics.UNIFIED_PLAN
            // GATHER_ONCE: Stop gathering after initial candidates found
            // Prevents late TURN candidates from disrupting working connection
            continualGatheringPolicy = ContinualGatheringPolicy.GATHER_ONCE
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
     * Add ICE candidate received from phone.
     * Buffers candidates if peer connection doesn't exist yet (race condition fix).
     */
    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        val pc = peerConnection
        if (pc != null) {
            pc.addIceCandidate(iceCandidate)
            Log.d(TAG, "Added ICE candidate directly")
        } else {
            synchronized(pendingIceCandidates) {
                pendingIceCandidates.add(iceCandidate)
            }
            Log.d(TAG, "Buffered ICE candidate (peer connection not ready, buffered=${pendingIceCandidates.size})")
        }
    }

    private fun drainPendingIceCandidates() {
        synchronized(pendingIceCandidates) {
            if (pendingIceCandidates.isNotEmpty()) {
                Log.i(TAG, "Draining ${pendingIceCandidates.size} buffered ICE candidates")
                for (candidate in pendingIceCandidates) {
                    peerConnection?.addIceCandidate(candidate)
                }
                pendingIceCandidates.clear()
            }
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return peerConnection?.connectionState() == PeerConnectionState.CONNECTED
    }

    /**
     * Get current WebRTC connection statistics
     * Uses the PeerConnection.getStats() API to retrieve real-time metrics
     *
     * @param callback Called with stats when available, null if no connection
     */
    fun getStats(callback: (WebRTCStats?) -> Unit) {
        val pc = peerConnection
        if (pc == null || pc.connectionState() != PeerConnectionState.CONNECTED) {
            callback(null)
            return
        }

        pc.getStats { report ->
            var rttMs: Double? = null
            var jitterMs: Double? = null
            var packetsLost: Long? = null
            var framesDecoded: Long? = null
            var framesDropped: Long? = null
            var framesPerSecond: Double? = null

            for (stats in report.statsMap.values) {
                when (stats.type) {
                    "candidate-pair" -> {
                        val members = stats.members
                        if (members["nominated"] as? Boolean == true) {
                            rttMs = (members["currentRoundTripTime"] as? Double)?.times(1000)
                        }
                    }
                    "inbound-rtp" -> {
                        val members = stats.members
                        if (members["kind"] as? String == "video") {
                            jitterMs = (members["jitter"] as? Double)?.times(1000)
                            packetsLost = (members["packetsLost"] as? Number)?.toLong()
                            framesDecoded = (members["framesDecoded"] as? Number)?.toLong()
                            framesDropped = (members["framesDropped"] as? Number)?.toLong()
                            framesPerSecond = members["framesPerSecond"] as? Double
                        }
                    }
                }
            }

            callback(WebRTCStats(rttMs, jitterMs, packetsLost, framesDecoded, framesDropped, framesPerSecond))
        }
    }

    /**
     * Cleanup and release all WebRTC resources
     */
    fun close() {
        Log.d(TAG, "Closing WebRTC client")

        try {
            // Clear pending candidates
            synchronized(pendingIceCandidates) {
                pendingIceCandidates.clear()
            }

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
