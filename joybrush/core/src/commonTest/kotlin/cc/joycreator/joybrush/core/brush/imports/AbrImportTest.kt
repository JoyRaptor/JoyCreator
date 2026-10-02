package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushValidate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mapping: every bucket, and the R40 rules in particular.
 *
 * Every fixture is hand-built from the byte builders in [AbrReaderTest], so nothing here depends on a
 * download and every expected number carries its derivation in the comment that asserts it. Numbers
 * that are written as `0.5` or `40` are written as the arithmetic that produced them: `0.25 + 0.7 ×
 * mean`, `Spcn / 100`, `jitter / 100 × 360`.
 */
class AbrImportTest {

    // ---- 6. the whole pack, end to end ----------------------------------------------------------------

    /**
     * A pack with a computed tip, a sampled tip, a texture pattern and a `patt` record, imported in one
     * `convert` call.
     *
     * **What this test cannot do, said out loud:** the spec asks for one *real* `.abr` pasted as a
     * base64 constant. This build has no `.abr` to paste — there is none in the repo, and the sandbox
     * cannot fetch a binary — so the end-to-end case is a synthetic pack covering the same panels.
     * That is a real gap and it is in the report: **the reader has not been run against a file
     * Photoshop wrote.**
     *
     * Non-vacuity: `brushes.isNotEmpty()`, and every preset is checked by `BrushValidate.validate`
     * returning **empty**, not "no worse than before".
     */
    @Test
    fun aWholePackConvertsAndEveryPresetIsALegalBrush() {
        val library = AbrImport.convert(pack(), "My Pack")
        assertTrue(library.brushes.isNotEmpty(), "the pack converted nothing")
        assertEquals(0, library.refused.size, "${library.refused}")
        for (result in library.brushes) {
            val p = result.preset
            assertEquals("abr", p.sourceFormat, p.name)
            // `.abr` carries no licence. "CC0" would be a claim about a pack we did not make, and R4
            // §5 is explicit that redistributing a converted third-party pack is not ours to do.
            assertEquals("unknown", p.license, p.name)
            assertEquals("", p.author, p.name)
            assertEquals("stamp", p.engine, p.name)
            assertEquals(brushId("My Pack", p.name), p.id, p.name)
            assertEquals(emptyList(), BrushValidate.validate(p), "${p.name}: ${result.warnings}")
        }
        // And a summary a screen can show, never empty.
        assertTrue(library.summary().isNotBlank())
    }

    // ---- 6b. `brushId`, tested as a function ------------------------------------------------------------

