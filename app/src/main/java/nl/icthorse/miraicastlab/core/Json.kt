package nl.icthorse.miraicastlab.core

/**
 * Minimal JSON writer.
 *
 * Deliberately dependency-free: the app must build and run completely offline (spec section 4),
 * and pulling in a serialisation library for six field types is not worth the build surface.
 */
object Json {

    /** Escapes a string for inclusion in a JSON document, including control characters. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    fun str(s: String): String = "\"" + escape(s) + "\""

    /** Renders a flat map as a JSON object. */
    fun obj(map: Map<String, String>): String =
        map.entries.joinToString(",", "{", "}") { str(it.key) + ":" + str(it.value) }

    /** Renders one [LogRecord] exactly in the shape mandated by spec section 13. */
    fun record(r: LogRecord): String = buildString {
        append("{")
        append(str("timestamp")).append(":").append(str(r.timestamp)).append(",")
        append(str("elapsedRealtimeMs")).append(":").append(r.elapsedRealtimeMs).append(",")
        append(str("testRunId")).append(":").append(str(r.testRunId)).append(",")
        append(str("category")).append(":").append(str(r.category.name)).append(",")
        append(str("event")).append(":").append(str(r.event)).append(",")
        append(str("status")).append(":").append(str(r.status.name)).append(",")
        append(str("details")).append(":").append(obj(r.details))
        append("}")
    }

    /** Renders a value for CSV, quoting and doubling embedded quotes. */
    fun csvCell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
}
