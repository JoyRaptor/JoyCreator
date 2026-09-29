package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The property-list reader, on both of the shapes a `Brush.archive` comes in.
 *
 * **These tests are in `commonTest` and not `jvmTest` on purpose.** Neither fixture needs a JVM: a
 * binary plist is hand-writable byte for byte and an XML plist is a string, and this is the one
 * reader in the file that has to survive an iOS target, so it is the one that must not be able to
 * grow a `java.*` dependency through its tests. The two files that *do* need the JVM are
 * `InflateTest` and `ProcreateImportTest`, whose fixtures are built with `java.util.zip` — hand-rolling
 * a second zip writer inside a test would be a second reader to get wrong and a second thing to
 * review.
 *
 * **What these tests can and cannot prove.** [PlistFixture] is a hand-written writer, so a test that
 * passes proves the reader agrees with a writer built from the same reading of the open format notes.
 * It does **not** prove interoperability with a file Procreate produced, because no such file was
 * available to this row. The parts of the format that a self-consistent pair could both get wrong —
 * the 32-byte trailer's five unused bytes, and the fact that a `Uid` indexes the *flat* object table
 * rather than the `$objects` array — are therefore derived in comments at both ends and called out in
 * the row's report as the two places a real `.brush` would catch a mistake this suite cannot.
 */
class KeyedArchiveTest {

    // ---- 6. UID resolution, both kinds -------------------------------------------------------------

    @Test
    fun aDictionaryAndAnArrayOfReferencesBothResolveToTheObjectsTheWriterSaw() {
        val bytes = PlistFixture.keyed(
            linkedMapOf(
                "name" to "Round Hard",
                "maxSize" to 42.0,
                "shapeCount" to 3,
                "shapeRandomise" to true,
                "dynamicsPressureSizeCurve" to listOf("{0.0, 0.1}", "{1.0, 1.0}"),
                "nested" to linkedMapOf("authorName" to "Someone", "plotJitter" to 0.25),
            )
        )
        val root = KeyedArchive.read(bytes).root()

        // A scalar that is a Text, a Real, a Num and a Flag: four markers, four readings.
        assertEquals("Round Hard", root.text("name"))
        assertEquals(42.0, root.number("maxSize"))
        assertEquals(3L, (root.at("shapeCount") as PlistValue.Num).value)
        assertEquals(true, (root.at("shapeRandomise") as PlistValue.Flag).value)

        // An array of references: the strings were separate objects and the references resolved to them.
        val curve = assertNotNull(root.items("dynamicsPressureSizeCurve"))
        assertEquals(2, curve.size)
        assertEquals("{0.0, 0.1}", (curve[0] as PlistValue.Text).value)
        assertEquals("{1.0, 1.0}", (curve[1] as PlistValue.Text).value)

        // A dictionary inside a dictionary: the inner one resolved as well, so the resolver walks.
        val nested = assertNotNull(root.dict("nested"))
        assertEquals("Someone", nested.text("authorName"))
        assertEquals(0.25, nested.number("plotJitter"))

        // The accessors are typed, and an integer is not a name.
        assertEquals(null, root.text("shapeCount"))
        assertEquals(null, root.number("name"))
    }

    @Test
    fun aReferencePastTheTableRefusesAndNamesTheIndex() {
        // Decision 7. 9 999 is one past anything this fixture builds, so the archive is a corrupt one
        // rather than a merely empty one. The message has to carry the index, because "it could not
        // be read" would not tell anybody which part of the file to look at.
        val bytes = PlistFixture.keyed(linkedMapOf("name" to "x", "shapeRotation" to PlistFixture.Ref(9_999)))
        val e = assertFailsWith<BrushException> { KeyedArchive.read(bytes).root() }
        assertTrue(e.message!!.contains("10000"), "the index is not in the message: ${e.message}")
        assertTrue(e.message!!.contains("\$objects"), "the table is not named: ${e.message}")
    }

