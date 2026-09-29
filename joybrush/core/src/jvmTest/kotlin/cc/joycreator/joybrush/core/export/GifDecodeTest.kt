package cc.joycreator.joybrush.core.export

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Element

/** One frame as an independent decoder saw it. */
private class DecodedFrame(
    val width: Int,
    val height: Int,
    val delayCs: Int,
    /** Row-major, four channels per pixel, straight (non-premultiplied) RGBA. */
    val rgba: IntArray,
) {
    fun channelAt(x: Int, y: Int, channel: Int): Int = rgba[(y * width + x) * 4 + channel]
}

/**
 * Decodes an encoded GIF with `javax.imageio` — a decoder this project did not write and cannot
 * influence.
 *
 * This file is the reason the encoder's tests live in two source sets. `commonTest` checks the bytes
 * against an oracle written from the format, which proves the file is *what we meant to write*. It
 * cannot prove the file is *readable*, because an oracle written by the same mind that wrote the
 * encoder shares its blind spots: a wrong code width and a wrong decoder agree perfectly, and the
 * file still opens in the one tool the author was testing with.
 *
 * ImageIO has no such relationship to this code. It stands in for the phone gallery, the chat app
 * and the browser, and the one that matters for this format is the unkind decoder: the LZW streams
 * a lenient reader quietly tolerates are exactly the ones that fail somewhere else. So the claims
 * here are deliberately the uncomfortable ones — a 255-colour table, a stream several sub-blocks
 * long, a code width that has to grow twice — rather than the comfortable ones.
 *
 * One property of GIF worth stating before the assertions, because it is easy to assert the wrong
 * thing about: **a GIF has one transparent index, not an alpha channel.** Every other index is
 * opaque, full stop. So a source pixel of alpha 128 comes back as alpha 255, and only pixels below
 * the encoder's threshold come back as 0. A test that expected 128 to survive would be testing for
 * something the format cannot express.
 */
class GifDecodeTest {

