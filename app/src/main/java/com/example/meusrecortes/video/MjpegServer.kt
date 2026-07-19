package com.example.meusrecortes.video

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class MjpegServer {

    companion object {
        private const val TAG = "MjpegServer"
        private const val PORT = 8080
        private const val BOUNDARY = "--jpgboundary"

        fun getWifiIpAddress(context: Context): String {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ipAddress = wifiManager?.connectionInfo?.ipAddress ?: 0
                if (ipAddress != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipAddress and 0xff,
                        ipAddress shr 8 and 0xff,
                        ipAddress shr 16 and 0xff,
                        ipAddress shr 24 and 0xff
                    )
                }
            } catch (_: Exception) {}
            return "192.168.1.X"
        }
    }

    private var serverSocket: ServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Servidor MJPEG iniciado na porta $PORT")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    clientSockets.add(socket)
                    handleClient(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no servidor MJPEG: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}

        for (socket in clientSockets) {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
        clientSockets.clear()
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                val outputStream = socket.getOutputStream()
                val header = ("HTTP/1.0 200 OK\r\n" +
                        "Connection: close\r\n" +
                        "Server: MeusRecortesStreamer/1.0\r\n" +
                        "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n").toByteArray()
                
                outputStream.write(header)
                outputStream.flush()
            } catch (e: Exception) {
                clientSockets.remove(socket)
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    fun pushFrame(jpegBytes: ByteArray) {
        if (!isRunning || clientSockets.isEmpty()) return

        scope.launch {
            val deadSockets = mutableListOf<Socket>()
            for (socket in clientSockets) {
                try {
                    val outputStream: OutputStream = socket.getOutputStream()
                    val frameHeader = ("$BOUNDARY\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${jpegBytes.size}\r\n\r\n").toByteArray()

                    outputStream.write(frameHeader)
                    outputStream.write(jpegBytes)
                    outputStream.write("\r\n".toByteArray())
                    outputStream.flush()
                } catch (e: Exception) {
                    deadSockets.add(socket)
                }
            }
            if (deadSockets.isNotEmpty()) {
                clientSockets.removeAll(deadSockets.toSet())
                for (s in deadSockets) {
                    try { s.close() } catch (_: Exception) {}
                }
            }
        }
    }
}
