package com.fitnessmirror.tv.network

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Discovers FitnessMirror phone app on the local network via UDP broadcast.
 * The phone broadcasts its presence on port 8081.
 */
class PhoneDiscovery(private val listener: DiscoveryListener) {

    companion object {
        private const val TAG = "PhoneDiscovery"
        private const val DISCOVERY_PORT = 8081
        private const val BUFFER_SIZE = 1024
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val DISCOVERY_TYPE = "FITNESS_MIRROR_DISCOVERY"
    }

    interface DiscoveryListener {
        fun onPhoneFound(ip: String, port: Int, name: String)
        fun onDiscoveryError(error: String)
    }

    data class PhoneInfo(
        val ip: String,
        val port: Int,
        val name: String,
        val lastSeen: Long = System.currentTimeMillis()
    )

    private var isListening = false
    private var socket: DatagramSocket? = null
    private var listenerThread: Thread? = null
    private val discoveredPhones = mutableMapOf<String, PhoneInfo>()

    fun startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening for phones")
            return
        }

        isListening = true
        listenerThread = thread(name = "PhoneDiscovery") {
            try {
                socket = DatagramSocket(DISCOVERY_PORT).apply {
                    soTimeout = SOCKET_TIMEOUT_MS
                    reuseAddress = true
                }

                Log.i(TAG, "Started listening for phone broadcasts on port $DISCOVERY_PORT")

                val buffer = ByteArray(BUFFER_SIZE)

                while (isListening) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        val message = String(packet.data, 0, packet.length)
                        handleDiscoveryMessage(message, packet.address.hostAddress ?: "")
                    } catch (e: SocketTimeoutException) {
                        // Timeout is expected, continue listening
                    }
                }
            } catch (e: Exception) {
                if (isListening) {
                    Log.e(TAG, "Discovery error: ${e.message}")
                    listener.onDiscoveryError(e.message ?: "Unknown error")
                }
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    private fun handleDiscoveryMessage(message: String, senderIp: String) {
        try {
            val json = JSONObject(message)

            if (json.optString("type") != DISCOVERY_TYPE) {
                return
            }

            val ip = json.getString("ip")
            val port = json.getInt("port")
            val name = json.optString("name", "Unknown Phone")

            Log.i(TAG, "Discovered phone: $name at $ip:$port (sender: $senderIp)")

            val phoneInfo = PhoneInfo(ip, port, name)
            val isNewPhone = !discoveredPhones.containsKey(ip)
            discoveredPhones[ip] = phoneInfo

            if (isNewPhone) {
                listener.onPhoneFound(ip, port, name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid discovery message: ${e.message}")
        }
    }

    fun stopListening() {
        Log.i(TAG, "Stopping phone discovery")
        isListening = false
        socket?.close()
        listenerThread?.interrupt()
        listenerThread = null
        discoveredPhones.clear()
    }

    fun getDiscoveredPhones(): List<PhoneInfo> {
        return discoveredPhones.values.toList()
    }

    fun isActive(): Boolean = isListening
}
