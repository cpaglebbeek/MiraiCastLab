package nl.icthorse.miraicastlab.auto

/**
 * A small JSON reader for one job: reading back the experiment matrix this module wrote.
 *
 * The project has no serialisation dependency on purpose (core/Json.kt writes JSON by hand for the
 * same reason). core/Json.kt only writes; the matrix has to survive an app restart, so it also has
 * to be read. The parser is deliberately lenient: a truncated evidence file - the realistic failure
 * after a crash mid-test - yields the rows it managed to read instead of throwing everything away.
 *
 * Numbers, booleans and null are coerced to their text form because every matrix field is stored as
 * a string; nothing in this module needs numeric fidelity from the file.
 */
internal object MiniJson {

    /** Parses a document. Returns null when the text is not usable at all. */
    fun parse(text: String): Any? = try {
        Parser(text).value()
    } catch (t: Throwable) {
        null
    }

    /** Convenience: the value at [key] of a parsed object, as a list of flat string maps. */
    fun objectArray(root: Any?, key: String): List<Map<String, String>> {
        val obj = root as? Map<*, *> ?: return emptyList()
        val arr = obj[key] as? List<*> ?: return emptyList()
        return arr.mapNotNull { element ->
            val m = element as? Map<*, *> ?: return@mapNotNull null
            val pairs = mutableListOf<Pair<String, String>>()
            for (e in m.entries) {
                val k = e.key as? String ?: continue
                pairs.add(k to stringify(e.value))
            }
            pairs.toMap()
        }
    }

    /** Convenience: a top-level string field. */
    fun str(root: Any?, key: String): String? =
        (root as? Map<*, *>)?.get(key)?.let { stringify(it) }

    private fun stringify(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun value(): Any? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> { lit("true"); true }
                'f' -> { lit("false"); false }
                'n' -> { lit("null"); null }
                else -> number()
            }
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun lit(l: String) {
            if (!s.startsWith(l, i)) throw IllegalArgumentException("bad literal at " + i)
            i += l.length
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // consume '{'
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (i < s.length) {
                skipWs()
                if (i >= s.length || s[i] != '"') break
                val k = string()
                skipWs()
                if (i >= s.length || s[i] != ':') break
                i++
                out[k] = value()
                skipWs()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == '}') i++
                break
            }
            return out
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // consume '['
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (i < s.length) {
                out.add(value())
                skipWs()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == ']') i++
                break
            }
            return out
        }

        private fun string(): String {
            val sb = StringBuilder()
            i++ // consume opening quote
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    i++
                    return sb.toString()
                }
                if (c == '\\') {
                    i++
                    if (i >= s.length) break
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(i + 1, minOf(i + 5, s.length))
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        private fun number(): Any? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            if (i == start) {
                i++ // unexpected byte: skip it rather than abandon the whole document
                return null
            }
            return s.substring(start, i).toDoubleOrNull()
        }
    }
}