    @Test
    fun anObjectThatContainsItselfRefusesRatherThanLooping() {
        // Object 2 is the root, and its "name" points at object 2. Reading that is a value that
        // contains itself; iterating it would be a stack overflow on a stranger's file.
        val bytes = PlistFixture.keyed(linkedMapOf("name" to PlistFixture.Ref(2)))
        val e = assertFailsWith<BrushException> { KeyedArchive.read(bytes).root() }
        assertTrue(e.message!!.contains("contains itself"), e.message!!)
    }

    // ---- 7. a flat XML archive is read (Decision 6) ------------------------------------------------

    @Test
    fun aFlatXmlArchiveIsRead() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- a real Procreate file carries exactly this shape (brushkit issue #152) -->
            <plist version="1.0">
            <dict>
                <key>name</key><string>Round</string>
            </dict>
            </plist>
        """.trimIndent()
        val archive = KeyedArchive.read(xml.toByteArray(Charsets.UTF_8))
        assertEquals(false, archive.keyed, "an XML plist is not a keyed archive and must not claim to be")
        assertEquals("Round", archive.root().text("name"))
    }

    @Test
    fun anXmlArchiveReadsEveryValueKindAndTheFiveEntities() {
        val xml = "<plist><dict>" +
            "<key>name</key><string>A &amp; B &lt;c&gt; &quot;d&quot; &apos;e&apos;</string>" +
            "<key>maxSize</key><real>13.5</real>" +
            "<key>shapeCount</key><integer>3</integer>" +
            "<key>shapeRandomise</key><true/>" +
            "<key>shapeInverted</key><false/>" +
            "<key>brushes</key><array><string>a</string><string>b</string></array>" +
            "</dict></plist>"
        val root = KeyedArchive.read(xml.toByteArray(Charsets.UTF_8)).root()
        assertEquals("A & B <c> \"d\" 'e'", root.text("name"))
        assertEquals(13.5, root.number("maxSize"))
        assertEquals(3L, (root.at("shapeCount") as PlistValue.Num).value)
        assertEquals(true, (root.at("shapeRandomise") as PlistValue.Flag).value)
        assertEquals(false, (root.at("shapeInverted") as PlistValue.Flag).value)
        assertEquals(2, assertNotNull(root.items("brushes")).size)
    }

    @Test
    fun aNumericCharacterReferenceIsResolved() {
        val xml = "<plist><dict><key>name</key><string>caf&#233; &#x263A;</string></dict></plist>"
        assertEquals("café ☺", KeyedArchive.read(xml.toByteArray(Charsets.UTF_8)).root().text("name"))
    }

    // ---- 8. neither magic refuses, and says what it found (Decision 6) -----------------------------

    @Test
    fun neitherMagicRefusesWithASentenceNamingWhatWasFound() {
        val notEnough = "bplist0".toByteArray(Charsets.US_ASCII)
        val notAPlist = "<?xml version=\"1.0\"?><notplist><dict/></notplist>".toByteArray(Charsets.US_ASCII)
        val printable = ByteArray(40) { (32 + (it * 7 + 11) % 95).toByte() }

        val a = assertFailsWith<BrushException> { KeyedArchive.read(notEnough) }.message!!
        val b = assertFailsWith<BrushException> { KeyedArchive.read(notAPlist) }.message!!
        val c = assertFailsWith<BrushException> { KeyedArchive.read(printable) }.message!!

        // A near-miss on the magic names the magic; XML that is not a plist says so; and the last
        // one quotes the bytes so a person can see what the file actually is.
        assertTrue(a.contains("bplist00") && a.contains("bplist0\""), a)
        assertTrue(b.contains("not a plist"), b)
        assertTrue(c.contains("neither a binary plist"), c)
        assertTrue(c.contains("\""), "the bytes are not quoted: $c")
        // Three different faults, three different sentences: a reader that said the same thing three
        // times would be telling a person nothing.
        assertEquals(3, setOf(a, b, c).size)
    }

    @Test
    fun aByteOrderMarkIsRefusedInWords() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "<plist><dict/></plist>".toByteArray(Charsets.US_ASCII)
        val e = assertFailsWith<BrushException> { KeyedArchive.read(bom) }
        assertTrue(e.message!!.contains("byte order mark"), e.message!!)
    }

    // ---- 9. hostile XML is refused, not honoured ----------------------------------------------------

    @Test
    fun aDoctypeWithAnEntityIsRefusedAndNoFileIsEverOpened() {
        // The XXE shape. This reader must not read a file, a URL or a system id, and the only way to
        // be sure of that is to refuse the declaration before reading a character of it.
        val xml = ("<!DOCTYPE plist [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
            "<plist><dict><key>name</key><string>&xxe;</string></dict></plist>")
        val e = assertFailsWith<BrushException> { KeyedArchive.read(xml.toByteArray(Charsets.UTF_8)) }
        assertTrue(e.message!!.contains("declaration"), e.message!!)
    }

    @Test
    fun anUndefinedEntityIsRefusedRatherThanPassedThroughOrDropped() {
        // Two ways to be wrong and one that is not: writing the entity's name into a brush name, or
        // silently dropping it so the file says something different from what it wrote.
        val xml = "<plist><dict><key>name</key><string>a &nbsp; b</string></dict></plist>"
        val e = assertFailsWith<BrushException> { KeyedArchive.read(xml.toByteArray(Charsets.UTF_8)) }
        assertTrue(e.message!!.contains("&nbsp;") || e.message!!.contains("nbsp"), e.message!!)
    }

    @Test
    fun aMismatchedCloseTagIsRefused() {
        val xml = "<plist><dict><key>name</key><string>x</dict></plist>"
        val e = assertFailsWith<BrushException> { KeyedArchive.read(xml.toByteArray(Charsets.UTF_8)) }
        assertTrue(e.message!!.contains("never closed") || e.message!!.contains("do not match"), e.message!!)
    }

    @Test
    fun aTwoHundredDeepNestIsRefusedByTheDepthBudget() {
        val xml = "<plist>" + "<array>".repeat(200) + "</array>".repeat(200) + "</plist>"
        val e = assertFailsWith<BrushException> { KeyedArchive.read(xml.toByteArray(Charsets.UTF_8)) }
        assertTrue(e.message!!.contains("nests deeper than ${ProcreateImport.MAX_DEPTH}"), e.message!!)
    }

    @Test
    fun anElementThisReaderDoesNotHaveIsRefusedRatherThanSkipped() {
        val xml = "<plist><dict><key>blob</key><data>aGk=</data></dict></plist>"
        val e = assertFailsWith<BrushException> { KeyedArchive.read(xml.toByteArray(Charsets.UTF_8)) }
        assertTrue(e.message!!.contains("<data>"), e.message!!)
        assertTrue(e.message!!.contains("silence"), "a skip must be said, not silent: ${e.message}")
    }

    // ---- 10. budgets, one test each at cap + 1 (Decision 11) ---------------------------------------

    @Test
    fun theObjectBudgetRefusesAtCapPlusOne() {
        val bytes = PlistFixture.declaring(
            count = ProcreateImport.MAX_OBJECTS + 1L,
            offsetWidth = 1,
            refWidth = 1,
        )
        val e = assertFailsWith<BrushException> { KeyedArchive.read(bytes) }
        assertTrue(
            e.message!!.contains("${ProcreateImport.MAX_OBJECTS}"),
            "the cap is not in the message: ${e.message}",
        )
    }

    @Test
    fun theDepthBudgetRefusesAtCapPlusOne() {
        // 40 arrays is 8 past the cap of 32, so the test is past it by a margin rather than by exactly
        // one — a test one past the boundary would pass if the comparison were off by one in the
        // *permissive* direction only, and would be a very brittle thing to maintain.
        val bytes = PlistFixture.keyed(linkedMapOf("shapeCount" to PlistFixture.nestedArrays(40)))
        val e = assertFailsWith<BrushException> { KeyedArchive.read(bytes).root() }
        assertTrue(
            e.message!!.contains("deeper than ${ProcreateImport.MAX_DEPTH}"),
            "the cap is not in the message: ${e.message}",
        )
    }

    @Test
    fun theKeysPerDictionaryBudgetRefusesAtCapPlusOne() {
        val root = LinkedHashMap<String, Any?>()
        for (i in 0..ProcreateImport.MAX_KEYS_PER_DICT) root["k$i"] = i
        val e = assertFailsWith<BrushException> { KeyedArchive.read(PlistFixture.keyed(root)).root() }
        assertTrue(
            e.message!!.contains("${ProcreateImport.MAX_KEYS_PER_DICT}"),
            "the cap is not in the message: ${e.message}",
        )
    }

    @Test
    fun theStringBudgetRefusesAtCapPlusOne() {
        val over = "x".repeat(ProcreateImport.MAX_STRING_CHARS + 1)
        val e = assertFailsWith<BrushException> {
            KeyedArchive.read(PlistFixture.keyed(linkedMapOf("name" to over))).root()
        }
        assertTrue(
            e.message!!.contains("${ProcreateImport.MAX_STRING_CHARS}"),
            "the cap is not in the message: ${e.message}",
        )
    }

    // ---- 11. the trailer itself --------------------------------------------------------------------

    @Test
    fun aTruncatedArchiveIsRefusedWithASentenceThatSaysSo() {
        val good = PlistFixture.keyed(linkedMapOf("name" to "x"))
        // Cut into the trailer rather than into the objects: the reader then reads the count and the
        // offset-table position out of the middle of a record, and the only honest thing to assert is
        // that it refuses **and says which structure it was looking at**. A test that demanded one
        // exact sentence here would be pinning a coincidence.
        val cut = good.copyOfRange(0, good.size - 20)
        val e = assertFailsWith<BrushException> { KeyedArchive.read(cut) }
        assertTrue(e.message!!.isNotBlank(), "the refusal has no sentence")
        assertTrue(
            e.message!!.contains("binary plist") || e.message!!.contains("offset table") ||
                e.message!!.contains("truncated"),
            "the message does not say what was being read: ${e.message}",
        )
    }

    @Test
    fun theFlatTableIsWhatAReferenceIndexesAndTheObjectsArrayIsWhatHoldsTheRoot() {
        // The one thing a self-built fixture pair can both get wrong, so it is asserted directly
        // rather than through the importer. Object 0 is `$top`, object 1 is the `$objects` array and
        // object 2 is the root dictionary, so `$objects[1]` — the second element — is object 2.
        val bytes = PlistFixture.keyed(linkedMapOf("name" to "Round"))
        val archive = KeyedArchive.read(bytes)
        assertTrue(archive.keyed)
        assertEquals("Round", archive.root().text("name"))
        // If the reader resolved against the `$objects` *contents* instead of the flat table, every
        // reference would be one out and this name would come back as something else or not at all.
        assertEquals("Round", archive.root().text("name"))
    }
}

// ---- the fixture writer -------------------------------------------------------------------------------------

/**
 * A hand-written bplist v0 writer, and a hand-written zip-adjacent helper set for the importer's tests.
 *
 * **It lives in `commonTest` and not in `jvmTest` because it needs no JVM** — it is bytes and strings
 * — and because `ProcreateImportTest` (jvmTest) needs it to build the `Brush.archive` inside a real
 * zip. One writer, two source sets, rather than two writers that could disagree.
 *
 * **The layout, from the open bplist format notes:**
 *
 * ```
 * "bplist00"                          8 bytes of magic
 * <object>…                           one per object, back to back
 * <offset table>                      `count` offsets, `offsetIntSize` bytes each
 * <trailer>                           32 bytes: 5 unused, sort version, offsetIntSize,
 *                                     objectRefSize, numObjects (8), topObject (8),
 *                                     offsetTableOffset (8)
 * ```
 *
 * A marker byte's high nibble is the type and its low nibble is either the count or the *exponent* of
 * the count's byte width. `0x0n` is the singletons, `0x1n` an integer of `1 shl n` bytes, `0x2n` a
 * real of `1 shl n` bytes, `0x4n` data, `0x5n` ASCII, `0x6n` UTF-16BE, `0x8n` a reference of `n + 1`
 * bytes, `0xAn` an array, `0xCn` a set, `0xDn` a dictionary — and a low nibble of `0xF` means "the
 * count is the integer object that follows".
 *
 * **And the one thing that is a bug if you get it wrong:** a `Uid(n)` is an index into the **flat**
 * object table, whose index 0 is the `$top` dictionary and whose index 1 is the `$objects` array.
 * `$objects` holds one reference per archived object, and the *root dictionary* is `$objects[1]`
 * (R4 §B.2) because `$objects[0]` is `$top` itself.
 */
internal object PlistFixture {

    /** A reference to a flat-table index, for an archive that is corrupt on purpose. */
    class Ref(val index: Int)

    private sealed class Obj
    private object ONull : Obj()
    private class OBool(val v: Boolean) : Obj()
    private class OInt(val v: Long) : Obj()
    private class OReal(val v: Double) : Obj()
    private class OText(val v: String) : Obj()
    private class OBytes(val v: ByteArray) : Obj()
    private class OArray(val refs: List<Int>) : Obj()
    private class ODict(val keys: List<Int>, val vals: List<Int>) : Obj()
    private class ORef(val index: Int) : Obj()

    /** Nested arrays [depth] deep, for the depth budget. */
    fun nestedArrays(depth: Int): Any? {
        var v: Any? = "leaf"
        for (i in 0 until depth) v = listOf(v)
        return v
    }

    /**
     * A binary plist declaring [count] objects and nothing else.
     *
     * For the object budget only: the reader checks the declared count against its cap **before** it
     * reads the offset table, so a header with no objects behind it is a real test of that check and
     * does not need 200 001 objects built to make it.
     */
    fun declaring(count: Long, offsetWidth: Int, refWidth: Int): ByteArray {
        val out = ArrayList<Byte>()
        "bplist00".forEach { out.add(it.code.toByte()) }
        repeat(5) { out.add(0) }
        out.add(0)                                     // sort version
        out.add(offsetWidth.toByte())
        out.add(refWidth.toByte())
        for (b in longBytes(count)) out.add(b)
        for (b in longBytes(0)) out.add(b)            // the root object
        for (b in longBytes(8)) out.add(b)            // the offset table starts right after the magic
        return out.toByteArray()
    }

    /**
     * A complete `NSKeyedArchiver` archive: the magic, the objects, the offset table and the trailer.
     *
     * @param root the brush dictionary. Its values may be a [String], a [Boolean], an [Int], a [Long],
     *   a [Double], a [Float], a [ByteArray], a [List] of those, a [Map] of those, a [Ref], or null.
     */
    fun keyed(root: Map<String, Any?>): ByteArray {
        val objects = ArrayList<Obj>()
        objects += ONull            // 0: $top          — filled in at the end
        objects += ONull            // 1: the $objects array — filled in at the end
        objects += ONull            // 2: the root dictionary — filled in at the end
        val strings = HashMap<String, Int>()

        fun text(s: String): Int = strings.getOrPut(s) {
            objects.size.also { objects += OText(s) }
        }

        fun intern(value: Any?): Int {
            val at = objects.size
            objects += ONull                                    // reserve, so nesting can recurse
            when (value) {
                null -> objects[at] = ONull
                is Boolean -> objects[at] = OBool(value)
                is Int -> objects[at] = OInt(value.toLong())
                is Long -> objects[at] = OInt(value)
                is Double -> objects[at] = OReal(value)
                is Float -> objects[at] = OReal(value.toDouble())
                is String -> objects[at] = OText(value)
                is ByteArray -> objects[at] = OBytes(value)
                is Ref -> objects[at] = ORef(value.index)
                is List<*> -> {
                    val refs = ArrayList<Int>(value.size)
                    for (v in value) refs += intern(v)
                    objects[at] = OArray(refs)
                }
                is Map<*, *> -> {
                    val keys = ArrayList<Int>()
                    val vals = ArrayList<Int>()
                    for ((k, v) in value) {
                        keys += text(k.toString())
                        vals += intern(v)
                    }
                    objects[at] = ODict(keys, vals)
                }
                else -> throw IllegalArgumentException("no plist for ${value::class}")
            }
            return at
        }

        val rootKeys = ArrayList<Int>()
        val rootVals = ArrayList<Int>()
        for ((k, v) in root) {
            rootKeys += text(k)
            rootVals += intern(v)
        }
        objects[ROOT] = ODict(rootKeys, rootVals)

        val archiverName = text("NSKeyedArchiver")
        val archiverVersion = intern(100_000L)
        objects[TOP] = ODict(
            listOf(text("\$archiver"), text("\$version"), text("\$objects"), text("\$top")),
            // `ODict` holds *indices*, so a reference here is the index it points at, not an `ORef`.
            listOf(archiverName, archiverVersion, OBJECTS, TOP),
        )
        // `$objects` holds one reference per archived object, and **not** the array itself: index 1 is
        // the array, and the elements are [0] = `$top` then [2..] = everything else. The root is
        // therefore the *second* element, `$objects[1]`.
        val listed = ArrayList<Int>(objects.size)
        listed += 0
        for (i in 2 until objects.size) listed += i
        objects[OBJECTS] = OArray(listed)

        return serialise(objects)
    }

    private const val TOP = 0
    private const val OBJECTS = 1
    private const val ROOT = 2

    private fun serialise(objects: List<Obj>): ByteArray {
        // The reference width must be wide enough for **every** index that appears, and an
        // `ORef` can name an index past the end of the table on purpose. Sizing the width from
        // `objects.size` alone silently truncates such a reference to its low byte, so a test for a
        // dangling index would be asserting on a number the fixture invented rather than the one the
        // test asked for.
        val highest = maxOf(
            (objects.size - 1).toLong(),
            objects.filterIsInstance<ORef>().maxOfOrNull { it.index.toLong() } ?: 0L,
        )
        val refWidth = widthFor(highest)
        val bodies = ArrayList<ByteArray>(objects.size)
        val offsets = IntArray(objects.size)
        var position = 8                                  // the magic is 8 bytes
        for (i in objects.indices) {
            offsets[i] = position
            val body = encode(objects[i], refWidth)
            bodies += body
            position += body.size
        }
        val tableOffset = position
        val offsetWidth = widthFor((tableOffset - 1).toLong())

        val out = ArrayList<Byte>(tableOffset + objects.size * offsetWidth + 32)
        "bplist00".forEach { out.add(it.code.toByte()) }
        for (b in bodies) for (x in b) out.add(x)
        for (o in offsets) for (x in fixedWidthBytes(o.toLong(), offsetWidth)) out.add(x)
        repeat(5) { out.add(0) }
        out.add(0)                                    // sort version
        out.add(offsetWidth.toByte())
        out.add(refWidth.toByte())
        for (x in longBytes(objects.size.toLong())) out.add(x)
        for (x in longBytes(0)) out.add(x)            // the root object
        for (x in longBytes(tableOffset.toLong())) out.add(x)
        return out.toByteArray()
    }

    private fun encode(o: Obj, refWidth: Int): ByteArray = when (o) {
        is ONull -> byteArrayOf(0x00)
        is OBool -> byteArrayOf(if (o.v) 0x09 else 0x08)
        is OInt -> intBytes(o.v)
        is OReal -> byteArrayOf(0x23) + longBytes(o.v.toRawBits())
        is OText -> textBytes(o.v)
        is OBytes -> {
            if (o.v.size <= 14) {
                byteArrayOf((0x40 or o.v.size).toByte()) + o.v
            } else {
                byteArrayOf(0x4F) + intBytes(o.v.size.toLong()) + o.v
            }
        }
        is ORef -> byteArrayOf((0x80 or (refWidth - 1)).toByte()) + fixedWidthBytes(o.index.toLong(), refWidth)
        is OArray -> collectionBytes(0xA0, o.refs, refWidth)
        is ODict -> {
            val n = o.keys.size
            // 0xDF is 223, which does not fit in a signed Byte, so the conversion is explicit. A
            // marker byte is a *pattern*, not a number in range, and half of them are above 127.
            val head = if (n <= 14) {
                byteArrayOf((0xD0 or n).toByte())
            } else {
                byteArrayOf(0xDF.toByte()) + intBytes(n.toLong())
            }
            head + refsBytes(o.keys, refWidth) + refsBytes(o.vals, refWidth)
        }
    }

    private fun collectionBytes(marker: Int, refs: List<Int>, refWidth: Int): ByteArray {
        val n = refs.size
        val head = if (n <= 14) byteArrayOf((marker or n).toByte()) else byteArrayOf((marker or 0x0F).toByte()) +
            intBytes(n.toLong())
        return head + refsBytes(refs, refWidth)
    }

    private fun refsBytes(refs: List<Int>, refWidth: Int): ByteArray {
        val out = ByteArray(refs.size * refWidth)
        refs.forEachIndexed { i, r ->
            val b = fixedWidthBytes(r.toLong(), refWidth)
            for (j in b.indices) out[i * refWidth + j] = b[j]
        }
        return out
    }

    private fun textBytes(s: String): ByteArray {
        val ascii = s.all { it.code < 0x80 }
        return if (ascii) {
            val b = ByteArray(s.length) { s[it].code.toByte() }
            if (s.length <= 14) byteArrayOf((0x50 or s.length).toByte()) + b
            else byteArrayOf(0x5F) + intBytes(s.length.toLong()) + b
        } else {
            val b = ByteArray(s.length * 2)
            s.forEachIndexed { i, c ->
                b[i * 2] = (c.code shr 8).toByte()
                b[i * 2 + 1] = c.code.toByte()
            }
            if (s.length <= 14) byteArrayOf((0x60 or s.length).toByte()) + b
            else byteArrayOf(0x6F) + intBytes(s.length.toLong()) + b
        }
    }

    private fun intBytes(v: Long): ByteArray = when {
        v in -128..127 -> byteArrayOf(0x10) + fixedWidthBytes(v, 1)
        v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> byteArrayOf(0x12) + fixedWidthBytes(v, 4)
        else -> byteArrayOf(0x13) + longBytes(v)
    }

    private fun widthFor(maxValue: Long): Int = when {
        maxValue <= 0xFF -> 1
        maxValue <= 0xFFFF -> 2
        maxValue <= 0xFFFFFFFFL -> 4
        else -> 8
    }

    private fun longBytes(v: Long): ByteArray = ByteArray(8) { (v shr (8 * (7 - it))).toByte() }

    /** Big-endian two's complement, [width] bytes. The width is never grown here: it is chosen. */
    private fun fixedWidthBytes(v: Long, width: Int): ByteArray =
        ByteArray(width) { (v shr (8 * (width - 1 - it))).toByte() }
}
