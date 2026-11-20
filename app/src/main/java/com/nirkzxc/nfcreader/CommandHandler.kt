package com.nirkzxc.nfcreader

import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses incoming TCP commands from the PC and produces the corresponding
 * JSON response event(s). Pure Kotlin — no Android framework dependencies —
 * so it can be unit-tested on the JVM without a device or emulator.
 */
class CommandHandler {

    // Simple URI scheme pattern for validating WRITE_URI commands.
    private val URI_PATTERN = Pattern.compile("^(https?|ftp|file)://[^\\s/$.?#].[^\\s]*$")

    data class Result(
        val event: String,
        val extra: Map<String, Any?> = emptyMap(),
        val close: Boolean = false
    )

    /**
     * Parses a single command line. Returns the event to send back to the PC
     * and any state changes the caller should apply (writeMode, writeType,
     * writeData).
     */
    fun parse(command: String): ParseResult {
        val trimmed = command.trim()

        return when {
            trimmed.startsWith("AUTH|") -> {
                val token = trimmed.substringAfter("AUTH|")
                ParseResult(
                    event = "auth_ok",
                    extra = mapOf("token" to token),
                    state = AuthState(token)
                )
            }

            trimmed == "SCAN" -> ParseResult(event = "scan_ready")

            trimmed.startsWith("WRITE_TEXT|") -> {
                val text = trimmed.substringAfter("WRITE_TEXT|")
                if (text.isBlank()) {
                    ParseResult(event = "write_result", extra = mapOf("result" to "ERROR: empty text"))
                } else {
                    ParseResult(
                        event = "write_ready",
                        extra = mapOf("type" to "text", "data" to text),
                        state = WriteState("text", text)
                    )
                }
            }

            trimmed.startsWith("WRITE_URI|") -> {
                val uri = trimmed.substringAfter("WRITE_URI|")
                if (uri.isBlank()) {
                    ParseResult(event = "write_result", extra = mapOf("result" to "ERROR: empty uri"))
                } else if (!URI_PATTERN.matcher(uri).matches()) {
                    ParseResult(event = "write_result", extra = mapOf("result" to "ERROR: invalid uri"))
                } else {
                    ParseResult(
                        event = "write_ready",
                        extra = mapOf("type" to "uri", "data" to uri),
                        state = WriteState("uri", uri)
                    )
                }
            }

            trimmed == "CANCEL_WRITE" -> ParseResult(
                event = "write_cancelled",
                state = CancelState
            )

            trimmed == "PING" -> ParseResult(event = "pong")

            else -> ParseResult(event = "unknown")
        }
    }

    /** Builds a JSON string for a scan event given a tag's data. */
    fun scanEvent(
        uid: String,
        technologies: List<String>,
        ndefType: String?,
        maxSize: Int?,
        writable: Boolean?,
        records: List<NdefRecordDto>?
    ): String {
        val obj = JSONObject()
        obj.put("event", "scan")
        obj.put("uid", uid)

        val techArray = JSONArray()
        technologies.forEach { techArray.put(it) }
        obj.put("technologies", techArray)

        ndefType?.let { obj.put("type", it) }
        maxSize?.let { obj.put("maxSize", it) }
        writable?.let { obj.put("writable", it) }

        if (records != null) {
            val recordsArray = JSONArray()
            for (record in records) {
                val recordObj = JSONObject()
                recordObj.put("tnf", record.tnf)
                recordObj.put("type", record.type)
                recordObj.put("payloadHex", record.payloadHex)
                record.uri?.let { recordObj.put("uri", it) }
                recordsArray.put(recordObj)
            }
            obj.put("ndefRecords", recordsArray)
        }

        return obj.toString()
    }

    /** Builds a JSON string for a write_result event. */
    fun writeResultEvent(result: String): String {
        return JSONObject().put("event", "write_result").put("result", result).toString()
    }

    /** Builds a JSON string for a write_cancelled event. */
    fun writeCancelledEvent(): String {
        return JSONObject().put("event", "write_cancelled").toString()
    }

    /** Builds a JSON string for a pong event. */
    fun pongEvent(): String {
        return JSONObject().put("event", "pong").toString()
    }

    /** Builds a JSON string for an auth_required event. */
    fun authRequiredEvent(): String {
        return JSONObject().put("event", "auth_required").toString()
    }

    /** Builds a JSON string for an auth_failed event. */
    fun authFailedEvent(reason: String): String {
        return JSONObject().put("event", "auth_failed").put("reason", reason).toString()
    }

    /** Builds a JSON string for an auth_ok event. */
    fun authOkEvent(token: String): String {
        return JSONObject().put("event", "auth_ok").toString()
    }
}

/** Immutable NDEF record DTO used by CommandHandler.scanEvent. */
data class NdefRecordDto(
    val tnf: Int,
    val type: String,
    val payloadHex: String,
    val uri: String? = null
)

/** Result of command parsing, including optional state change. */
data class ParseResult(
    val event: String,
    val extra: Map<String, Any?> = emptyMap(),
    val state: AppState? = null
)

sealed interface AppState

data class AuthState(val token: String) : AppState

data class WriteState(val type: String, val data: String) : AppState

object CancelState : AppState
