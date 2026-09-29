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
 *
 * [VERSION] is 2 and [OLDEST_VERSION] is 1: the writer always writes the newest, and the reader
 * accepts every version in between, because a recording written by the build that drew it must
 * still open. The magic stays [MAGIC] — it names the FORMAT FAMILY ("a Joy Brush stroke"), and a
 * v2 recording is still one. A version outside the range is refused by number, as JB-0.04's
 * decision 4 says: this build does not know what a v3 recording means, and guessing is how a
 * drawing is destroyed.
 */
object StrokeCodec {
    const val MAGIC = "JBS1"

    /** The newest recording this build writes, and the newest it reads. */
    const val VERSION: Int = 2

    /** The oldest recording this build reads. See the table above. */
    const val OLDEST_VERSION: Int = 1

    /** What a version-1 recording is read as: black, at the brush's own size. */
    const val DEFAULT_COLOR_ARGB: Int = 0xFF000000.toInt()

    private const val FLAG_TILT = 1
    private const val FLAG_AZIMUTH = 1 shl 1
    private const val FLAG_BARREL = 1 shl 2
    private const val KNOWN_FLAGS = FLAG_TILT or FLAG_AZIMUTH or FLAG_BARREL

    private val MAGIC_BYTES = MAGIC.encodeToByteArray()

    /** The smallest a sample can be: timeMs, x, y, pressure, tool. Used to sanity-check counts. */
    private const val MIN_SAMPLE_BYTES = 8 + 4 + 4 + 4 + 1

    /** Writes [VERSION]. Always the newest layout — an old recording is upgraded by being re-saved. */
    fun encode(record: StrokeRecord): ByteArray {
        val w = ByteWriter()
        w.bytes(MAGIC_BYTES)
        w.u16(VERSION)

        var flags = 0
        if (record.samples.any { it.hasTilt }) flags = flags or FLAG_TILT
        if (record.samples.any { it.hasAzimuth }) flags = flags or FLAG_AZIMUTH
        if (record.samples.any { it.hasBarrel }) flags = flags or FLAG_BARREL
        w.u16(flags)

        w.utf8(record.id)
        w.utf8(record.brushId)
        w.i64(record.seed)
        w.f32(record.smoothing)
        w.f32(record.screenPerDoc)
        w.u32(record.colorArgb.toLong() and 0xFFFF_FFFFL)
        w.f32(record.widthScale)
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
        if (flags and KNOWN_FLAGS.inv() != 0) {
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
        return StrokeRecord(id, brushId, seed, smoothing, screenPerDoc, samples, colorArgb, widthScale)
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
            out.add(decode(r.bytes(n.toInt())))
        }
        return out
    }

    private fun toolOf(ordinal: Int): Tool = Tool.entries.getOrNull(ordinal)
        ?: throw StrokeCodecException("unknown tool $ordinal")
}

class StrokeCodecException(message: String) : Exception(message)
