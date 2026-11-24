package cc.joycreator.joybrush.android.chrome

/**
 * JoyBrush's rule (the three bars: "no technical errors ever shown"): a message reaching a person
 * never carries the inside of the app. Many refusals and failures arrive with an exception's own
 * text on the end ("Couldn't export. Your drawing is safe. this PNG renderer supports raster
 * layers..."); the plain sentences in front are kept and the technical tail is dropped, so the
 * person reads "Couldn't export. Your drawing is safe." The full text still goes to the log.
 *
 * A sentence is technical when it names the app's insides: file and zip internals, a cel, a slab,
 * a stored-tile kind, a renderer, the graphics driver, an exception class, byte counts. A person's
 * own names in quotes ("Sketch" is locked) are NOT technical: only the words around them count.
 *
 * Pure Kotlin, pinned by `PlainWordsTest` against real messages from the app.
 */
object PlainWords {

    /** Said when every sentence of a message was technical. */
    const val FALLBACK = "That didn't work, and your drawing is safe. Try again, or close and reopen the drawing."

    private val TECHNICAL = listOf(
        Regex("""(?i)\brenderer\b"""),
        Regex("""(?i)\b(GL error|framebuffer|GPU|texture|float colour buffers?|shader)\b"""),
        Regex("""(?i)\bcel\b"""),
        Regex("""(?i)\bslabs?\b"""),
        Regex("""(?i)\b(media|paint|ground|look|stored frame|physical source|snapshot media|snapshot) tiles?\b"""),
        Regex("""(?i)\btile (key|"|\$|\{)"""),
        Regex("""(?i)\btx_ty\b"""),
        Regex("""(?i)\b(archive|zip|zip64|mimetype|document\.json|stack\.xml|sidecar|schema|JSON)\b"""),
        Regex("""(?i)\bentry (name )?"[^"]*"""" ),
        Regex("""\b[A-Z][A-Za-z]*(Exception|Error)\b"""),
        Regex("""\b(java|kotlin|android)\.[a-z]"""),
        Regex("""(?i)\b\d+ (of \d+ )?bytes\b"""),
        Regex("""(?i)\bnot connected( yet)?\b"""),
        Regex("""(?i)\b(tween renderer|tween exposures|exposures)\b"""),
        Regex("""(?i)\bseq \d"""),
        Regex("""(?i)\bnull\b"""),
        Regex("""\b(board|layer) "(?=[^"]*[0-9_])[a-z0-9_-]{8,}""""),   // an internal id (it has digits), not a person's name
        Regex("""(?i)\bcanonical\b|\bpaint owner\b|\bnative owner\b|\bprojection\b"""),
        Regex("""(?i)\bCPU (ink )?rendering\b|\bimage/pooling pass\b"""),
    )

    /** True when [sentence] names the app's insides. */
    fun isTechnical(sentence: String): Boolean = TECHNICAL.any { it.containsMatchIn(sentence) }

    /**
     * [message] as a person should read it: the plain sentences up to the first technical one; or
     * [fallback] when nothing plain is left. A message with nothing technical in it is returned as is.
     */
    fun forPerson(message: String?, fallback: String = FALLBACK): String {
        val m = message?.trim().orEmpty()
        if (m.isEmpty()) return fallback
        if (!isTechnical(m)) return m
        val kept = StringBuilder()
        for (s in sentences(m)) {
            if (isTechnical(s)) break
            kept.append(s)
        }
        var out = kept.toString().trim()
        if (out.endsWith(":") || out.endsWith(";")) out = out.dropLast(1).trimEnd() + "."
        return if (out.isEmpty() || out == ".") fallback else out
    }

    /** Sentences with their ending kept ("Couldn't export." / " Your drawing is safe."). */
    private fun sentences(m: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < m.length) {
            val c = m[i]
            if ((c == '.' || c == '!' || c == '?' || c == ':' || c == ';') && (i + 1 == m.length || m[i + 1] == ' ')) {
                out.add(m.substring(start, i + 1))
                start = i + 1
            }
            i++
        }
        if (start < m.length) out.add(m.substring(start))
        return out
    }
}
