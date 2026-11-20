package com.nirkzxc.nfcreader

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val TAG = "NFC_READER"
        private const val PORT = 18765
        private const val ACCEPT_TIMEOUT_MS = 5000
        private const val SOCKET_TIMEOUT_MS = 30000
        private const val MAX_AUTH_ATTEMPTS = 3

        // Auth token is injected via BuildConfig (from -PnfcAuthToken or NFC_AUTH_TOKEN).
        // No default is shipped: a blank token means no client can authenticate.
    }

    private lateinit var nfcAdapter: NfcAdapter

    @Volatile
    private var writeMode = false

    @Volatile
    private var writeType = ""

    @Volatile
    private var writeData = ""

    // Guard all mutable socket state with this lock.
    private val socketLock = Any()
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var clientSocket: Socket? = null
    @Volatile
    private var writer: PrintWriter? = null

    private var acceptThread: Thread? = null
    @Volatile
    private var isServerRunning = false

    // Single-threaded executor for outgoing JSON sends — avoids unbounded thread creation.
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NFCReader-Send").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.e(TAG, "NFC adapter not available on this device — NFC reading disabled")
        }

        startServer()
    }

    private fun startServer() {
        isServerRunning = true
        acceptThread = thread(isDaemon = true, name = "NFCReader-TCP-Accept") {
            try {
                // Bind to loopback only — port is NOT exposed to Wi-Fi/LAN.
                serverSocket = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket?.soTimeout = ACCEPT_TIMEOUT_MS
                Log.i(TAG, "TCP SERVER STARTED: $PORT (loopback only)")

                while (isServerRunning && !serverSocket!!.isClosed) {
                    try {
                        val socket = serverSocket!!.accept()
                        socket.soTimeout = SOCKET_TIMEOUT_MS
                        socket.tcpNoDelay = true

                        synchronized(socketLock) {
                            clientSocket?.close()
                            clientSocket = socket
                            writer = PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
                        }

                        Log.i(TAG, "PC CONNECTED")

                        thread(isDaemon = true, name = "NFCReader-Command-Reader") {
                            readCommands(socket)
                        }.start()
                    } catch (e: java.net.SocketTimeoutException) {
                        // accept() timeout — loop back and re-check isServerRunning
                        continue
                    } catch (e: Exception) {
                        if (isServerRunning) {
                            Log.e(TAG, "SERVER ACCEPT ERROR", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SERVER START FAILED", e)
            } finally {
                isServerRunning = false
            }
        }
    }

    private fun readCommands(socket: Socket) {
        var reader: BufferedReader? = null
        var authenticated = false
        var authAttempts = 0

        try {
            reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), "UTF-8")
            )

            while (!socket.isClosed && isServerRunning) {
                val command = reader.readLine() ?: break

                if (command.isBlank()) continue

                Log.i(TAG, "COMMAND: $command")

                if (!authenticated) {
                    if (command.startsWith("AUTH|")) {
                        val token = command.substringAfter("AUTH|")
                        val expectedToken = BuildConfig.AUTH_TOKEN
                        if (expectedToken.isBlank()) {
                            Log.w(TAG, "AUTH_TOKEN not configured — rejecting authentication")
                            sendJson(
                                JSONObject()
                                    .put("event", "auth_failed")
                                    .put("reason", "not_configured")
                                    .toString()
                            )
                            break
                        }
                        if (token == expectedToken) {
                            authenticated = true
                            sendJson(
                                JSONObject()
                                    .put("event", "auth_ok")
                                    .toString()
                            )
                        } else {
                            authAttempts++
                            Log.w(TAG, "AUTH FAILED (attempt $authAttempts)")
                            sendJson(
                                JSONObject()
                                    .put("event", "auth_failed")
                                    .put("reason", "invalid_token")
                                    .toString()
                            )
                            if (authAttempts >= MAX_AUTH_ATTEMPTS) {
                                Log.w(TAG, "Too many auth failures — closing connection")
                                break
                            }
                        }
                    } else {
                        sendJson(
                            JSONObject()
                                .put("event", "auth_required")
                                .toString()
                        )
                    }
                    continue
                }

                when {
                    command == "SCAN" -> {
                        sendJson(
                            JSONObject()
                                .put("event", "scan_ready")
                                .toString()
                        )
                    }

                    command.startsWith("WRITE_TEXT|") -> {
                        val text = command.substringAfter("WRITE_TEXT|")
                        if (text.isBlank()) {
                            sendJson(
                                JSONObject()
                                    .put("event", "write_result")
                                    .put("result", "ERROR: empty text")
                                    .toString()
                            )
                            continue
                        }
                        writeType = "text"
                        writeData = text
                        writeMode = true

                        sendJson(
                            JSONObject()
                                .put("event", "write_ready")
                                .put("type", "text")
                                .put("data", text)
                                .toString()
                        )
                    }

                    command.startsWith("WRITE_URI|") -> {
                        val uri = command.substringAfter("WRITE_URI|")
                        if (uri.isBlank()) {
                            sendJson(
                                JSONObject()
                                    .put("event", "write_result")
                                    .put("result", "ERROR: empty uri")
                                    .toString()
                            )
                            continue
                        }
                        if (!Patterns.WEB_URL.matcher(uri).matches()) {
                            sendJson(
                                JSONObject()
                                    .put("event", "write_result")
                                    .put("result", "ERROR: invalid uri")
                                    .toString()
                            )
                            continue
                        }
                        writeType = "uri"
                        writeData = uri
                        writeMode = true

                        sendJson(
                            JSONObject()
                                .put("event", "write_ready")
                                .put("type", "uri")
                                .put("data", uri)
                                .toString()
                        )
                    }

                    command == "CANCEL_WRITE" -> {
                        writeMode = false
                        writeType = ""
                        writeData = ""

                        sendJson(
                            JSONObject()
                                .put("event", "write_cancelled")
                                .toString()
                        )
                    }

                    command == "PING" -> {
                        sendJson(
                            JSONObject()
                                .put("event", "pong")
                                .toString()
                        )
                    }

                    else -> {
                        Log.w(TAG, "Unknown command: $command")
                    }
                }
            }
        } catch (e: Exception) {
            if (isServerRunning) {
                Log.e(TAG, "COMMAND ERROR", e)
            }
        } finally {
            reader?.close()
            synchronized(socketLock) {
                if (clientSocket === socket) {
                    clientSocket = null
                    writer = null
                }
            }
            try { socket.close() } catch (_: Exception) {}
            Log.i(TAG, "PC DISCONNECTED")
        }
    }

    override fun onResume() {
        super.onResume()

        nfcAdapter?.let {
            it.enableReaderMode(
                this,
                { tag ->
                    processTag(tag)
                },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V,
                null
            )
        } ?: run {
            Log.w(TAG, "NFC adapter unavailable — NFC reading disabled")
        }
    }

    override fun onPause() {
        super.onPause()

        nfcAdapter?.disableReaderMode(this)
    }

    private fun processTag(tag: Tag) {

        if (writeMode) {
            val result = writeToTag(tag)

            sendJson(
                JSONObject()
                    .put("event", "write_result")
                    .put("result", result)
                    .toString()
            )

            if (result.startsWith("OK")) {
                writeMode = false
                writeType = ""
                writeData = ""
            }

            return
        }

        val obj = JSONObject()

        obj.put(
            "event",
            "scan"
        )

        obj.put(
            "uid",
            tag.id.joinToString("") {
                "%02X".format(it)
            }
        )

        val technologies = JSONArray()

        tag.techList.forEach {
            technologies.put(it)
        }

        obj.put(
            "technologies",
            technologies
        )

        val ndef = Ndef.get(tag)

        if (ndef != null) {

            try {
                ndef.connect()

                obj.put("type", ndef.type)
                obj.put("maxSize", ndef.maxSize)
                obj.put("writable", ndef.isWritable)

                // Use getNdefMessage() after connect() for the current message.
                val message = ndef.getNdefMessage()

                if (message != null) {
                    obj.put(
                        "ndefRecords",
                        parseNdef(message)
                    )
                }

                ndef.close()

            } catch (e: Exception) {

                obj.put(
                    "ndefError",
                    e.javaClass.simpleName
                )

                try {
                    ndef.close()
                } catch (_: Exception) {}
            }
        }

        sendJson(obj)
    }

    private fun writeToTag(tag: Tag): String {

        val ndef = Ndef.get(tag)
            ?: return "ERROR: tag does not support NDEF"

        try {

            ndef.connect()

            if (!ndef.isWritable) {
                return "ERROR: tag is read-only"
            }

            val record: NdefRecord

            when (writeType) {

                "text" -> {
                    record = NdefRecord.createTextRecord(
                        "en",
                        writeData
                    )
                }

                "uri" -> {
                    record = NdefRecord.createUri(
                        writeData
                    )
                }

                else -> {
                    return "ERROR: unknown write type"
                }
            }

            val message = NdefMessage(
                arrayOf(record)
            )

            val bytes = message.toByteArray()

            if (bytes.size > ndef.maxSize) {
                return "ERROR: data too large (${bytes.size}/${ndef.maxSize})"
            }

            ndef.writeNdefMessage(message)

            return "OK: written ${bytes.size} bytes"

        } catch (e: Exception) {

            return "ERROR: ${e.javaClass.simpleName}: ${e.message}"

        } finally {

            try {
                ndef.close()
            } catch (_: Exception) {}
        }
    }

    private fun parseNdef(message: NdefMessage): JSONArray {

        val array = JSONArray()

        for (record in message.records) {

            val obj = JSONObject()

            obj.put(
                "tnf",
                record.tnf
            )

            obj.put(
                "type",
                String(
                    record.type,
                    StandardCharsets.UTF_8
                )
            )

            obj.put(
                "payloadHex",
                record.payload.joinToString("") {
                    "%02X".format(it)
                }
            )

            try {
                record.toUri()?.let {
                    obj.put(
                        "uri",
                        it.toString()
                    )
                }
            } catch (_: Exception) {}

            array.put(obj)
        }

        return array
    }

    private fun sendJson(obj: JSONObject) {
        sendJson(obj.toString())
    }

    private fun sendJson(json: String) {
        sendExecutor.execute {
            try {
                synchronized(socketLock) {
                    writer?.println(json)
                    writer?.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "SEND ERROR", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        isServerRunning = false

        // Close client socket to unblock any blocked reader threads.
        synchronized(socketLock) {
            try {
                clientSocket?.close()
                clientSocket = null
                writer = null
            } catch (_: Exception) {}
        }

        // Close server socket to unblock accept().
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}

        // Shutdown the send executor.
        sendExecutor.shutdown()

        // Accept thread is daemon — it will exit when the process ends.
    }
}
