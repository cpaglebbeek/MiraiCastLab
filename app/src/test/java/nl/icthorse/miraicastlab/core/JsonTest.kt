package nl.icthorse.miraicastlab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The evidence file is append-only JSONL that has to survive being parsed by something other than
 * this app. Malformed escaping there is silent data loss, so the escaping is tested exhaustively.
 */
class JsonTest {

    @Test
    fun `escapes the characters that would break a json document`() {
        assertEquals("he said \\\"hi\\\"", Json.escape("he said \"hi\""))
        assertEquals("a\\\\b", Json.escape("a\\b"))
        assertEquals("a\\nb", Json.escape("a\nb"))
        assertEquals("a\\rb", Json.escape("a\rb"))
        assertEquals("a\\tb", Json.escape("a\tb"))
        assertEquals("a\\bb", Json.escape("a\bb"))
        assertEquals("a\\fb", Json.escape("a\u000Cb"))
    }

    @Test
    fun `escapes other control characters as unicode`() {
        // A dumpsys value or a device name can carry a stray control byte; it must not end the
        // string early in whatever parses the evidence file later.
        assertEquals("a\\u0000b", Json.escape("a\u0000b"))
        assertEquals("a\\u001fb", Json.escape("a\u001Fb"))
    }

    @Test
    fun `leaves ordinary and non-ascii text untouched`() {
        assertEquals("Galaxy Z Fold 6", Json.escape("Galaxy Z Fold 6"))
        assertEquals("Mirai éü", Json.escape("Mirai éü"))
    }

    @Test
    fun `record emits exactly the seven fields the spec mandates`() {
        val r = LogRecord(
            timestamp = "2026-08-30T12:00:00Z",
            elapsedRealtimeMs = 1234,
            testRunId = "run-1",
            category = LabCategory.MIRACAST,
            event = "display_added",
            status = LabStatus.OBSERVED,
            details = mapOf("displayId" to "2"),
        )
        val json = Json.record(r)
        listOf("timestamp", "elapsedRealtimeMs", "testRunId", "category", "event", "status", "details")
            .forEach { assertTrue("missing field " + it, json.contains("\"" + it + "\"")) }
        assertTrue(json.contains("\"MIRACAST\""))
        assertTrue(json.contains("\"OBSERVED\""))
        assertTrue(json.contains("\"elapsedRealtimeMs\":1234"))
        assertTrue(json.startsWith("{") && json.endsWith("}"))
    }

    @Test
    fun `record keeps a quote inside a detail value from breaking the line`() {
        val r = LogRecord(
            "t", 0, "run", LabCategory.INPUT, "ev", LabStatus.ERROR,
            mapOf("msg" to "unexpected \" quote"),
        )
        val json = Json.record(r)
        assertTrue(json.contains("unexpected \\\" quote"))
        // One line, always: JSONL depends on it.
        assertEquals(1, json.lines().size)
    }

    @Test
    fun `a newline inside a detail value cannot split a jsonl record`() {
        val r = LogRecord(
            "t", 0, "run", LabCategory.DISPLAY, "ev", LabStatus.OBSERVED,
            mapOf("dump" to "line1\nline2"),
        )
        assertEquals(1, Json.record(r).lines().size)
    }

    @Test
    fun `csv cell quotes only when it has to and doubles embedded quotes`() {
        assertEquals("plain", Json.csvCell("plain"))
        assertEquals("\"a,b\"", Json.csvCell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Json.csvCell("say \"hi\""))
        assertEquals("\"line1\nline2\"", Json.csvCell("line1\nline2"))
    }

    @Test
    fun `empty details map renders as an empty object`() {
        assertEquals("{}", Json.obj(emptyMap()))
    }
}