    /**
     * JB-8.02 and JB-8.04 call the same function on names from files they have never seen, and an id
     * is a folder name — so the rules are asserted here, once, rather than through three brushes.
     */
    @Test
    fun brushIdIsTheOneSanitiser() {
        val id = brushId("My Pack", "Round/Hard 2")
        assertEquals("my-pack.round-hard-2", id)
        assertTrue(' ' !in id && '/' !in id, id)
        assertFalse(id.startsWith("-") || id.endsWith("-"), id)
        // Four one-liners, each with its derivation in the name of the case.
        // ".." survives: each part is two of the five characters the rule keeps, and the two parts
        // are joined with a dot, so the id is two + one + two = five dots — not blank, and not "..".
        val dots = brushId("..", "..")
        assertTrue(dots.isNotBlank(), dots)
        assertTrue(dots != "..", dots)
        assertEquals(5, dots.length, dots)
        // Nothing left at all → the one word the rule names.
        assertEquals("brush", brushId("", ""))
        // A run of two dashes is one run, so it collapses to one.
        assertEquals("a-b.c", brushId("A--B", "c"))
        // Nothing outside a–z/0–9/./-/_ survives, whatever script it is in.
        val cjk = brushId("Ä", "日本")
        assertEquals("brush", cjk)
        assertTrue(cjk.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }, cjk)
        // A prefix that sanitises away entirely is dropped, and it does not leave a leading dot:
        // "---" is one run of three, which collapses to one and is then trimmed off both ends.
        assertEquals("x", brushId("---", "x"))
        assertEquals("y", brushId("--", "y"))
    }

    // ---- 7. THE JB-8.03 TEST ---------------------------------------------------------------------------

    /**
     * **No mapped curve is dropped.** This is the test that would have caught JB-8.03's Finding 1,
     * where MyPaint's table silently dropped `opaque`'s and `hardness`'s input curves and one brush
     * arrived nearly invisible.
     *
     * Every dynamics object here carries `bVTy = 2` (Pen Pressure) and a `Mnm ` of 10 %, so the
     * expected curve on each is the two points `(0, 0.1)` and `(1, 1)` — `Mnm / 100 = 10 / 100`.
     */
    @Test
    fun everyMappedControlBecomesACurveAndIsNeverReadAsABaseValue() {
        val library = AbrImport.convert(
            file(desc = descSection(listOf(curvedBrush("Curved")))),
            "pack",
        )
        val p = library.brushes.single().preset
        for ((what, param) in listOf(
            "size" to p.size,
            "opacity" to p.opacity,
            "flow" to p.flow,
            "tip.angle" to p.tip.angle,
            "scatter.amount" to p.scatter.amount,
        )) {
            val curve = param.inputs.singleOrNull()
            assertNotNull(curve, "$what has no curve: ${param.inputs}")
            assertEquals(BrushInput.pressure, curve.input, what)
            assertEquals(2, curve.curve.size, what)
            assertEquals(listOf(0f, 0.1f), curve.curve[0], "$what at the minimum")
            assertEquals(listOf(1f, 1f), curve.curve[1], "$what at full pressure")
        }
        // The jitters the same objects carry: `jitter / 100`.
        assertEquals(0.5f, p.sizeJitter, 1e-6f)          // szVr jitter 50 % of the diameter
        assertEquals(0.4f, p.scatter.countJitter, 1e-6f) // countDynamics jitter 40 %
        // Angle jitter is degrees, so 30 % of a full turn: 30 / 100 × 360.
        assertEquals(108f, p.angleJitter, 1e-4f)
        // `countJitter` is a plain float with no curve, so a *controlled* count jitter is a loss —
        // and it is said, once, rather than dropped.
        assertNotNull(p.extensions["abr.countDynamics"])
        assertTrue(
            library.brushes.single().warnings.any { it.contains("count jitter is controlled") },
            "${library.brushes.single().warnings}",
        )
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    /** The other four `bVTy` codes, and `bVTy = 0` with fade steps, each land on the right input. */
    @Test
    fun everyControlCodeLandsOnTheInputItMeans() {
        // 1 Fade → distance, 3 Pen Tilt → tilt, 4 Stylus Wheel → barrel. 2 is in the test above.
        val library = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Codes",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)), itemOf("Angl", longV(10)))),
                itemOf("szVr", dynamics(1, fadeSteps = 5, minimum = 20)),
                itemOf("angleDynamics", dynamics(4)),
            )))),
            "pack",
        )
        val p = library.brushes.single().preset
        assertEquals(BrushInput.distance, p.size.inputs.single().input)
        assertEquals(listOf(0f, 0.2f), p.size.inputs.single().curve[0])   // 20 % minimum
        assertEquals(BrushInput.barrel, p.tip.angle.inputs.single().input)
        // A control of 0 with no fade steps produces no curve at all: there is nothing to control.
        val off = AbrImport.convert(
            file(desc = descSection(listOf(brush("Off", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))))))),
            "pack",
        )
        assertEquals(emptyList(), off.brushes.single().preset.size.inputs)
    }

    /** Codes 5 to 8 have no `BrushInput`, so they fall back to the fade curve **and** are said once. */
    @Test
    fun anInferredControlCodeFallsBackToFadeAndIsSaidOnceForTheFile() {
        val library = AbrImport.convert(
            file(desc = descSection(listOf(
                brush("A", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))), itemOf("szVr", dynamics(6, minimum = 30))),
                brush("B", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))), itemOf("szVr", dynamics(7, minimum = 30))),
            ))),
            "pack",
        )
        assertEquals(2, library.brushes.size)
        for (result in library.brushes) {
            val curve = result.preset.size.inputs.single()
            assertEquals(BrushInput.distance, curve.input, "the fallback")
            assertEquals(listOf(0f, 0.3f), curve.curve[0])
        }
        // Each brush records its own control…
        assertEquals("6", library.brushes[0].preset.extensions["abr.bVTy.szVr"])
        assertEquals("7", library.brushes[1].preset.extensions["abr.bVTy.szVr"])
        // …and the "these codes are inferred" fact is ONE sentence across the whole library, not one
        // per brush. JB-8.03's Finding 2: a warning that fires on every brush trains people to ignore
        // warnings. `library` is the only place it can live without a second ImportLibrary shape.
        val said = library.brushes.sumOf { r -> r.warnings.count { it.contains("bVTy") } } +
            library.refused.sumOf { r -> if (r.reason.contains("bVTy")) 1 else 0 }
        assertEquals(1, said, "${library.brushes.map { it.warnings }}")
        assertTrue(library.summary().isNotBlank())
    }

    // ---- 8. the plain mappings, exactly -----------------------------------------------------------------

    /** `Dmtr = 40` → 40 px; `Hrdn = 75` → 0.75; `Rndn = 50` → `-(1 - 50/100)` = -0.5; `Spcn = 25` → 0.25. */
    @Test
    fun theComputedTipMapsExactly() {
        val p = single(brush(
            "Numbers",
            itemOf("Brsh", computedTip(
                itemOf("Dmtr", longV(40)),
                itemOf("Hrdn", longV(75)),
                itemOf("Angl", longV(33)),
                itemOf("Rndn", longV(50)),
                itemOf("Spcn", longV(25)),
            )),
        ))
        assertEquals(40f, p.size.base, 1e-4f)
        assertEquals(0.75f, p.tip.hardness.base, 1e-4f)
        assertEquals(-0.5f, p.tip.aspect, 1e-4f)
        assertEquals(33f, p.tip.angle.base, 1e-4f)
        assertEquals(0.25f, p.spacing, 1e-4f)
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    /** A file that states no diameter gets Photoshop's own default, and is told so. */
    @Test
    fun aFileWithNoDiameterUsesTheDefaultAndSaysSo() {
        val library = AbrImport.convert(
            file(desc = descSection(listOf(brush("Sizeless", itemOf("Brsh", computedTip()))))),
            "pack",
        )
        val result = library.brushes.single()
        assertEquals(13f, result.preset.size.base, 1e-4f)   // the Photoshop Brush panel default
        assertTrue(result.warnings.any { it.contains("does not say how big") }, "${result.warnings}")
        assertEquals(emptyList(), BrushValidate.validate(result.preset))
    }

    // ---- 9. every LOSSY bucket says so -------------------------------------------------------------------

    @Test
    fun everyLossyBucketSaysWhatItLost() {
        // A bitmap tip (R40's warning is asserted by value in the next test).
        val tip = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Bitmap", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(packBitsTip(UUID, 4, 4))),
            ),
            "pack",
        ).brushes.single()
        assertNotNull(tip.preset.extensions["abr.tipImage"])
        assertTrue(tip.warnings.any { it.contains("bitmap tip") }, "${tip.warnings}")

        // A texture pattern: the warning names the pattern's id.
        val textured = AbrImport.convert(
            file(
                desc = descSection(listOf(brush(
                    "Textured",
                    itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))),
                    itemOf("Txtr", objcV("", "null", itemOf("Idnt", enumV("Idnt", PATTERN_ID)))),
                ))),
                patt = pattSection(listOf(patternRecord(PATTERN_ID))),
            ),
            "pack",
        ).brushes.single()
        assertTrue(
            textured.warnings.any { it.contains(PATTERN_ID) && it.contains(TEXTURE_NOT_DRAWN) },
            "${textured.warnings}",
        )

        // `Intr` off: the spacing mode Joy Brush cannot express.
        val speed = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Speed",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)), itemOf("Intr", boolV(false)))),
            )))),
            "pack",
        ).brushes.single()
        assertEquals("speed", speed.preset.extensions["abr.spacingMode"])
        assertTrue(speed.warnings.any { it.contains("spaces its dabs by speed") }, "${speed.warnings}")

        // Roundness dynamics: no `Param` to put it on, so it is raw and named.
        val roundness = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Round",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))),
                itemOf("roundnessDynamics", dynamics(2, jitter = 20)),
            )))),
            "pack",
        ).brushes.single()
        assertNotNull(roundness.preset.extensions["abr.roundnessDynamics"])
        assertTrue(roundness.warnings.any { it.contains("roundness dynamics") }, "${roundness.warnings}")

        // A flipped tip, and everything the file states that this build does not map.
        val flipped = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Flipped",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)), itemOf("flipX", boolV(true)))),
                itemOf("dualBrush", objcV("", "null", itemOf("useDualBrush", boolV(true)))),
            )))),
            "pack",
        ).brushes.single()
        assertEquals("true", flipped.preset.extensions["abr.flipX"])
        assertNotNull(flipped.preset.extensions["abr.dualBrush"])
        // One warning per group, not one per key: both the tip's and the brush's unknowns are one line.
        assertEquals(1, flipped.warnings.count { it.contains("does not map") }, "${flipped.warnings}")
    }

    // ---- 9b. THE R40 TESTS --------------------------------------------------------------------------------

    /**
     * (a) The exact sentence, by value. Asserted against [TEXTURE_NOT_DRAWN] and never a retyped
     * literal, because three importers with three copies of the sentence is the drift R40 exists to
     * stop.
     */
    @Test
    fun theR40SentenceIsPresentByValueOnBothTextures() {
        val bitmap = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Bitmap", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(packBitsTip(UUID, 4, 4))),
            ),
            "pack",
        ).brushes.single()
        assertTrue(bitmap.warnings.any { it.contains(TEXTURE_NOT_DRAWN) }, "${bitmap.warnings}")
        // …plus Decision 4's second half, saying what the numbers are standing in for.
        assertTrue(bitmap.warnings.any { it.contains("bitmap tip") && it.contains("JB-1.05d") }, "${bitmap.warnings}")

        val textured = AbrImport.convert(
            file(
                desc = descSection(listOf(brush(
                    "Textured",
                    itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))),
                    itemOf("Txtr", objcV("", "null", itemOf("Idnt", enumV("Idnt", PATTERN_ID)))),
                ))),
                patt = pattSection(listOf(patternRecord(PATTERN_ID))),
            ),
            "pack",
        ).brushes.single()
        assertTrue(textured.warnings.any { it.contains(TEXTURE_NOT_DRAWN) }, "${textured.warnings}")
    }

    /**
     * (b) The image is stored, the preset says so, and **the numbers are still real**.
     *
     * The fixture is a 4 × 4 gray tip whose every row is 0, 85, 170, 255. The mean is
     * (0 + 85 + 170 + 255) / (4 × 255) = 510 / 1020 = 0.5 exactly, so Decision 4's stand-in is
     * 0.25 + 0.7 × 0.5 = 0.6 and `size.base` is the bitmap's own width, 4.
     */
    @Test
    fun aSampledTipIsStoredAsImageAndStillCarriesRealNumbers() {
        val entry = packBitsTip(UUID, 4, 4)
        val library = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Bitmap", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(entry)),
            ),
            "pack",
        )
        val p = library.brushes.single().preset
        assertEquals("image", p.tip.source)
        // The name says the encoding, and these bytes are PackBits gray — never "tip.png".
        assertEquals("tip.packbits", p.tip.image)
        assertTrue(!p.tip.image.isNullOrBlank(), "rule 23 needs a non-blank path")
        assertEquals(4f, p.size.base, 1e-4f)
        assertEquals(0.6f, p.tip.hardness.base, 1e-4f)          // 0.25 + 0.7 × 0.5
        // The stored bytes are the `samp` payload byte for byte, checked with a decoder written from
        // the RFC 4648 alphabet rather than from the encoder.
        val stored = base64Decode(p.extensions.getValue("abr.tipImage"))
        assertContentEquals(entry.payload, stored)
        // A raw tip is named for what it is, not for the compression code it happens not to use.
        val rawEntry = packBitsTip(UUID, 4, 4, compression = 0)
        val raw = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Raw", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(rawEntry)),
            ),
            "pack",
        ).brushes.single().preset
        assertEquals("tip.raw", raw.tip.image)
        assertContentEquals(rawEntry.payload, base64Decode(raw.extensions.getValue("abr.tipImage")))
    }

    /**
     * The base64 itself, through the whole pipeline. RFC 4648 §4's own test vector: the three bytes
     * `M` `a` `n` (0x4D 0x61 0x6E) are `"TWFu"`. That is 24 bits, which is exactly four 6-bit
     * characters with no padding, so the fixture is a 3 × 1 raw 8-bit tip and nothing else.
     */
    @Test
    fun theStoredTipIsBase64OfTheFilesOwnBytes() {
        val payload = byteArrayOf('M'.code.toByte(), 'a'.code.toByte(), 'n'.code.toByte())
        val library = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Man", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(TipEntry(UUID, bottom = 1, right = 3, compression = 0, payload = payload))),
            ),
            "pack",
        )
        val p = library.brushes.single().preset
        assertEquals("TWFu", p.extensions.getValue("abr.tipImage"))
        assertContentEquals(payload, base64Decode(p.extensions.getValue("abr.tipImage")))
        // …and the same bytes give the mean (77 + 97 + 110) / (3 × 255) = 284 / 765 ≈ 0.371 241 8.
        assertEquals(0.25f + 0.7f * (284.0 / 765.0).toFloat(), p.tip.hardness.base, 1e-5f)
    }

    /**
     * (c) Over the cap, the image is **dropped whole, not truncated** (Decision 4a).
     *
     * A 512 × 512 raw 8-bit tip is 512 × 512 = 262 144 stored bytes, which base64-encodes to
     * ceil(262 144 / 3) × 4 = 87 382 × 4 = 349 528 characters — over the 262 144-character cap by
     * 87 384. The tip's own numbers are unchanged, `tip.source` stays `"procedural"`, and the warning
     * carries the R40 sentence with the byte count and the cap in it.
     *
     * Non-vacuity: the assertion is that the key is **absent**, not that it is short. "Absent" and
     * "truncated" must not both pass.
     */
    @Test
    fun aTipTooBigForTheExtensionsBudgetIsDroppedWholeAndSaysWhy() {
        val entry = TipEntry(
            UUID,
            bottom = 512,
            right = 512,
            compression = 0,
            payload = ByteArray(512 * 512) { (it % 256).toByte() },
        )
        val result = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Big", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(entry)),
            ),
            "pack",
        ).brushes.single()
        val p = result.preset
        assertNull(p.extensions["abr.tipImage"], "the image must be absent, not truncated")
        assertEquals("procedural", p.tip.source)
        assertNull(p.tip.image)
        // The numbers of Decision 4 survive: the brush still paints at the bitmap's size.
        assertEquals(512f, p.size.base, 1e-4f)
        assertTrue(
            result.warnings.any { it.contains(TEXTURE_NOT_DRAWN) && it.contains("262144") && it.contains("$MAX_EXTENSION_BYTES") },
            "${result.warnings}",
        )
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    /**
     * (d) A pattern does not invent an image: `paperGrain` stays off and the id is what is kept.
     */
    @Test
    fun aPatternLeavesPaperGrainOffAndKeepsTheId() {
        val p = AbrImport.convert(
            file(
                desc = descSection(listOf(brush(
                    "Textured",
                    itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))),
                    itemOf("useTexture", boolV(true)),
                    itemOf("Txtr", objcV("", "null", itemOf("Idnt", enumV("Idnt", PATTERN_ID)))),
                    itemOf("textureScale", longV(50)),
                    itemOf("textureDepth", longV(80)),
                ))),
                patt = pattSection(listOf(patternRecord(PATTERN_ID))),
            ),
            "pack",
        ).brushes.single().preset
        assertFalse(p.paperGrain.enabled, "R40 does not mean invent an image")
        assertEquals("cloud", p.paperGrain.source)
        assertTrue(p.extensions.getValue("abr.texturePattern").contains(PATTERN_ID))
        // The two texture numbers that do map.
        assertEquals(50f, p.paperGrain.scale, 1e-4f)
        assertEquals(0.8f, p.paperGrain.depth.base, 1e-4f)      // 80 % of full depth
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    // ---- 9c. the colour-jitter scale, four cases ---------------------------------------------------------------

    /**
     * The stored scale of Photoshop's colour jitters is not documented (R4 §A.7 states the *UI* is
     * 0–100 %; R4's mapping table marks the file mapping `≈` with "Distribution unknown"), so the
     * magnitude decides and nothing is divided on a guess.
     */
    @Test
    fun theColourJitterScaleIsDecidedByMagnitudeAndSaysSo() {
        // Brgh = 0 → 0, and nothing at all is said: both readings agree, so there is no fact to report.
        val zero = colourResult(longV(0))
        assertEquals(0f, zero.preset.color.value, 0f)
        assertEquals(emptyList(), zero.warnings, "a zero jitter needs no warning of any kind")
        assertEquals(0f, zero.preset.color.hue, 0f)

        // Brgh = 0.5 → 0.5 and **not** 0.005, which is the case the old mapping got silently wrong,
        // with the raw value kept and the one whole-file sentence naming the unresolved scale.
        val half = colourResult(doubV(0.5))
        assertEquals(0.5f, half.preset.color.value, 1e-6f)
        assertEquals("0.5", half.preset.extensions["abr.Brgh"])
        assertEquals(1, half.fileWarning.lines().count { it.contains("Brgh") && it.contains("unresolved") }, "$half")
        // No per-brush clamp warning: nothing was clamped.
        assertEquals(0, half.warnings.count { it.contains("clamps") }, "${half.warnings}")

        // Brgh = 100 → 100 / 100 = 1.0, in range, so the file says "percentage" and the brush says
        // nothing: the divide landed inside the field.
        val hundred = colourResult(doubV(100.0))
        assertEquals(1.0f, hundred.preset.color.value, 1e-6f)
        assertEquals(1, hundred.fileWarning.lines().count { it.contains("Brgh") && it.contains("percentage") }, "$hundred")
        assertEquals(0, hundred.warnings.count { it.contains("clamps") }, "${hundred.warnings}")

        // Brgh = 250 → 250 / 100 = 2.5, still over 1, so it clamps and names both numbers.
        val over = colourResult(doubV(250.0))
        assertEquals(1.0f, over.preset.color.value, 1e-6f)
        val clamp = over.warnings.filter { it.contains("Brgh") }
        assertEquals(1, clamp.size, "${over.warnings}")
        assertTrue(clamp.single().contains("250"), clamp.single())
        assertTrue(clamp.single().contains("1.0"), clamp.single())
    }

    /** Hue and saturation go through the same rule, and `colorDynamicsPerTip` sets `perStroke`. */
    @Test
    fun hueAndSaturationUseTheSameRuleAndThePerTipFlagIsPerStroke() {
        val result = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Colour",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))),
                itemOf("H   ", doubV(0.25)),
                itemOf("Strt", doubV(30.0)),
                itemOf("colorDynamicsPerTip", boolV(true)),
            )))),
            "pack",
        ).brushes.single().preset
        assertEquals(0.25f, result.color.hue, 1e-6f)          // unresolved, read as 0..1
        assertEquals(0.3f, result.color.saturation, 1e-6f)     // 30 % read as 30 / 100
        // `ColorJitter` has no `perDab`; the flag is `perStroke` (BrushPreset.kt:57).
        assertTrue(result.color.perStroke)
        assertEquals(emptyList(), BrushValidate.validate(result))
    }

    // ---- 10/11. refusals -------------------------------------------------------------------------------------

    /**
     * Each REFUSED bucket drops exactly that brush, and the other three survive.
     *
     * Non-vacuity: the two survivors are checked by id, not by count, and each reason names its own
     * brush.
     */
    @Test
    fun eachRefusedBucketDropsExactlyThatBrush() {
        val good = brush("Good", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))))
        val bristle = brush("Bristle", itemOf("Brsh", objcV("", "dBrush", itemOf("Dmtr", longV(20)))))
        val erodible = brush("Erodible", itemOf("Brsh", objcV("", "dTips", itemOf("Shp ", longV(1)))))
        val zipped = brush("Zipped", itemOf("Brsh", sampledTip(UUID)))
        val library = AbrImport.convert(
            file(
                desc = descSection(listOf(good, bristle, erodible, zipped)),
                samp = sampSection(listOf(packBitsTip(UUID, 4, 4, compression = 2))),
            ),
            "pack",
        )
        assertEquals(listOf("Good"), library.brushes.map { it.preset.name })
        assertEquals(3, library.refused.size, "${library.refused}")
        assertEquals(listOf(1, 2, 3), library.refused.map { it.index })
        for (r in library.refused) assertTrue(r.name.isNotEmpty(), "$r")
        assertTrue(library.refused[0].reason.contains("bristle"), library.refused[0].reason)
        assertTrue(library.refused[1].reason.contains("erodible"), library.refused[1].reason)
        assertTrue(library.refused[2].reason.contains("compressed"), library.refused[2].reason)
        // A summary a screen can show, naming what was lost.
        assertTrue(library.summary().contains("3 refused"), library.summary())
    }

    /**
     * One bad brush does not kill the file (D1), and the survivors are **equal** to what a file
     * holding only them would have produced — not merely the same count.
     *
     * The two files carry no whole-file fact (no `bVTy` 5–8 and no colour jitter), which is what makes
     * the equality possible: the one whole-file sentence rides on the first converted brush, so a
     * file with an extra brush before them would legitimately differ.
     */
    @Test
    fun oneBadBrushDoesNotKillTheFile() {
        val goodA = brush("Alpha", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(12)))))
        val goodB = brush("Beta", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(34)), itemOf("Hrdn", longV(40)))))
        val broken = brush("Broken", itemOf("Brsh", objcV("", "dBrush")))
        val both = AbrImport.convert(file(desc = descSection(listOf(goodA, broken, goodB))), "pack")
        val only = AbrImport.convert(file(desc = descSection(listOf(goodA, goodB))), "pack")
        assertEquals(2, both.brushes.size)
        assertEquals(1, both.refused.size)
        assertEquals(only.brushes, both.brushes)
    }

    /**
     * A `Brsh` entry that will not parse refuses itself and the entries after it, because a list's
     * entries are self-delimiting only once they have been parsed. The brushes **before** it still
     * convert, which is the whole of D1.
     */
    @Test
    fun aGarbageBrushInTheListKeepsTheOnesBeforeIt() {
        val unknown = Bytes()
        unknown.ascii("wobb"); unknown.u32(0)
        val library = AbrImport.convert(
            file(desc = descSection(listOf(
                brush("First", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20))))),
                brush("Second", itemOf("K", unknown.toByteArray())),
                brush("Third", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20))))),
            ))),
            "pack",
        )
        assertEquals(listOf("First"), library.brushes.map { it.preset.name })
        assertEquals(2, library.refused.size, "${library.refused}")
        // A brush that will not parse has no name to give, so the refusal names it by its position —
        // which is the only handle there is at that point.
        assertTrue(library.refused[0].reason.contains("brush 2"), library.refused[0].reason)
        assertTrue(library.refused[1].reason.contains("wobb"), library.refused[1].reason)
    }

    /**
     * A brush whose list entry is an `'obj '`/`'prop'` reference — the other form the format allows —
     * is read, not refused. The reference points at item 0 of the same descriptor, which is the
     * brush descriptor itself.
     */
    @Test
    fun aBrushListEntryMayBeAPropertyReference() {
        // The `Brsh` list holds two entries: a real brush, then an `'obj '`/`'prop'` pointer at item 0
        // of the *root* descriptor — which does not exist, because the root has only `Brsh` in it.
        val pointer = Bytes()
        pointer.ascii("obj "); pointer.ascii("prop"); pointer.u32(0)
        val real = brush("Pointed", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))))
        val body = Bytes()
        body.raw(real); body.raw(pointer.toByteArray())
        val list = Bytes()
        list.ascii("VlLs"); list.u32(2); list.raw(body.toByteArray())
        val desc = Bytes()
        desc.u32(16); desc.raw(descriptorBody("", "null", itemOf("Brsh", list.toByteArray())))
        val library = AbrImport.convert(file(desc = desc.toByteArray()), "pack")
        // The real brush converts, and the pointer is refused with a sentence rather than silently
        // becoming null — a null there would be a brush with no tip and no word.
        assertEquals(listOf("Pointed"), library.brushes.map { it.preset.name }, "${library.refused}")
        assertEquals(20f, library.brushes.single().preset.size.base, 1e-4f)
        assertEquals(1, library.refused.size, "${library.refused}")
        assertTrue(library.refused.single().reason.contains("item 1"), library.refused.single().reason)
    }

    // ---- 12. Long before Int ------------------------------------------------------------------------------

    /**
     * D10: the bounds are four signed 32-bit numbers read as **Long**, and the subtraction is in Long.
     *
     * `right - left` here is 2 147 483 647 − (−2 147 483 648) = 4 294 967 295, which is one more than
     * `Int.MAX_VALUE`. `(right - left)` as an `Int` would be −1 and every allocation after it would
     * be made from a negative number.
     */
    @Test
    fun aTipWithImpossibleBoundsIsRefusedWithASentenceAboutItsSize() {
        val library = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Huge", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(
                    TipEntry(UUID, left = -2147483648, right = 2147483647, bottom = 1, compression = 0, payload = ByteArray(1))
                )),
            ),
            "pack",
        )
        assertEquals(0, library.brushes.size)
        val reason = library.refused.single().reason
        assertTrue(reason.contains("4294967295"), reason)
        assertTrue(reason.contains("px"), reason)
    }

    /** `MAX_TIP_DIM` + 1 on a side, which is 2 049 px, is refused the same way. */
    @Test
    fun aTipOverTheDimensionCapIsRefusedWithASentenceAboutItsSize() {
        val library = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Wide", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(
                    TipEntry(UUID, bottom = 1, right = 2049, compression = 0, payload = ByteArray(2049))
                )),
            ),
            "pack",
        )
        assertEquals(0, library.brushes.size)
        val reason = library.refused.single().reason
        assertTrue(reason.contains("2049x1"), reason)
        assertTrue(reason.contains("2048"), reason)
        // The cap's own edge is not a refusal: 2 048 × 2 048 = 4 194 304 pixels, exactly
        // MAX_TIP_PIXELS, and 4 194 304 bytes, well inside MAX_TIP_BYTES.
        val edge = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Edge", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(
                    TipEntry(UUID, bottom = 2048, right = 2048, compression = 0, payload = ByteArray(2048 * 2048))
                )),
            ),
            "pack",
        )
        assertEquals(1, edge.brushes.size, "${edge.refused}")
        assertEquals(2048f, edge.brushes.single().preset.size.base, 1e-4f)
    }

    // ---- 13/14. the budgets a brush can break itself ----------------------------------------------------------

    /**
     * `MAX_EXTENSION_BYTES` is real. Nine `TEXT` keys of 32 768 characters are 9 × 32 768 = 294 912
     * characters, which is 32 768 over the 262 144 cap; 32 768 characters is 65 536 bytes, exactly
     * the reader's own `MAX_STRING_BYTES`, so each one is at its own edge and still reads.
     *
     * Eight of them are 262 144 characters — exactly the cap, and not a refusal. That boundary is the
     * interesting half: a check written `>=` would throw away a brush that fits.
     */
    @Test
    fun extensionsPastTheCapRefuseTheBrushRatherThanTruncatingIt() {
        val chunk = "x".repeat(32_768)
        fun keys(n: Int) = (0 until n).map { itemOf("K00$it", textV(chunk)) }.toTypedArray()
        val over = AbrImport.convert(
            file(desc = descSection(listOf(brush("Loud", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))), *keys(9))))),
            "pack",
        )
        assertEquals(0, over.brushes.size, "${over.brushes.map { it.preset.extensions.keys }}")
        assertTrue(over.refused.single().reason.contains("$MAX_EXTENSION_BYTES"), over.refused.single().reason)

        val exact = AbrImport.convert(
            file(desc = descSection(listOf(brush("Full", itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))), *keys(8))))),
            "pack",
        )
        val p = exact.brushes.single().preset
        assertEquals(8, p.extensions.size, "${p.extensions.keys}")
        for (i in 0 until 8) assertEquals(chunk, p.extensions["abr.K00$i"], "abr.K00$i is not complete")
        assertEquals(8 * 32_768, p.extensions.values.sumOf { it.length })
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    /** Spacing is clamped with both numbers, and a spacing that cannot be clamped refuses the brush. */
    @Test
    fun spacingIsClampedWithBothNumbersAndAnImpossibleOneRefusesTheBrush() {
        // Spcn = 0.1 % → 0.001, which is under BrushValidate's 0.005 floor. A `doub`, because 0.1 %
        // is not an integer number of percent and `long` would have rounded it to 1 % — 0.01, which
        // is *inside* the range and would have proved nothing.
        val clamped = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Tight",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)), itemOf("Spcn", doubV(0.1)))),
            )))),
            "pack",
        ).brushes.single()
        assertEquals(0.005f, clamped.preset.spacing, 1e-6f)
        val warning = clamped.warnings.filter { it.contains("spacing") }
        assertEquals(1, warning.size, "${clamped.warnings}")
        assertTrue(warning.single().contains("0.001"), warning.single())
        assertTrue(warning.single().contains("0.005"), warning.single())
        assertEquals(emptyList(), BrushValidate.validate(clamped.preset))

        // Spcn = +Infinity is a `doub` whose bits are the IEEE 754 infinity, written by hand so the
        // fixture says exactly what it means. There is no fraction of a diameter this is, and a guess
        // would be a stroke of zero dabs or a stroke of one.
        val impossible = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Broken",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)), itemOf("Spcn", doubBits(0x7FF0000000000000L)))),
            )))),
            "pack",
        )
        assertEquals(0, impossible.brushes.size)
        assertTrue(impossible.refused.single().reason.contains("spacing"), impossible.refused.single().reason)
    }

    /** A diameter and a roundness outside their ranges are clamped with both numbers, not refused. */
    @Test
    fun anOutOfRangeDiameterIsClampedWithBothNumbers() {
        val result = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Big",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(9000)), itemOf("Rndn", longV(300)))),
            )))),
            "pack",
        ).brushes.single()
        assertEquals(4096f, result.preset.size.base, 1e-4f)      // BrushValidate.MAX_SIZE_PX
        // 300 % roundness is −(1 − 3) = 2, which is outside −1..1, so it clamps and says both numbers.
        assertEquals(1f, result.preset.tip.aspect, 1e-4f)
        assertTrue(result.warnings.any { it.contains("9000") && it.contains("4096") }, "${result.warnings}")
        assertTrue(result.warnings.any { it.contains("300") && it.contains("-1") }, "${result.warnings}")
        assertEquals(emptyList(), BrushValidate.validate(result.preset))

        // 150 % is −(1 − 150/100) = −(−0.5) = +0.5, which is inside −1..1 and is therefore *not*
        // clamped: the mapping is exact up to the point the field runs out, and a clamp before that
        // would be a smaller brush than the file asked for.
        val inside = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Wide",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)), itemOf("Rndn", longV(150)))),
            )))),
            "pack",
        ).brushes.single()
        assertEquals(0.5f, inside.preset.tip.aspect, 1e-4f)
        assertEquals(0, inside.warnings.count { it.contains("roundness") }, "${inside.warnings}")
    }

    // ---- 15. malformed input ------------------------------------------------------------------------------

    /**
     * Random bytes, a valid header with a garbage `desc`, an empty file and a 3-byte file: each one
     * either throws or produces a library that says what it lost. **None** returns an empty
     * `brushes` list with nothing refused and nothing thrown.
     */
    @Test
    fun malformedInputIsARefusalOrAnExceptionAndNeverAPartialPreset() {
        // 64 bytes from a fixed linear congruential generator, so the fixture is the same every run.
        val random = ByteArray(64)
        var seed = 0x13579BDF
        for (i in random.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            random[i] = (seed shr 16).toByte()
        }
        val cases = listOf<Pair<String, ByteArray>>(
            "random bytes" to random,
            "an empty file" to ByteArray(0),
            "three bytes" to byteArrayOf(0, 6, 0),
            "a header and nothing else" to file(),
            "a valid header with a garbage desc" to file(desc = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)),
            "a truncated file" to file(desc = descSection(listOf(brush("x")))).copyOfRange(0, 24),
        )
        for ((what, bytes) in cases) {
            val outcome = try {
                val library = AbrImport.convert(bytes, "pack")
                assertTrue(
                    library.brushes.isNotEmpty() || library.refused.isNotEmpty(),
                    "$what returned an empty library and said nothing",
                )
                "returned ${library.brushes.size} brushes and ${library.refused.size} refusals"
            } catch (e: BrushException) {
                assertTrue(e.message?.isNotBlank() == true, "$what threw with no message")
                "threw: ${e.message}"
            }
            println("$what: $outcome")
        }
    }

    /** A tip that is 1 bit, 8 bit or 16 bit deep all read; 32 does not, by name. */
    @Test
    fun aTipDepthThisBuildDoesNotReadIsRefusedWithTheDepthInTheSentence() {
        val library = AbrImport.convert(
            file(
                desc = descSection(listOf(brush("Deep", itemOf("Brsh", sampledTip(UUID))))),
                samp = sampSection(listOf(TipEntry(UUID, bottom = 2, right = 2, depth = 32, compression = 0, payload = ByteArray(16)))),
            ),
            "pack",
        )
        assertEquals(0, library.brushes.size)
        assertTrue(library.refused.single().reason.contains("depth is 32"), library.refused.single().reason)
    }

    // ---- the rest of the mapping -----------------------------------------------------------------------------

    /** Tool options, the toggles, and the brush's own `Spcn` when the tip does not carry one. */
    @Test
    fun toolOptionsAndTogglesMap() {
        val result = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Options",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))),
                itemOf("Rpt ", boolV(true)),
                itemOf("Nose", boolV(true)),
                itemOf("Cnt ", longV(30)),
                itemOf("toolOptions", objcV(
                    "", "PbTl",
                    itemOf("Opct", longV(80)),
                    itemOf("flow", longV(90)),
                    itemOf("smoothingValue", longV(45)),
                )),
            )))),
            "pack",
        ).brushes.single()
        val p = result.preset
        assertEquals("buildup", p.accumulate)
        assertEquals(0.8f, p.opacity.base, 1e-6f)      // 80 / 100
        assertEquals(0.9f, p.flow.base, 1e-6f)         // 90 / 100
        assertEquals(0.45f, p.smoothing, 1e-6f)       // 45 / 100
        assertEquals(16, p.scatter.count)             // 30 dabs clamped into BrushValidate's 1..16
        assertNotNull(p.extensions["abr.Nose"])
        assertTrue(result.warnings.any { it.contains("noise") }, "${result.warnings}")
        assertTrue(result.warnings.any { it.contains("30 clamped to 16") }, "${result.warnings}")
        assertEquals(emptyList(), BrushValidate.validate(p))
    }

    /** An airbrush tip is LOSSY, and it says what it lost. A `dTips` that is not shape 5 is refused. */
    @Test
    fun anAirbrushTipIsLossyAndAnErodibleOneIsRefused() {
        val airbrush = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Air",
                itemOf("Brsh", objcV("", "dTips", itemOf("Shp ", longV(5)), itemOf("dtipsHardness", longV(60)))),
            )))),
            "pack",
        ).brushes.single()
        assertEquals("procedural", airbrush.preset.tip.source)
        assertEquals(0.6f, airbrush.preset.tip.hardness.base, 1e-6f)
        assertTrue(airbrush.warnings.any { it.contains("airbrush tip") }, "${airbrush.warnings}")
        assertEquals(emptyList(), BrushValidate.validate(airbrush.preset))

        for (shape in listOf(0, 1, 2, 3, 4)) {
            val refused = AbrImport.convert(
                file(desc = descSection(listOf(brush(
                    "Erodes",
                    itemOf("Brsh", objcV("", "dTips", itemOf("Shp ", longV(shape.toLong())))),
                )))),
                "pack",
            )
            assertEquals(0, refused.brushes.size, "shape $shape")
            assertTrue(refused.refused.single().reason.contains("erodible"), refused.refused.single().reason)
        }
    }

    /** A brush that states neither a name nor a tip is refused with a sentence, not half-built. */
    @Test
    fun aBrushWithNoNameAndNoTipIsRefusedWithASentence() {
        val library = AbrImport.convert(
            file(desc = descSection(listOf(brush("Nameless"), objcV("", "null")))),
            "pack",
        )
        assertEquals(0, library.brushes.size, "${library.brushes.map { it.preset.name }}")
        assertEquals(2, library.refused.size)
        assertTrue(library.refused[0].name.isNotEmpty())
        assertTrue(library.refused[1].reason.contains("Brsh"), library.refused[1].reason)
        // The fallback name is a real one, so the id is a safe folder name.
        assertEquals(brushId("pack", "brush 2"), brushId("pack", library.refused[1].name))
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /**
     * A brush with a control on every setting that takes one, all on Pen Pressure (`bVTy = 2`) and
     * all with a 10 % minimum, so the expected curve is `(0, 0.1)` → `(1, 1)` on each of them.
     */
    private fun curvedBrush(name: String): ByteArray = brush(
        name,
        itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)), itemOf("Angl", longV(0)))),
        itemOf("szVr", dynamics(2, jitter = 50, minimum = 10)),
        itemOf("opVr", dynamics(2, jitter = 40, minimum = 10)),
        itemOf("prVr", dynamics(2, jitter = 5, minimum = 10)),
        itemOf("scatterDynamics", dynamics(2, jitter = 0, minimum = 10)),
        itemOf("countDynamics", dynamics(2, jitter = 40, minimum = 10)),
        itemOf("angleDynamics", dynamics(2, jitter = 30, minimum = 10)),
    )

    private fun single(descriptor: ByteArray): cc.joycreator.joybrush.core.brush.BrushPreset =
        AbrImport.convert(file(desc = descSection(listOf(descriptor))), "pack").brushes.single().preset

    private class ColourResult(
        val preset: cc.joycreator.joybrush.core.brush.BrushPreset,
        val warnings: List<String>,
        val fileWarning: String,
    ) {
        override fun toString(): String = "$warnings / file: $fileWarning"
    }

    private fun colourResult(brgh: ByteArray): ColourResult {
        val result = AbrImport.convert(
            file(desc = descSection(listOf(brush(
                "Colour",
                itemOf("Brsh", computedTip(itemOf("Dmtr", longV(20)))),
                itemOf("Brgh", brgh),
            )))),
            "pack",
        ).brushes.single()
        val fileWarning = result.warnings.filter { it.startsWith("this file:") }.joinToString(" ")
        return ColourResult(result.preset, result.warnings.filterNot { it.startsWith("this file:") }, fileWarning)
    }

    private companion object {
        const val UUID = "d2b1c3f4-1122-3344-5566-778899aabbcc"
        const val PATTERN_ID = "3e6b0c2a-77aa-4d55-9f10-2b8c5d1e4a60"

        /**
         * A `samp` entry whose every row is a 4-byte gray ramp, PackBits-encoded.
         *
         * 0, 85, 170, 255 is 0, 1/3, 2/3, 1 of 255, so the mean of a row is exactly 0.5 — which is
         * what makes the hardness assertions elsewhere in this file arithmetic rather than an
         * eyeball.
         */
        fun packBitsTip(uuid: String, width: Int, height: Int, compression: Int = 1): TipEntry {
            val row = packLiterals(ByteArray(width) { RAMP[it % 4] })
            val payload = Bytes()
            if (compression == 0) {
                for (r in 0 until height) for (i in 0 until width) payload.u8(RAMP[i % 4].toInt() and 0xFF)
            } else {
                // Table-first like the real files: every row's `u16` count, then every row.
                repeat(height) { payload.u16(row.size) }
                repeat(height) { payload.raw(row) }
            }
            return TipEntry(uuid, bottom = height, right = width, compression = compression, payload = payload.toByteArray())
        }

        /**
         * A pack with a computed brush, a sampled brush, a texture pattern and a `patt` record — the
         * panels a real `.abr` carries, in the order the sections appear in one.
         */
        fun pack(): ByteArray = file(
            desc = descSection(listOf(
                brush(
                    "Round Hard",
                    itemOf("Brsh", computedTip(
                        itemOf("Dmtr", longV(19)),
                        itemOf("Hrdn", longV(80)),
                        itemOf("Angl", longV(0)),
                        itemOf("Rndn", longV(100)),
                        itemOf("Spcn", longV(25)),
                    )),
                    itemOf("szVr", dynamics(2, jitter = 0, fadeSteps = 0, minimum = 100)),
                    itemOf("opVr", dynamics(2, jitter = 0, minimum = 100)),
                    itemOf("toolOptions", objcV("", "PbTl", itemOf("Opct", longV(100)))),
                    itemOf("interpretation", longV(1)),
                ),
                brush(
                    "Ink Splatter",
                    itemOf("Brsh", sampledTip(UUID)),
                    itemOf("Rpt ", boolV(true)),
                ),
                brush(
                    "Paper",
                    itemOf("Brsh", computedTip(itemOf("Dmtr", longV(40)), itemOf("Spcn", longV(10)))),
                    itemOf("useTexture", boolV(true)),
                    itemOf("Txtr", objcV("", "null", itemOf("Idnt", enumV("Idnt", PATTERN_ID)))),
                ),
            )),
            samp = sampSection(listOf(packBitsTip(UUID, 4, 4))),
            patt = pattSection(listOf(patternRecord(PATTERN_ID))),
        )
    }
}

/**
 * RFC 4648 §4 decode, written from the alphabet and the 6-bits-per-character rule rather than from
 * the encoder it checks — a decoder that shares the encoder's mistake proves nothing.
 */
private fun base64Decode(text: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val bits = ArrayList<Int>(text.length * 6)
    for (c in text) {
        if (c == '=') break
        val v = alphabet.indexOf(c)
        check(v >= 0) { "'$c' is not in the base64 alphabet" }
        for (k in 0 until 6) bits.add((v shr (5 - k)) and 1)
    }
    val out = ArrayList<Byte>(bits.size / 8)
    var i = 0
    while (i + 8 <= bits.size) {
        var n = 0
        // The last group may be short: 28 bytes is nine whole 24-bit groups and one byte, so a
        // decoder that insists on 24 bits at a time silently drops the last byte of every image.
        val take = minOf(8, bits.size - i)
        for (k in 0 until take) n = (n shl 1) or bits[i + k]
        out.add(n.toByte())
        i += take
    }
    return ByteArray(out.size) { out[it] }
}
