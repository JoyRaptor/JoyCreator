package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.io.ByteReader
import cc.joycreator.joybrush.core.io.ByteWriter

/**
 * A stroke recording on disk, byte-for-byte.
 *
 * This is the promise the rest of the engine is built on: whatever a painter drew can be written and
 * read back and be the SAME stroke — same positions, same raw bits, same NaN in the channels that
 * pen has no sensor for. Ink layers, undo replay, the timelapse and re-brushing a stroke all come
 * back through here, so "close enough" is not good enough anywhere in here.
 *
 * Binary, not JSON: a 1,000-sample stroke is ~33 KB instead of ~150 KB, and a float written with
 * [Float.toRawBits] comes back as the same float rather than the nearest thing JSON can spell.
 *
 * A channel is stored for the WHOLE recording when ANY sample has it (flag bit per channel), so the
 * layout is one fixed shape rather than a per-sample variable one; a sample that had no reading
 * stores NaN and decodes as NaN, which is what it was.
 *
 * ## Versions
 *
 * LEAD_RULINGS R3 (new data ⇒ version bump), which is why the two fields JB-5.03a added —
 * `colorArgb` (u32) and `widthScale` (f32) — cost a version number and not a guess:
 *
 * | version | layout |
 * |---|---|
 * | 1 | …`screenPerDoc`, `sampleCount`, samples. Reads back as black, scale 1. |
 * | 2 | …`screenPerDoc`, **`colorArgb`, `widthScale`**, `sampleCount`, samples. |
 * | 3 | …`widthScale`, **`seq` (i64), [`groupId` (utf8) only when the group flag is set]**, `sampleCount`, samples. Header flag bit 3 = locked (no payload), bit 4 = has a groupId. |
 *
 * v3 (JB-5.20 D1, 5.20a) puts a line in its cel's time order (`seq`) and carries the two owner/UI fields. `locked` and
 * `groupId` cost nothing when they are not used: the flag bits are 0 and no bytes are written. A v1/v2 recording read
 * by [decodeAll] gets `seq` from FILE ORDER (1, 2, 3...), `locked = false`, `groupId = null`; read alone by [decode]
 * it has no neighbours to be ordered among, so its `seq` is 0 ("not in a cel yet").
 *
 * [VERSION] is 3 and [OLDEST_VERSION] is 1: the writer always writes the newest, and the reader
 * accepts every version in between, because a recording written by the build that drew it must
 * still open. The magic stays [MAGIC] — it names the FORMAT FAMILY ("a Joy Brush stroke"), and a
 * v3 recording is still one. A version outside the range is refused by number, as JB-0.04's
 * decision 4 says: this build does not know what a v4 recording means, and guessing is how a
 * drawing is destroyed.
 */
object StrokeCodec {
    const val MAGIC = "JBS1"

    /** The newest recording this build writes, and the newest it reads. */
    const val VERSION: Int = 3

    /** The oldest recording this build reads. See the table above. */
    const val OLDEST_VERSION: Int = 1

    /** What a version-1 recording is read as: black, at the brush's own size. */
    const val DEFAULT_COLOR_ARGB: Int = 0xFF000000.toInt()

    private const val FLAG_TILT = 1
    private const val FLAG_AZIMUTH = 1 shl 1
    private const val FLAG_BARREL = 1 shl 2
    private const val FLAG_LOCKED = 1 shl 3   // v3
    private const val FLAG_GROUP = 1 shl 4    // v3
    private const val SAMPLE_FLAGS = FLAG_TILT or FLAG_AZIMUTH or FLAG_BARREL
    private const val V3_FLAGS = FLAG_LOCKED or FLAG_GROUP

    private val MAGIC_BYTES = MAGIC.encodeToByteArray()

    /** The smallest a sample can be: timeMs, x, y, pressure, tool. Used to sanity-check counts. */
    private const val MIN_SAMPLE_BYTES = 8 + 4 + 4 + 4 + 1

