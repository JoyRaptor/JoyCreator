package cc.joycreator.joybrush.core.doc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Thrown by [DocJson.decode] when a `document.json` cannot be read. */
class DocException(message: String) : Exception(message)

/**
 * `document.json` — the one file that says what a Joy Brush drawing IS.
 *
 * It is written pretty with keys in declaration order and states its defaults outright, so a person
 * opening it can see the whole shape without chasing a schema, and so a diff of two documents shows
 * a change of value rather than a change of presence.
 *
 * Reading REFUSES a key it does not know (R31, JB-0.02d). Not "ignores" — refuses. A key that is
 * dropped is a key that is gone: the document that came back no longer knows it was ever in the
 * file, and the next autosave writes the file back without it and stamps the CURRENT [DOC_VERSION]
 * on the result, so a person opens a drawing, Joy Brush quietly strips a setting it did not
 * understand, and the setting is lost for good under a version number that says the file is
 * current. The refusal happens at the PARSE, before anything is built, because the moment kotlinx
 * builds a [JbDocument] the unknown key is already gone and there is nothing left to name.
 *
 * A file from a NEWER Joy Brush is still read, deliberately: it is not this reader's unknown keys
 * that make it unreadable, and [DocOps.validate] says so in the words a person can act on —
 * "from a newer Joy Brush" — which is a better sentence than any key could produce. That is why
 * [DOC_VERSION] is checked by [DocOps.validate] and not by the parser, and why the check below is
 * gated on it.
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
        return doc.copy(
            layers = doc.layers.map { l ->
                l.copy(frameCel = l.frameCel.entries.sortedBy { it.key }.associate { it.key to it.value }, regions = l.regions.map { region ->
                    region.copy(frameCel = region.frameCel.entries.sortedBy { it.key }.associate { it.key to it.value })
                })
            },
        )
    }

    /**
     * Decoding never validates the DOCUMENT; the one thing it does refuse is a key this build does
     * not know, because that key cannot survive the trip. A document from a newer Joy Brush must
     * still decode, so that [DocOps.validate] can report the version problem in words instead of
     * the caller getting a parse error it cannot explain.
     */
    fun decode(text: String): JbDocument = try {
        refuseUnknownKeys(json.parseToJsonElement(text))
        json.decodeFromString(JbDocument.serializer(), text)
    } catch (e: SerializationException) {
        throw DocException("document.json cannot be read: ${e.message}")
    } catch (e: IllegalArgumentException) {
        // SerializationException and JsonDecodingException both land here.
        throw DocException("document.json cannot be read: ${e.message}")
    }

    /**
     * R31: a file carrying a key this build does not know is refused BY NAME, at the door.
     *
     * It sits INSIDE decode's `try` and not in front of it, for two reasons that are both load
     * bearing. A file that is not JSON at all throws from this parse as well as from the typed
     * decode, and it has to leave as a [DocException] — `JbArchive` catches only that, so a raw
     * library exception would crash the archive read instead of being reported. And a root that is
     * not a [JsonObject] (`"[]"`, a bare string) has no keys to speak of, so it falls straight
     * through to today's behaviour and the typed decode says what it always said.
     *
     * A file whose root `version` is GREATER than [DOC_VERSION] is not scanned: that file is from
     * the future, [DocOps.validate] already says so in better words, and a key is not the reason
     * it cannot be read here.
     *
     * That exemption is NARROW, and deliberately so (JB-0.02d Decision 2, corrected by R44 item 5).
     * ONLY a root `version` that is a JSON integer strictly greater than [DOC_VERSION] skips the
     * scan. A `version` that is absent, `null`, non-integer, fractional or a string IS scanned, and
     * a file carrying unknown keys is refused in words.
     *
     * An earlier draft of that decision let a version-less file fall through unscanned too, on the
     * reasoning that a file with no usable version might as well be passed on. That is the same
     * failure this function exists to stop, reached by a different road: a file this build cannot
     * place in time, carrying a setting it does not know, is precisely the file whose one useful
     * sentence is the one the scan would have given. Silently scattering it is what R31 was raised
     * about. The code below has always scanned those; only the prose was wrong.
     */
    private fun refuseUnknownKeys(root: JsonElement) {
        val obj = root as? JsonObject ?: return
        val version = (obj["version"] as? JsonPrimitive)?.intOrNull
        if (version != null && version > DOC_VERSION) return
        val unknown = ArrayList<String>()
        walkUnknownKeys(obj, JbDocument.serializer().descriptor, "$", unknown)
        if (unknown.isEmpty()) return
        throw DocException(unknown.joinToString("; "))
    }

    /**
     * The known-key set is the DESCRIPTOR's, never a table written out by hand: `getElementIndex`
     * answers "is this key known here, and if so what is it" straight from the class being walked,
     * so a field added to [DocModel] is known the moment it is added and a hand-written table
     * cannot drift. (Both `SerialDescriptor.getElementIndex` and the `elementNames` extension are
     * present and non-experimental in the pinned kotlinx-serialization 1.8.1 — verified against
     * the jar, not assumed.)
     *
     * The walk is EXHAUSTIVE over the tree, and it has to be, because `ignoreUnknownKeys` is still
     * `true` and so this scan is the ONLY guard: a shape it failed to visit would be dropped
     * silently, which is the exact failure this row exists to stop. So every [JsonObject] is
     * reached, including the ones inside arrays (`boards[]`, `cels[]`, `frames[]`), inside map
     * values, and inside a nullable field's descriptor — the last one because `grid: SpriteGrid?`
     * and friends reach this walk wrapped, and a wrapper that reported a different kind would
     * silently skip the whole object.
     *
     * Two kinds are deliberately NOT descended into as names. A [StructureKind.MAP]'s keys are
     * DATA, not keys of the format: `frameCel` maps a frame id to a cel id, so `f1` and `f2` are
     * values somebody chose and refusing them would refuse every animated document. Its VALUES are
     * a shape, so those are walked. Anything else that turns out to hold a [JsonObject] is a shape
     * this walk has no vocabulary for, and it is refused rather than waved through — a guard that
     * can fail open is not a guard.
     */
    private fun walkUnknownKeys(
        node: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
        out: MutableList<String>,
    ) {
        when (descriptor.kind) {
            StructureKind.CLASS -> {
                val obj = node as? JsonObject ?: return
                for (key in obj.keys) {
                    val at = descriptor.getElementIndex(key)
                    if (at < 0) {
                        out += "this file has \"$key\", which this version of Joy Brush does not know, at $path"
                    } else {
                        walkUnknownKeys(obj.getValue(key), descriptor.getElementDescriptor(at), "$path.$key", out)
                    }
                }
            }

            StructureKind.LIST -> {
                val arr = node as? JsonArray ?: return
                val element = descriptor.getElementDescriptor(0)
                for (i in arr.indices) walkUnknownKeys(arr[i], element, "$path[$i]", out)
            }

            StructureKind.MAP -> {
                val obj = node as? JsonObject ?: return
                val value = descriptor.getElementDescriptor(1)
                for (key in obj.keys) walkUnknownKeys(obj.getValue(key), value, "$path.$key", out)
            }

            else -> if (node is JsonObject) {
                out += "this file has an object at $path that this version of Joy Brush has no shape for"
            }
        }
    }
}
