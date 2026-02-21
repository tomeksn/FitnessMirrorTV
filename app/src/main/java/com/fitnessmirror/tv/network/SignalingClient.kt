package com.fitnessmirror.tv.network

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for WebRTC signaling with FitnessMirror phone app.
 * Handles SDP offer/answer exchange and ICE candidate negotiation.
 */
class SignalingClient(
    private val listener: SignalingListener
) {

    companion object {
        private const val TAG = "SignalingClient"
        private const val PING_INTERVAL_SECONDS = 30L
        private const val CONNECTION_TIMEOUT_SECONDS = 10L
        private const val MAX_CONNECT_ATTEMPTS = 3
    }

    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onOfferReceived(sdp: String)
        fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onVideoUrlReceived(videoId: String, currentTime: Float)
        fun onVideoControlReceived(command: String, value: Float?)
        fun onError(error: String)
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private var serverUrl: String = ""
    private var connectAttempt = 0
    private var targetIp: String = ""
    private var targetPort: Int = 0

    fun connect(ip: String, port: Int) {
        targetIp = ip
        targetPort = port
        connectAttempt = 0
        attemptConnect()
    }

    private fun attemptConnect() {
        connectAttempt++
        serverUrl = "ws://$targetIp:$targetPort/stream"
        Log.i(TAG, "Connection attempt $connectAttempt/$MAX_CONNECT_ATTEMPTS to $serverUrl")

        // HTTP pre-check: verify server is reachable before WebSocket
        val checkUrl = "http://$targetIp:$targetPort/api/status"
        Log.d(TAG, "Pre-check: GET $checkUrl")

        val checkRequest = Request.Builder().url(checkUrl).build()
        client.newCall(checkRequest).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.close()
                Log.i(TAG, "Pre-check OK (HTTP ${response.code}) - proceeding with WebSocket")
                connectWebSocket()
            }

            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.w(TAG, "Pre-check failed: ${e.message}")
                if (connectAttempt < MAX_CONNECT_ATTEMPTS) {
                    Log.d(TAG, "Retrying in ${connectAttempt * 2}s...")
                    Thread.sleep(connectAttempt * 2000L)
                    attemptConnect()
                } else {
                    Log.e(TAG, "Server not reachable after $MAX_CONNECT_ATTEMPTS attempts")
                    listener.onError("Serwer nieosiągalny: ${e.message}")
                }
            }
        })
    }

    private fun connectWebSocket() {
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to $serverUrl")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code - $reason")
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                listener.onError(t.message ?: "Connection failed")
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "SDP" -> {
                    val sdpType = json.getString("sdpType")
                    val sdp = json.getString("sdp")
                    Log.i(TAG, "Received SDP: $sdpType")

                    if (sdpType == "offer") {
                        listener.onOfferReceived(sdp)
                    }
                }

                "ICE" -> {
                    val sdpMid = json.getString("sdpMid")
                    val sdpMLineIndex = json.getInt("sdpMLineIndex")
                    val candidate = json.getString("candidate")
                    Log.d(TAG, "Received ICE candidate")
                    listener.onIceCandidateReceived(sdpMid, sdpMLineIndex, candidate)
                }

                "VIDEO_URL" -> {
                    val videoId = json.getString("videoId")
                    val currentTime = json.optDouble("currentTime", 0.0).toFloat()
                    Log.i(TAG, "Received VIDEO_URL: $videoId at $currentTime")
                    listener.onVideoUrlReceived(videoId, currentTime)
                }

                "VIDEO_CONTROL" -> {
                    val command = json.getString("command")
                    val value = if (json.has("value")) json.getDouble("value").toFloat() else null
                    Log.i(TAG, "Received VIDEO_CONTROL: $command")
                    listener.onVideoControlReceived(command, value)
                }

                "TIMESTAMP" -> {
                    // Ignore timestamp messages (used for latency measurement)
                }

                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    fun sendAnswer(sdp: String) {
        val json = JSONObject().apply {
            put("type", "SDP")
            put("sdpType", "answer")
            put("sdp", sdp)
        }
        sendMessage(json.toString())
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val json = JSONObject().apply {
            put("type", "ICE")
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
            put("candidate", candidate)
        }
        sendMessage(json.toString())
    }

    fun sendQualityControl(action: String) {
        val json = JSONObject().apply {
            put("type", "QUALITY_CONTROL")
            put("action", action)  // "decrease" or "increase"
        }
        sendMessage(json.toString())
        Log.d(TAG, "Sent QUALITY_CONTROL: $action")
    }

    private fun sendMessage(message: String) {
        webSocket?.let { ws ->
            val success = ws.send(message)
            if (!success) {
                Log.e(TAG, "Failed to send message")
            }
        } ?: Log.e(TAG, "WebSocket not connected")
    }

    fun disconnect() {
        Log.i(TAG, "Disconnecting WebSocket")
        webSocket?.close(1000, "Client closing")
        webSocket = null
    }

    fun isConnected(): Boolean = webSocket != null
}
