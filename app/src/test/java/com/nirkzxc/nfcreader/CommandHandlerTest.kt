package com.nirkzxc.nfcreader

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class CommandHandlerTest {

    private val handler = CommandHandler()

    @Test
    fun `parse SCAN returns scan_ready`() {
        val result = handler.parse("SCAN")
        assertEquals("scan_ready", result.event)
        assertNull(result.state)
    }

    @Test
    fun `parse WRITE_TEXT with valid text returns write_ready and state`() {
        val result = handler.parse("WRITE_TEXT|hello")
        assertEquals("write_ready", result.event)
        assertEquals("text", result.extra["type"])
        assertEquals("hello", result.extra["data"])
        assertTrue(result.state is WriteState)
        assertEquals("text", (result.state as WriteState).type)
        assertEquals("hello", (result.state as WriteState).data)
    }

    @Test
    fun `parse WRITE_TEXT with empty text returns error`() {
        val result = handler.parse("WRITE_TEXT|")
        assertEquals("write_result", result.event)
        assertEquals("ERROR: empty text", result.extra["result"])
    }

    @Test
    fun `parse WRITE_TEXT with whitespace-only text returns error`() {
        val result = handler.parse("WRITE_TEXT|   ")
        assertEquals("write_result", result.event)
        assertEquals("ERROR: empty text", result.extra["result"])
    }

    @Test
    fun `parse WRITE_URI with valid URI returns write_ready`() {
        val result = handler.parse("WRITE_URI|https://example.com")
        assertEquals("write_ready", result.event)
        assertEquals("uri", result.extra["type"])
        assertEquals("https://example.com", result.extra["data"])
    }

    @Test
    fun `parse WRITE_URI with empty URI returns error`() {
        val result = handler.parse("WRITE_URI|")
        assertEquals("write_result", result.event)
        assertEquals("ERROR: empty uri", result.extra["result"])
    }

    @Test
    fun `parse WRITE_URI with invalid URI returns error`() {
        val result = handler.parse("WRITE_URI|not-a-uri")
        assertEquals("write_result", result.event)
        assertEquals("ERROR: invalid uri", result.extra["result"])
    }

    @Test
    fun `parse CANCEL_WRITE returns write_cancelled`() {
        val result = handler.parse("CANCEL_WRITE")
        assertEquals("write_cancelled", result.event)
        assertTrue(result.state is CancelState)
    }

    @Test
    fun `parse PING returns pong`() {
        val result = handler.parse("PING")
        assertEquals("pong", result.event)
    }

    @Test
    fun `parse AUTH with valid token returns auth_ok`() {
        val result = handler.parse("AUTH|secret-token-123")
        assertEquals("auth_ok", result.event)
        assertTrue(result.state is AuthState)
        assertEquals("secret-token-123", (result.state as AuthState).token)
    }

    @Test
    fun `parse unknown command returns unknown`() {
        val result = handler.parse("FOOBAR")
        assertEquals("unknown", result.event)
    }

    @Test
    fun `parse trims surrounding whitespace from command`() {
        val result = handler.parse("  SCAN  ")
        assertEquals("scan_ready", result.event)
    }

    @Test
    fun `scanEvent produces expected JSON structure`() {
        val records = listOf(
            NdefRecordDto(
                tnf = 1,
                type = "U",
                payloadHex = "5402656e",
                uri = "https://example.com"
            )
        )
        val json = handler.scanEvent(
            uid = "AABBCCDD",
            technologies = listOf("NfcA", "Ndef"),
            ndefType = "NDEF",
            maxSize = 1024,
            writable = true,
            records = records
        )

        assertTrue(json.contains("\"event\":\"scan\""))
        assertTrue(json.contains("\"uid\":\"AABBCCDD\""))
        assertTrue(json.contains("\"type\":\"NDEF\""))
        assertTrue(json.contains("\"maxSize\":1024"))
        assertTrue(json.contains("\"writable\":true"))
        assertTrue(json.contains("\"uri\":\"https://example.com\""))
    }

    @Test
    fun `scanEvent with null optional fields omits them`() {
        val json = handler.scanEvent(
            uid = "00112233",
            technologies = listOf("NfcV"),
            ndefType = null,
            maxSize = null,
            writable = null,
            records = null
        )

        val obj = JSONObject(json)
        assertEquals("00112233", obj.getString("uid"))
        assertFalse(obj.has("type"))
        assertFalse(obj.has("maxSize"))
        assertFalse(obj.has("writable"))
        assertFalse(obj.has("ndefRecords"))
    }

    @Test
    fun `writeResultEvent produces expected JSON`() {
        val json = handler.writeResultEvent("OK: written 42 bytes")
        val obj = JSONObject(json)
        assertEquals("write_result", obj.getString("event"))
        assertEquals("OK: written 42 bytes", obj.getString("result"))
    }

    @Test
    fun `writeCancelledEvent produces expected JSON`() {
        val json = handler.writeCancelledEvent()
        val obj = JSONObject(json)
        assertEquals("write_cancelled", obj.getString("event"))
    }

    @Test
    fun `pongEvent produces expected JSON`() {
        val json = handler.pongEvent()
        val obj = JSONObject(json)
        assertEquals("pong", obj.getString("event"))
    }

    @Test
    fun `authRequiredEvent produces expected JSON`() {
        val json = handler.authRequiredEvent()
        val obj = JSONObject(json)
        assertEquals("auth_required", obj.getString("event"))
    }

    @Test
    fun `authFailedEvent produces expected JSON`() {
        val json = handler.authFailedEvent("invalid_token")
        val obj = JSONObject(json)
        assertEquals("auth_failed", obj.getString("event"))
        assertEquals("invalid_token", obj.getString("reason"))
    }

    @Test
    fun `authOkEvent produces expected JSON`() {
        val json = handler.authOkEvent("my-token")
        val obj = JSONObject(json)
        assertEquals("auth_ok", obj.getString("event"))
    }
}
