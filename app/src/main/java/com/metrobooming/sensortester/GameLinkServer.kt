package com.metrobooming.sensortester

import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A minimal local-loopback data feed for a companion game running on the
 * same device (see the "手机游戏联动" section in README.md).
 *
 * Broadcasts one JSON line per recording tick -- train state, player
 * activity, and the raw intensity signals a game might want for difficulty
 * -- to every connected client. Deliberately dumb on purpose: no
 * handshake, no WebSocket framing, no auth (loopback-only, nothing else on
 * the device can reach it without root). Any client that can open a TCP
 * socket and read lines -- Godot's StreamPeerTCP included -- can consume
 * this directly without an Android-specific plugin.
 *
 * Best-effort and fire-and-forget: a slow, absent, or crashed client never
 * blocks or slows down sensor collection. broadcastState() is called from
 * RecordingService's main-thread tick loop, so it only builds the JSON
 * payload inline and hands the actual socket write off to a background
 * executor -- Android throws NetworkOnMainThreadException on any socket I/O
 * (including a write on an already-open connection) attempted from the main
 * thread.
 */
class GameLinkServer {
    companion object {
        const val PORT = 8765
        const val PROTOCOL_VERSION = 1
        private const val TAG = "GameLink"
    }

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private val clients = mutableListOf<Socket>()
    private val writers = mutableListOf<OutputStream>()

    // broadcastState() is called from RecordingService's main-thread tick loop.
    // Android throws NetworkOnMainThreadException on ANY socket I/O from the
    // main thread, including a write to an already-open connection -- so the
    // actual write has to happen on this background thread, not inline.
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "metro-game-link-io").apply { isDaemon = true }
    }

    /** Starts listening on 127.0.0.1:PORT. Safe to call more than once. */
    fun start() {
        synchronized(lock) {
            if (serverSocket != null) return
            serverSocket = try {
                // Bind explicitly to the IPv4 loopback literal. getLoopbackAddress()
                // can resolve to the IPv6 loopback (::1) on some devices, which a
                // client dialing the literal "127.0.0.1" (like Godot's
                // StreamPeerTCP.connect_to_host) can never reach -- the OS returns
                // an instant connection-refused RST for that exact address:port,
                // even though a server actually is listening one address family over.
                ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
            } catch (e: Exception) {
                // Port already taken by another instance, or sockets unavailable --
                // the game link is a nice-to-have, never fail recording over it.
                Log.w(TAG, "failed to bind 127.0.0.1:$PORT", e)
                return
            }
        }
        Log.i(TAG, "listening on 127.0.0.1:$PORT")
        thread(name = "metro-game-link", isDaemon = true) { acceptLoop() }
    }

    /** Stops listening and disconnects every client. Safe to call more than once. */
    fun stop() {
        val socketToClose: ServerSocket?
        val clientsToClose: List<Socket>
        synchronized(lock) {
            socketToClose = serverSocket
            serverSocket = null
            clientsToClose = clients.toList()
            clients.clear()
            writers.clear()
        }
        if (socketToClose != null) Log.i(TAG, "stopping, dropping ${clientsToClose.size} client(s)")
        clientsToClose.forEach(::closeQuietly)
        try {
            socketToClose?.close()
        } catch (_: Exception) {
        }
        ioExecutor.shutdownNow()
    }

    /**
     * Sends the current state to every connected client as one line of JSON.
     * A no-op when nobody is connected. See README.md for the field list.
     */
    fun broadcastState(
        elapsedMs: Long,
        trainState: String,
        trainMoving: Boolean,
        playerActive: Boolean,
        micLevelRatio: Double,
        accelRms: Double,
        gyroRmsDegS: Double,
    ) {
        // Safe to check on the caller's (main) thread -- only guards an early
        // return, no I/O happens here.
        val hasClients = synchronized(lock) { writers.isNotEmpty() }
        if (!hasClients) return

        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("t", elapsedMs)
            put("state", trainState)
            put("train_moving", trainMoving)
            put("player_active", playerActive)
            put("mic_level_ratio", micLevelRatio)
            put("accel_rms", accelRms)
            put("gyro_rms_deg_s", gyroRmsDegS)
        }
        val line = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
        // The actual socket write is the network I/O part -- runs on ioExecutor,
        // never on the caller's thread.
        try {
            ioExecutor.execute { writeToAllClients(line) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor was shut down (service stopping mid-tick); drop the frame.
        }
    }

    private fun writeToAllClients(line: ByteArray) {
        val currentWriters: List<OutputStream>
        synchronized(lock) {
            if (writers.isEmpty()) return
            currentWriters = writers.toList()
        }
        val deadWriters = mutableListOf<OutputStream>()
        currentWriters.forEach { writer ->
            try {
                writer.write(line)
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "write failed, dropping client: ${e.javaClass.simpleName}: ${e.message}")
                deadWriters.add(writer)
            }
        }
        if (deadWriters.isNotEmpty()) dropClients(deadWriters)
    }

    private fun dropClients(deadWriters: List<OutputStream>) {
        synchronized(lock) {
            val dead = deadWriters.toSet()
            val survivingClients = mutableListOf<Socket>()
            val survivingWriters = mutableListOf<OutputStream>()
            for (i in clients.indices) {
                if (writers[i] in dead) {
                    closeQuietly(clients[i])
                } else {
                    survivingClients.add(clients[i])
                    survivingWriters.add(writers[i])
                }
            }
            clients.clear()
            clients.addAll(survivingClients)
            writers.clear()
            writers.addAll(survivingWriters)
        }
    }

    private fun acceptLoop() {
        while (true) {
            val currentServer = synchronized(lock) { serverSocket } ?: break
            val socket = try {
                currentServer.accept()
            } catch (e: Exception) {
                Log.i(TAG, "accept loop ending: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
            Log.i(TAG, "client connected: ${socket.remoteSocketAddress}")
            synchronized(lock) {
                if (serverSocket == null) {
                    closeQuietly(socket)
                } else {
                    clients.add(socket)
                    writers.add(socket.getOutputStream())
                }
            }
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