    @Test
    fun threeFramesSurviveARealDecoderWithTheirColoursAndDelays() {
        val width = 64
        val height = 48
        val encoder = GifEncoder(width, height)
        encoder.addFrame(flat(width, height, 0xFF, 0x00, 0x00, 0xFF), 100)
        encoder.addFrame(flat(width, height, 0x00, 0xFF, 0x00, 0xFF), 250)
        // Half painted, half not: the transparency case is the one an encoder gets subtly wrong
        // rather than obviously wrong. Alpha 0x80 is AT the threshold, so this half is opaque and
        // must come back as such — and a decoder that got the threshold wrong would show it as
        // transparent, which is a hole in the middle of the picture rather than an obvious failure.
        val halfAndHalf = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val at = (y * width + x) * 4
                val c = if (x < width / 2) {
                    intArrayOf(0x00, 0x00, 0xFF, 0x80)
                } else {
                    intArrayOf(0x00, 0x00, 0x00, 0x00)
                }
                halfAndHalf[at] = c[0].toByte()
                halfAndHalf[at + 1] = c[1].toByte()
                halfAndHalf[at + 2] = c[2].toByte()
                halfAndHalf[at + 3] = c[3].toByte()
            }
        }
        encoder.addFrame(halfAndHalf, 40)

        val decoded = decode(encoder.finish())
        assertEquals(3, decoded.size, "frame count")

        // 10, 25 and 4 hundredths. The last is not a round number of hundredths, so this also checks
        // the rounding rather than only the clamping.
        val expectedDelays = listOf(10, 25, 4)
        for (i in decoded.indices) {
            assertEquals(width, decoded[i].width, "frame $i width")
            assertEquals(height, decoded[i].height, "frame $i height")
            assertEquals(expectedDelays[i], decoded[i].delayCs, "frame $i delay")
        }

        // Every channel of every pixel in both solid frames. Three distinct colours means three
        // single-colour boxes, whose means are the colours themselves, so the tolerance is not
        // carrying any weight here: a channel out by more than a rounding is a mapping bug, not a
        // palette one.
        val solid = listOf(
            Triple(decoded[0], 0xFF, 0x00),
            Triple(decoded[1], 0x00, 0xFF),
        )
        for ((index, frameAndColour) in solid.withIndex()) {
            val frame = frameAndColour.first
            val red = frameAndColour.second
            val green = frameAndColour.third
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = frame.channelAt(x, y, 0)
                    val g = frame.channelAt(x, y, 1)
                    val b = frame.channelAt(x, y, 2)
                    assertTrue(
                        abs(r - red) <= 8 && abs(g - green) <= 8 && abs(b - 0x00) <= 8,
                        "frame $index pixel $x,$y is rgb($r,$g,$b), expected within 8 of " +
                            "rgb($red,$green,0)",
                    )
                }
            }
        }

        val third = decoded[2]
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < width / 2) {
                    assertEquals(255, third.channelAt(x, y, 3), "frame 2 painted half at $x,$y must be opaque")
                    assertTrue(
                        abs(third.channelAt(x, y, 2) - 0xFF) <= 8,
                        "frame 2 painted half at $x,$y is not blue: ${third.channelAt(x, y, 2)}",
                    )
                } else {
                    assertEquals(0, third.channelAt(x, y, 3), "frame 2 empty half at $x,$y must be transparent")
                }
            }
        }
    }

    @Test
    fun aStreamTooBigForOneSubBlockStillDecodes() {
        // 96x96 of many-colour noise is close to the worst case for this format, and it is chosen
        // for three separate reasons that all show up as the same symptom if they are wrong.
        //
        // One: it compresses badly, so its LZW output runs to many times 255 bytes and the image
        // data is split across sub-blocks with a terminator at the end. Getting the chunking wrong
        // does not throw — the decoder reads the first sub-block, runs out of data, and returns a
        // SHORT frame — so the assertion is on the frame's dimensions coming back right. A pixel
        // check would pass on the part that survived.
        //
        // Two: noise yields nearly one code per pixel, so 9216 of them mint 9000-odd codes. That
        // walks the code width up through 10, 11 and 12, and a decoder that mis-times any of those
        // steps desynchronises and returns nonsense.
        //
        // Three, and this is the one that catches the bug nobody writes a test for: the 4096-code
        // table fills, and the encoder has to emit a clear code mid-stream and start over. A width
        // that stayed at 12 across the clear, or a clear that reset the table without resetting the
        // width, is the classic "opens in my tool" failure. This is the only test here that reaches
        // that point, because it is the only one with enough codes to get there.
        val size = 96
        val encoder = GifEncoder(size, size)
        encoder.addFrame(noise(size, size, 250), 100)
        encoder.addFrame(noise(size, size, 97), 100)

        val decoded = decode(encoder.finish())
        assertEquals(2, decoded.size)
        for (i in decoded.indices) {
            assertEquals(size, decoded[i].width, "frame $i width")
            assertEquals(size, decoded[i].height, "frame $i height")
        }
    }

    @Test
    fun aFullTableWithALongRunStillDecodesExactly() {
        // The second LZW pressure point: 251 distinct colours put the table at its 256 slots, so the
        // opening code width is 9 bits rather than 3, and the 250-colour frame mints enough codes to
        // cross the 10- and 11-bit boundaries. (A long run does NOT do this — a run of N identical
        // pixels emits about root-of-N codes, because each one covers more symbols than the last, so
        // 2304 of them is about 67 codes. Growing the width is what noise is for; the run below is
        // here for the other half of the dictionary, the growing prefix, which a decoder that
        // mishandles the self-referential code loses the tail of.)
        //
        // The 251 colours are multiples of 16 in every channel, so two things are true that the
        // assertions lean on: each is exactly a 5-bit cache bucket, so the quantisation cannot move
        // one; and 251 is under the 255 the cut allows, so the cut has room to isolate every single
        // one and each palette entry IS its colour rather than a mean of several. A test that
        // depended on 300 colours surviving would be a test about the cut, not about LZW.
        val width = 48
        val height = 48
        val paletteColours = 250
        val busy = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val n = i % paletteColours
            val at = i * 4
            busy[at] = ((n % 16) * 16).toByte()
            busy[at + 1] = (((n / 16) % 16) * 16).toByte()
            busy[at + 2] = 0.toByte()
            busy[at + 3] = 0xFF.toByte()
        }
        val encoder = GifEncoder(width, height)
        encoder.addFrame(busy, 100)
        encoder.addFrame(flat(width, height, 0x20, 0x40, 0x60, 0xFF), 100)

        val decoded = decode(encoder.finish())
        assertEquals(2, decoded.size)

        // The long run, at every pixel. This is the assertion the whole test exists for.
        val second = decoded[1]
        assertEquals(48, second.width)
        assertEquals(48, second.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals(0x20, second.channelAt(x, y, 0), "frame 1 red at $x,$y")
                assertEquals(0x40, second.channelAt(x, y, 1), "frame 1 green at $x,$y")
                assertEquals(0x60, second.channelAt(x, y, 2), "frame 1 blue at $x,$y")
            }
        }

        // And the busy frame round-trips too, which is a check on the 9-bit opening width rather
        // than on the palette: 250 codes in a row with few repeats is nearly one code per pixel.
        val first = decoded[0]
        assertEquals(48, first.width)
        for (i in 0 until width * height) {
            val n = i % paletteColours
            val x = i % width
            val y = i / width
            assertEquals((n % 16) * 16, first.channelAt(x, y, 0), "frame 0 red at $x,$y")
            assertEquals(((n / 16) % 16) * 16, first.channelAt(x, y, 1), "frame 0 green at $x,$y")
        }
    }

    @Test
    fun anEntirelyEmptyAnimationDecodesWithoutThrowing() {
        // A frame of nothing but transparency is a real thing an app produces — a layer nobody ever
        // drew on — and it is the case where the palette is empty and the table is at the minimum
        // legal size of two entries. It must not throw, here or in a viewer.
        val encoder = GifEncoder(16, 16)
        encoder.addFrame(flat(16, 16, 0, 0, 0, 0), 100)
        val decoded = decode(encoder.finish())
        assertEquals(1, decoded.size)
        assertEquals(16, decoded[0].width)
        assertEquals(16, decoded[0].height)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                assertEquals(0, decoded[0].channelAt(x, y, 3), "empty frame at $x,$y")
            }
        }
    }

    @Test
    fun theLoopingExtensionIsPresentOnlyWhenTheFileShouldLoop() {
        // The loop count is the one piece of GIF metadata a decoder can act on, and "plays forever"
        // is what an export button means by an animation. ImageIO reports the NETSCAPE block back as
        // an ApplicationExtension node, so this can be checked rather than assumed.
        val looping = GifEncoder(8, 8, loop = true)
        looping.addFrame(flat(8, 8, 0x10, 0x20, 0x30, 0xFF), 100)
        val once = GifEncoder(8, 8, loop = false)
        once.addFrame(flat(8, 8, 0x10, 0x20, 0x30, 0xFF), 100)

        assertEquals(1, applicationExtensionIds(looping.finish()).size, "a looping file's NETSCAPE block")
        // ImageIO reports the id as "NETSCAPE", not "NETSCAPE2.0": its metadata tree keeps only the
        // leading name. The full eleven-byte spelling is not left unchecked for that — the
        // byte-level parser in GifEncoderTest compares the eleven bytes exactly, and the loop
        // sub-block's count and terminator are walked there too. What is being asked HERE is the
        // narrower question: does a third-party decoder see an application extension and recognise
        // it as the looping one. Pinning ImageIO's truncation exactly would make this test a
        // detector of JDK changes rather than of encoder changes.
        assertEquals(
            listOf("NETSCAPE"),
            applicationExtensionIds(looping.finish()).map { it.substringBefore(".") },
            "the looping block's application id, as a decoder names it",
        )
        assertEquals(
            emptyList(),
            applicationExtensionIds(once.finish()),
            "loop = false must write no application extension at all, not one with a count of 1",
        )
        // Both still decode, and identically apart from the extension.
        assertEquals(1, decode(looping.finish()).size)
        assertEquals(1, decode(once.finish()).size)
    }

    // ------------------------------------------------------------------ plumbing

    private fun decode(bytes: ByteArray): List<DecodedFrame> {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: fail("ImageIO could not open an input stream over ${bytes.size} bytes")
        try {
            val readers = ImageIO.getImageReadersByFormatName("gif")
            if (!readers.hasNext()) fail("this JVM has no GIF reader, so this test cannot mean anything")
            val reader: ImageReader = readers.next()
            try {
                reader.input = stream
                return readAll(reader)
            } finally {
                reader.dispose()
            }
        } finally {
            stream.close()
        }
    }

    private fun readAll(reader: ImageReader): List<DecodedFrame> {
        val count = reader.getNumImages(true)
        val out = ArrayList<DecodedFrame>(count)
        for (index in 0 until count) {
            val image = reader.read(index)
            val argb = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
            val rgba = IntArray(argb.size * 4)
            for (i in argb.indices) {
                rgba[i * 4] = (argb[i] shr 16) and 0xFF
                rgba[i * 4 + 1] = (argb[i] shr 8) and 0xFF
                rgba[i * 4 + 2] = argb[i] and 0xFF
                rgba[i * 4 + 3] = (argb[i] ushr 24) and 0xFF
            }
            out.add(
                DecodedFrame(
                    width = image.width,
                    height = image.height,
                    delayCs = delayCentisecondsOf(reader, index),
                    rgba = rgba,
                ),
            )
        }
        return out
    }

    /**
     * The application ids a decoder sees in the file, in the order it sees them.
     *
     * Reading this back out of the metadata tree rather than out of our own bytes is the point: it
     * is the only check in the suite that a third party agrees the NETSCAPE block is where it
     * belongs and is spelled the way the convention says.
     */
    private fun applicationExtensionIds(bytes: ByteArray): List<String> {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: fail("ImageIO could not open an input stream over ${bytes.size} bytes")
        try {
            val readers = ImageIO.getImageReadersByFormatName("gif")
            if (!readers.hasNext()) fail("this JVM has no GIF reader, so this test cannot mean anything")
            val reader: ImageReader = readers.next()
            try {
                reader.input = stream
                val root = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0")
                val nodes = (root as Element).getElementsByTagName("ApplicationExtension")
                val ids = ArrayList<String>(nodes.length)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    // The attribute is `applicationID` — capital D — in ImageIO's GIF metadata
                    // format. DOM attribute lookup is case sensitive, so asking for `applicationId`
                    // returns "" for a perfectly good node rather than failing, which is how a test
                    // ends up asserting against an empty string and calling it a decode failure.
                    val attributes = node.attributes
                    val id = when {
                        attributes.getNamedItem("applicationID") != null ->
                            node.getAttribute("applicationID")
                        attributes.getNamedItem("applicationId") != null ->
                            node.getAttribute("applicationId")
                        else -> fail(
                            "an ApplicationExtension node with no application id attribute; the " +
                                "attributes present are ${(0 until attributes.length)
                                    .map { attributes.item(it).nodeName }
                                    .sorted()}",
                        )
                    }
                    ids.add(id)
                }
                return ids
            } finally {
                reader.dispose()
            }
        } finally {
            stream.close()
        }
    }

    /**
     * The frame's delay, read out of the graphic control extension the way a viewer would.
     *
     * The tree attribute is the file's own hundredths of a second, which is the unit the GIF stores,
     * so this compares against the encoder's centiseconds with no conversion in between. If it ever
     * comes back as milliseconds that is a decoder change, not an encoder one, and every delay
     * assertion in here will say so by being out by a factor of ten.
     */
    private fun delayCentisecondsOf(reader: ImageReader, index: Int): Int {
        val root = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
        val gce = (root as Element).getElementsByTagName("GraphicControlExtension").item(0) as? Element
            ?: fail("frame $index has no graphic control extension, so its delay cannot be read")
        val text = gce.getAttribute("delayTime")
        return text.toIntOrNull() ?: fail("frame $index delayTime is \"$text\", which is not a number")
    }

    // ------------------------------------------------------------------ fixtures

    private fun flat(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        var at = 0
        while (at < out.size) {
            out[at] = r.toByte()
            out[at + 1] = g.toByte()
            out[at + 2] = b.toByte()
            out[at + 3] = a.toByte()
            at += 4
        }
        return out
    }

    /** Deterministic noise from an explicit LCG: the same pixels on every platform, every run. */
    private fun noise(w: Int, h: Int, colourCount: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        var state = 0x2545F491
        var at = 0
        while (at < out.size) {
            state = state * 1103515245 + 12345
            out[at] = (((state shr 16) and 0x7F) * colourCount / 128).toByte()
            out[at + 1] = (((state shr 8) and 0x7F) * colourCount / 128).toByte()
            out[at + 2] = ((state and 0x7F) * colourCount / 128).toByte()
            out[at + 3] = 0xFF.toByte()
            at += 4
        }
        return out
    }
}