    /** Writes [VERSION]. Always the newest layout — an old recording is upgraded by being re-saved. */
    fun encode(record: StrokeRecord): ByteArray {
        // Decode refuses a negative seq, so a recording that said one would be a drawing that cannot be reopened.
        if (record.seq < 0) throw StrokeCodecException("stroke \"${record.id}\" has seq ${record.seq}; a seq is never negative")
        val w = ByteWriter()
        w.bytes(MAGIC_BYTES)
        w.u16(VERSION)

        var flags = 0
        if (record.samples.any { it.hasTilt }) flags = flags or FLAG_TILT
        if (record.samples.any { it.hasAzimuth }) flags = flags or FLAG_AZIMUTH
        if (record.samples.any { it.hasBarrel }) flags = flags or FLAG_BARREL
        if (record.locked) flags = flags or FLAG_LOCKED
        if (record.groupId != null) flags = flags or FLAG_GROUP
        w.u16(flags)

        w.utf8(record.id)
        w.utf8(record.brushId)
        w.i64(record.seed)
        w.f32(record.smoothing)
        w.f32(record.screenPerDoc)
        w.u32(record.colorArgb.toLong() and 0xFFFF_FFFFL)
        w.f32(record.widthScale)
        w.i64(record.seq)
        record.groupId?.let { w.utf8(it) }
        w.u32(record.samples.size.toLong())

        val tilt = flags and FLAG_TILT != 0
        val azimuth = flags and FLAG_AZIMUTH != 0
        val barrel = flags and FLAG_BARREL != 0
        for (s in record.samples) {
            w.f64(s.timeMs)
            w.f32(s.x)
            w.f32(s.y)
            w.f32(s.pressure)
            if (tilt) w.f32(s.tilt)
            if (azimuth) w.f32(s.azimuth)
            if (barrel) w.f32(s.barrel)
            w.u8(s.tool.ordinal)
        }
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): StrokeRecord {
        val r = ByteReader(bytes)
        if (!r.bytes(MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)) {
            throw StrokeCodecException("not a stroke recording: bad magic")
        }
        val version = r.u16()
        if (version < OLDEST_VERSION || version > VERSION) {
            throw StrokeCodecException("unsupported stroke version $version")
        }
        val flags = r.u16()
        // A bit we do not know means the sample layout we are about to read is not this one, so the
        // samples would come back as plausible rubbish. Refuse rather than guess.
        // The locked / group bits exist from version 3; in a v1/v2 recording they are as unknown as any other.
        val known = if (version >= 3) SAMPLE_FLAGS or V3_FLAGS else SAMPLE_FLAGS
        if (flags and known.inv() != 0) {
            throw StrokeCodecException("unsupported stroke flags 0x${flags.toString(16)}")
        }

        val id = r.utf8()
        val brushId = r.utf8()
        val seed = r.i64()
        val smoothing = r.f32()
        val screenPerDoc = r.f32()

        // Version 1 stops here. A recording written before a stroke carried its own colour and
        // weight is read as what it was drawn as: black, at the brush's own size.
        var colorArgb = DEFAULT_COLOR_ARGB
        var widthScale = 1f
        if (version >= 2) {
            colorArgb = r.u32().toInt()
            // The record is a plain value, so a hand-built or corrupt one can hold a scale that is
            // not a number or is out of range (see JB-5.03a decision 2). It is brought into range
            // HERE, on the way in, so everything downstream of a decode — replay, the ink renderer,
            // a re-weight — can multiply by it without re-checking.
            val raw = r.f32()
            widthScale = if (raw.isFinite()) {
                raw.coerceIn(StrokeEdit.MIN_WIDTH_SCALE, StrokeEdit.MAX_WIDTH_SCALE)
            } else {
                1f
            }
        }

        // Version 3: the line's place in time, then its group when the flag says it has one. A v1/v2 recording is
        // not in any order of its own (decodeAll gives it its file position); locked is a v3 word, so it is false.
        var seq = 0L
        var groupId: String? = null
        if (version >= 3) {
            seq = r.i64()
            if (seq < 0) throw StrokeCodecException("stroke \"$id\" has seq $seq; a seq is never negative")
            if (flags and FLAG_GROUP != 0) groupId = r.utf8()
        }
        val locked = version >= 3 && flags and FLAG_LOCKED != 0

        val count = r.u32()
        if (count > Int.MAX_VALUE || count * MIN_SAMPLE_BYTES > r.remaining) {
            throw StrokeCodecException("truncated: $count samples do not fit in ${r.remaining} bytes")
        }
        val samples = ArrayList<PenSample>(count.toInt())
        val tilt = flags and FLAG_TILT != 0
        val azimuth = flags and FLAG_AZIMUTH != 0
        val barrel = flags and FLAG_BARREL != 0
        repeat(count.toInt()) {
            val timeMs = r.f64()
            val x = r.f32()
            val y = r.f32()
            val pressure = r.f32()
            val t = if (tilt) r.f32() else Float.NaN
            val a = if (azimuth) r.f32() else Float.NaN
            val b = if (barrel) r.f32() else Float.NaN
            val tool = toolOf(r.u8())
            samples.add(PenSample(x, y, timeMs, pressure, t, a, b, tool))
        }
        return StrokeRecord(id, brushId, seed, smoothing, screenPerDoc, samples, colorArgb, widthScale, seq, locked, groupId)
    }

    /** The version a recording says it is, without decoding the rest; for [decodeAll]'s file-order rule. */
    private fun versionOf(bytes: ByteArray): Int {
        val r = ByteReader(bytes)
        r.bytes(MAGIC_BYTES.size)
        return r.u16()
    }

    /** One u32 count, then each recording prefixed with its own byte length. */
    fun encodeAll(records: List<StrokeRecord>): ByteArray {
        val w = ByteWriter()
        w.u32(records.size.toLong())
        for (r in records) {
            val b = encode(r)
            w.u32(b.size.toLong())
            w.bytes(b)
        }
        return w.toByteArray()
    }

    fun decodeAll(bytes: ByteArray): List<StrokeRecord> {
        val r = ByteReader(bytes)
        val count = r.u32()
        // Every record is at least a magic, a version, two ids and a seed — four bytes of count plus
        // one byte each at the very least. This stops a corrupt count from asking for a huge list.
        if (count > r.remaining) throw StrokeCodecException("truncated: count $count, ${r.remaining} bytes left")
        val out = ArrayList<StrokeRecord>(count.toInt())
        repeat(count.toInt()) {
            // Checked before the read, so a corrupt length is "truncated" and not a negative count.
            val n = r.u32()
            if (n > r.remaining) throw StrokeCodecException("truncated: a record of $n bytes, ${r.remaining} left")
            val bytes = r.bytes(n.toInt())
            val record = decode(bytes)
            // v1/v2 recordings are numbered by where they sit in the file: that WAS their time order (JB-5.20 D1).
            out.add(if (versionOf(bytes) < 3) record.copy(seq = (out.size + 1).toLong()) else record)
        }
        return out
    }

    private fun toolOf(ordinal: Int): Tool = Tool.entries.getOrNull(ordinal)
        ?: throw StrokeCodecException("unknown tool $ordinal")
}

class StrokeCodecException(message: String) : Exception(message)
