package cc.joycreator.joybrush.core.doc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Thrown by [DocJson.decode] when a `document.json` cannot be read. */
class DocException(message: String) : Exception(message)

/**
 * `document.json` — the one file that says what a Joy Brush drawing IS.
 *
 * It is written pretty with keys in declaration order and states its defaults outright, so a person
 * opening it can see the whole shape without chasing a schema, and so a diff of two documents shows
 * a change of value rather than a change of presence. Reading IGNORES keys it does not know, so a
 * document written by a later Joy Brush still opens here instead of failing — that is the whole point
 * of [DOC_VERSION] being checked by [DocOps.validate] and not by the parser.
 *
 * Encoding is deterministic: the same document always produces byte-identical JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
object DocJson {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(doc: JbDocument): String = json.encodeToString(JbDocument.serializer(), canonical(doc))

    /**
     * Puts `frameCel` in key order before writing.
     *
     * Without this, a document saved twice can differ byte for byte purely because a map was built in
     * a different order — and a `.joybrush` is a file people diff, sync and ask "did this change?".
     * Sorting loses nothing: play order is `Board.frames`, not the order of these keys. Object keys
     * are already stable, because kotlinx writes properties in declaration order.
     */
    private fun canonical(doc: JbDocument): JbDocument {
        if (doc.layers.none { it.frameCel.size > 1 }) return doc
        return doc.copy(
            layers = doc.layers.map { l ->
                if (l.frameCel.size < 2) l
                else l.copy(frameCel = l.frameCel.entries.sortedBy { it.key }.associate { it.key to it.value })
            },
        )
    }

    /**
     * Decoding never validates. A document from a newer Joy Brush must still decode, so that
     * [DocOps.validate] can report the version problem in words instead of the caller getting a
     * parse error it cannot explain.
     */
    fun decode(text: String): JbDocument = try {
        json.decodeFromString(JbDocument.serializer(), text)
    } catch (e: SerializationException) {
        throw DocException("document.json cannot be read: ${e.message}")
    } catch (e: IllegalArgumentException) {
        // SerializationException and JsonDecodingException both land here.
        throw DocException("document.json cannot be read: ${e.message}")
    }
}
