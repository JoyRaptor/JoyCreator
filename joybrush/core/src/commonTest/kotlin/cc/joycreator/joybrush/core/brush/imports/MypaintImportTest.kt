package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BRUSH_VERSION
import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.Param
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three `.myb` fixtures below are pasted **verbatim** from github.com/mypaint/mypaint-brushes at
 * commit 08da4a4 (`git/trees/master?recursive=1`, branch `master`), re-indented and reflowed onto
 * fewer lines and with no values changed. The `.myb` files there are CC0-1.0 per that repo's
 * `Licenses.dep5`; the repo scaffolding is GPL-2+. Only data is used here, and no libmypaint code.
 *
 *   brushes/classic/pen.myb               6 107 bytes
 *   brushes/classic/charcoal.myb          5 163 bytes
 *   brushes/deevad/basic_digital_brush.myb 6 994 bytes
 *
 * commonTest cannot open a file (it has to stay platform-neutral), so the strings here are the only
 * copy. If one is edited, the file at github must be edited to match.
 */
class MypaintImportTest {

    // ---- 1. the three real brushes, exactly as mypaint-brushes ships them -------------------------

    /** brushes/classic/pen.myb — CC0-1.0, mypaint-brushes @ 08da4a4. */
    private val pen: String = """
        {
          "comment": "MyPaint brush file",
          "description": "",
          "group": "",
          "notes": "",
          "parent_brush_name": "classic/pen",
          "settings": {
            "anti_aliasing": { "base_value": 1.0, "inputs": {} },
            "change_color_h": { "base_value": 0.0, "inputs": {} },
            "change_color_hsl_s": { "base_value": 0.0, "inputs": {} },
            "change_color_hsv_s": { "base_value": 0.0, "inputs": {} },
            "change_color_l": { "base_value": 0.0, "inputs": {} },
            "change_color_v": { "base_value": 0.0, "inputs": {} },
            "color_h": { "base_value": 0.0, "inputs": {} },
            "color_s": { "base_value": 0.0, "inputs": {} },
            "color_v": { "base_value": 0.0, "inputs": {} },
            "colorize": { "base_value": 0.0, "inputs": {} },
            "custom_input": { "base_value": 0.0, "inputs": {} },
            "custom_input_slowness": { "base_value": 0.0, "inputs": {} },
            "dabs_per_actual_radius": { "base_value": 2.2, "inputs": {} },
            "dabs_per_basic_radius": { "base_value": 0.0, "inputs": {} },
            "dabs_per_second": { "base_value": 0.0, "inputs": {} },
            "direction_filter": { "base_value": 2.0, "inputs": {} },
            "elliptical_dab_angle": { "base_value": 90.0, "inputs": {} },
            "elliptical_dab_ratio": { "base_value": 1.0, "inputs": {} },
            "eraser": { "base_value": 0.0, "inputs": {} },
            "hardness": { "base_value": 0.9, "inputs": {
              "pressure": [[0.0, 0.0], [1.0, 0.05]],
              "speed1": [[0.0, -0.0], [1.0, -0.09]] } },
            "lock_alpha": { "base_value": 0.0, "inputs": {} },
            "offset_by_random": { "base_value": 0.0, "inputs": {} },
            "offset_by_speed": { "base_value": 0.0, "inputs": {} },
            "offset_by_speed_slowness": { "base_value": 1.0, "inputs": {} },
            "opaque": { "base_value": 1.0, "inputs": {} },
            "opaque_linearize": { "base_value": 0.9, "inputs": {} },
            "opaque_multiply": { "base_value": 0.0, "inputs": {
              "pressure": [[0.0, 0.0], [0.015, 0.0], [0.015, 1.0], [1.0, 1.0]] } },
            "pressure_gain_log": { "base_value": 0.0, "inputs": {} },
            "radius_by_random": { "base_value": 0.0, "inputs": {} },
            "radius_logarithmic": { "base_value": 0.96, "inputs": {
              "pressure": [[0.0, 0.0], [1.0, 0.5]],
              "speed1": [[0.0, -0.0], [1.0, -0.15]] } },
            "restore_color": { "base_value": 0.0, "inputs": {} },
            "slow_tracking": { "base_value": 0.65, "inputs": {} },
            "slow_tracking_per_dab": { "base_value": 0.8, "inputs": {} },
            "smudge": { "base_value": 0.0, "inputs": {} },
            "smudge_length": { "base_value": 0.5, "inputs": {} },
            "smudge_radius_log": { "base_value": 0.0, "inputs": {} },
            "snap_to_pixel": { "base_value": 0.0, "inputs": {} },
            "speed1_gamma": { "base_value": 2.87, "inputs": {} },
            "speed1_slowness": { "base_value": 0.04, "inputs": {} },
            "speed2_gamma": { "base_value": 4.0, "inputs": {} },
            "speed2_slowness": { "base_value": 0.8, "inputs": {} },
            "stroke_duration_logarithmic": { "base_value": 4.0, "inputs": {} },
            "stroke_holdtime": { "base_value": 0.0, "inputs": {} },
            "stroke_threshold": { "base_value": 0.0, "inputs": {} },
            "tracking_noise": { "base_value": 0.0, "inputs": {} }
          },
          "version": 3
        }
    """.trimIndent()

    /** brushes/classic/charcoal.myb — CC0-1.0, mypaint-brushes @ 08da4a4. */
    private val charcoal: String = """
        {
          "comment": "MyPaint brush file",
          "group": "",
          "parent_brush_name": "",
          "settings": {
            "anti_aliasing": { "base_value": 0.0, "inputs": {} },
            "change_color_h": { "base_value": 0.0, "inputs": {} },
            "change_color_hsl_s": { "base_value": 0.0, "inputs": {} },
            "change_color_hsv_s": { "base_value": 0.0, "inputs": {} },
            "change_color_l": { "base_value": 0.0, "inputs": {} },
            "change_color_v": { "base_value": 0.0, "inputs": {} },
            "color_h": { "base_value": 0.6354166666666666, "inputs": {} },
            "color_s": { "base_value": 0.8807339449541285, "inputs": {} },
            "color_v": { "base_value": 0.42745098039215684, "inputs": {} },
            "colorize": { "base_value": 0.0, "inputs": {} },
            "custom_input": { "base_value": 0.0, "inputs": {} },
            "custom_input_slowness": { "base_value": 0.0, "inputs": {} },
            "dabs_per_actual_radius": { "base_value": 5.0, "inputs": {} },
            "dabs_per_basic_radius": { "base_value": 0.0, "inputs": {} },
            "dabs_per_second": { "base_value": 0.0, "inputs": {} },
            "direction_filter": { "base_value": 2.0, "inputs": {} },
            "elliptical_dab_angle": { "base_value": 90.0, "inputs": {} },
            "elliptical_dab_ratio": { "base_value": 1.0, "inputs": {} },
            "eraser": { "base_value": 0.0, "inputs": {} },
            "hardness": { "base_value": 0.2, "inputs": {} },
            "lock_alpha": { "base_value": 0.0, "inputs": {} },
            "offset_by_random": { "base_value": 1.6, "inputs": {
              "pressure": [[0, 0], [1.0, -1.4]] } },
            "offset_by_speed": { "base_value": 0.0, "inputs": {} },
            "offset_by_speed_slowness": { "base_value": 1.0, "inputs": {} },
            "opaque": { "base_value": 0.4, "inputs": {
              "pressure": [[0, 0], [1.0, 0.4]] } },
            "opaque_linearize": { "base_value": 0.0, "inputs": {} },
            "opaque_multiply": { "base_value": 0.0, "inputs": {
              "pressure": [[0, 0], [1.0, 1.0]] } },
            "radius_by_random": { "base_value": 0.0, "inputs": {} },
            "radius_logarithmic": { "base_value": 0.7, "inputs": {} },
            "restore_color": { "base_value": 0.0, "inputs": {} },
            "slow_tracking": { "base_value": 2.0, "inputs": {} },
            "slow_tracking_per_dab": { "base_value": 0.0, "inputs": {} },
            "smudge": { "base_value": 0.0, "inputs": {} },
            "smudge_length": { "base_value": 0.5, "inputs": {} },
            "smudge_radius_log": { "base_value": 0.0, "inputs": {} },
            "speed1_gamma": { "base_value": 4.0, "inputs": {} },
            "speed1_slowness": { "base_value": 0.04, "inputs": {} },
            "speed2_gamma": { "base_value": 4.0, "inputs": {} },
            "speed2_slowness": { "base_value": 0.8, "inputs": {} },
            "stroke_duration_logarithmic": { "base_value": 4.0, "inputs": {} },
            "stroke_holdtime": { "base_value": 0.0, "inputs": {} },
            "stroke_threshold": { "base_value": 0.0, "inputs": {} },
            "tracking_noise": { "base_value": 0.0, "inputs": {} }
          },
          "version": 3
        }
    """.trimIndent()

    /** brushes/deevad/basic_digital_brush.myb — CC0-1.0, mypaint-brushes @ 08da4a4, David Revoy. */
    private val basicDigital: String = """
        {
          "comment": "MyPaint brush file",
          "group": "",
          "description": "A basic rounded digital brush",
          "notes": "A brush preset part of the Brushkit v0.6 \n created in october 2012 by David Revoy (aka Deevad ) \n source: http://www.davidrevoy.com/article142/ressource-mypaint-brushes \n license: CC-Zero/Public-Domain",
          "parent_brush_name": "",
          "settings": {
            "anti_aliasing": { "base_value": 2.3, "inputs": {} },
            "change_color_h": { "base_value": 0.0, "inputs": {} },
            "change_color_hsl_s": { "base_value": 0.0, "inputs": {} },
            "change_color_hsv_s": { "base_value": 0.0, "inputs": {} },
            "change_color_l": { "base_value": 0.0, "inputs": {} },
            "change_color_v": { "base_value": 0.0, "inputs": {} },
            "color_h": { "base_value": 0.02525252525252525, "inputs": {} },
            "color_s": { "base_value": 1.0, "inputs": {} },
            "color_v": { "base_value": 0.0, "inputs": {} },
            "colorize": { "base_value": 0.0, "inputs": {} },
            "custom_input": { "base_value": 0.0, "inputs": {} },
            "custom_input_slowness": { "base_value": 0.0, "inputs": {} },
            "dabs_per_actual_radius": { "base_value": 2.0, "inputs": {} },
            "dabs_per_basic_radius": { "base_value": 4.63, "inputs": {} },
            "dabs_per_second": { "base_value": 0.0, "inputs": {} },
            "direction_filter": { "base_value": 2.0, "inputs": {} },
            "elliptical_dab_angle": { "base_value": 90.0, "inputs": {} },
            "elliptical_dab_ratio": { "base_value": 1.0, "inputs": {} },
            "eraser": { "base_value": 0.0, "inputs": {} },
            "hardness": { "base_value": 0.8, "inputs": {
              "pressure": [[0.0, 0.0], [1.0, 1.0]] } },
            "lock_alpha": { "base_value": 0.0, "inputs": {} },
            "offset_by_random": { "base_value": 0.0, "inputs": {} },
            "offset_by_speed": { "base_value": 0.0, "inputs": {} },
            "offset_by_speed_slowness": { "base_value": 1.0, "inputs": {} },
            "opaque": { "base_value": 2.54152805533e-05, "inputs": {
              "pressure": [[0.0, 0.0], [0.2875, 0.0625], [0.645, 0.19791666666666663],
                           [0.875, 0.65625], [1.0, 1.0]] } },
            "opaque_linearize": { "base_value": 0.9, "inputs": {} },
            "opaque_multiply": { "base_value": 0.0, "inputs": {
              "pressure": [[0.0, 0.0], [0.080645, 0.072917], [0.154839, 0.291667],
                           [0.21129, 0.458333], [0.285484, 0.760417], [0.348387, 0.947917],
                           [0.416129, 1.0], [1.0, 1.0]] } },
            "radius_by_random": { "base_value": 0.0, "inputs": {} },
            "radius_logarithmic": { "base_value": 3.02, "inputs": {} },
            "restore_color": { "base_value": 0.0, "inputs": {} },
            "slow_tracking": { "base_value": 0.0, "inputs": {} },
            "slow_tracking_per_dab": { "base_value": 0.0, "inputs": {} },
            "smudge": { "base_value": 0.0, "inputs": {
              "pressure": [[0.0, 1.0], [1.0, -1.0]] } },
            "smudge_length": { "base_value": 0.5, "inputs": {
              "stroke": [[0.0, 1.0], [1.0, -1.0]] } },
            "smudge_radius_log": { "base_value": 0.0, "inputs": {} },
            "speed1_gamma": { "base_value": 4.0, "inputs": {} },
            "speed1_slowness": { "base_value": 0.04, "inputs": {} },
            "speed2_gamma": { "base_value": 4.0, "inputs": {} },
            "speed2_slowness": { "base_value": 0.8, "inputs": {} },
            "stroke_duration_logarithmic": { "base_value": 4.0, "inputs": {} },
            "stroke_holdtime": { "base_value": 10.0, "inputs": {} },
            "stroke_threshold": { "base_value": 0.0, "inputs": {} },
            "tracking_noise": { "base_value": 0.0, "inputs": {} }
          },
          "version": 3
        }
    """.trimIndent()

    // ---- helpers --------------------------------------------------------------------------------

    private fun convert(json: String): ImportResult =
        MypaintImport.convert(json, "myb.test", "MyPaint test brush")

    /** A well-formed `.myb` around one settings body, for the hostile-input tests. */
    private fun file(settings: String, comment: String = "", version: Int = 3): String =
        """{ "comment": "$comment", "version": $version, "settings": { $settings } }"""

    /**
     * Some warning in [result] says all of [needles].
     *
     * Nails the *facts* — which setting, which number, which way — rather than one whole sentence.
     * A test that pins the prose breaks every time the wording is improved, which is the wrong time to
     * learn that someone is improving the wording.
     */
    private fun warnsAbout(result: ImportResult, vararg needles: String) {
        val hit = result.warnings.filter { w -> needles.all { n -> w.contains(n) } }
        assertTrue(hit.isNotEmpty(), "no warning said ${needles.toList()}; got ${result.warnings}")
    }

    // ---- 2. each real brush converts, and what it becomes ------------------------------------------

    @Test
    fun everyRealBrushConvertsAndValidatesClean() {
        for ((label, json) in listOf("pen" to pen, "charcoal" to charcoal, "digital" to basicDigital)) {
            val result = convert(json)
            val p = result.preset
            assertEquals(emptyList(), BrushValidate.validate(p), "$label must be a legal brush: $p")
            assertEquals("joybrush.brush", p.format, label)
            // A brush THIS build creates says the current version, not 1: `BrushPreset.version`
            // defaults to BRUSH_VERSION, and EnumFreezeTest asserts the two are equal precisely so
            // an imported brush cannot be stamped with a version nobody validates. It was 1 before
            // JB-1.08a raised the version; the pin moves with the constant rather than being
            // deleted, so the next bump is caught here.
            assertEquals(BRUSH_VERSION, p.version, label)
            assertEquals("myb.test", p.id, label)
            assertEquals("MyPaint test brush", p.name, label)
            assertEquals("myb", p.sourceFormat, label)
            assertEquals("stamp", p.engine, label)
            assertEquals("wash", p.accumulate, label)
            assertEquals("normal", p.blend, label)
            assertEquals(0f, p.angleJitter, label)
            assertEquals(0f, p.color.hue, label)
            // The one thing every MyPaint brush has and none of these three use.
            assertEquals(0f, p.sizeJitter, label)
            // Nothing is stored under a key that is not a MyPaint setting, so a future reader can
            // tell at a glance that every key here came out of a `.myb`.
            for (key in p.extensions.keys) {
                assertTrue(key.startsWith("myb."), "$label put \"$key\" in extensions")
            }
            // Every extension is the raw JSON of that setting, not a note about it.
            for ((key, text) in p.extensions) {
                assertTrue(text.startsWith("{"), "$label stored \"$text\" for $key, not an object")
            }
            // Nothing converted into something the validator would refuse.
            assertTrue(
                result.warnings.none { it.startsWith("imported brush would be refused") },
                "$label produced a brush validation rejects: ${result.warnings}",
            )
        }
    }

    @Test
    fun thePenBecomesAReadablePen() {
        // radius_logarithmic base 0.96 → 2·e^0.96 px of diameter.
        //   e^0.96 = e ÷ e^0.04;  e^0.04 = 1.0408108;  2.7182818 ÷ 1.0408108 = 2.6116964;
        //   × 2 = 5.2233928.
        val p = convert(pen).preset
        assertEquals(5.2233928f, p.size.base, 1e-4f)
        assertEquals(0.2272727f, p.spacing, 1e-5f)   // 1 ÷ (2 × 2.2)
        assertEquals(0.9f, p.tip.hardness.base)      // hardness, as is
        assertEquals(0f, p.tip.aspect)               // elliptical_dab_ratio 1 → 1 − 1/1
        assertEquals(90f, p.tip.angle.base)           // elliptical_dab_angle, as is
        assertEquals(1f, p.opacity.base)              // opaque 1.0, already inside 0..1
        assertEquals(0f, p.scatter.amount.base)       // offset_by_random 0 ÷ 2
        assertEquals(0.065f, p.smoothing, 1e-6f)      // slow_tracking 0.65 ÷ 10
        assertEquals("unknown", p.license)            // nothing in the header claims CC0
        assertEquals("MyPaint brush file", p.author)

        // The pressure curve [[0,0],[1,0.5]] is an *additive offset on a log radius*, which is a
        // multiplier: it becomes [[0, e^0], [1, e^0.5]] and the Param combines by multiply.
        val sizePressure = p.size.inputs.single()
        assertEquals(BrushInput.pressure, sizePressure.input)
        assertEquals("multiply", p.size.combine)
        assertEquals(listOf(0f, 1f), sizePressure.curve[0])
        assertEquals(1f, sizePressure.curve[0][1], 1e-6f)          // e^0
        assertEquals(1.6487213f, sizePressure.curve[1][1], 1e-6f) // e^0.5
        assertEquals(1f, sizePressure.curve[1][0], 1e-6f)

        // opaque_multiply's pressure curve is already linear, so it is copied across unchanged.
        val opacityPressure = p.opacity.inputs.single()
        assertEquals(BrushInput.pressure, opacityPressure.input)
        assertEquals(listOf(0f, 0f), opacityPressure.curve[0])
        assertEquals(listOf(0.015f, 0f), opacityPressure.curve[1])
        assertEquals(listOf(0.015f, 1f), opacityPressure.curve[2])
        assertEquals(listOf(1f, 1f), opacityPressure.curve[3])
    }

    @Test
    fun charcoalAndTheDigitalBrushConvertTheirOwnNumbers() {
        val coal = convert(charcoal).preset
        // 2·e^0.7 = 2 × 2.0137527 = 4.0275054.
        assertEquals(4.0275054f, coal.size.base, 1e-4f)
        assertEquals(0.1f, coal.spacing, 1e-6f)   // 1 ÷ (2 × 5)
        assertEquals(0.2f, coal.tip.hardness.base)
        assertEquals(0.8f, coal.scatter.amount.base)  // offset_by_random 1.6 radii = 0.8 diameters
        assertEquals(0.2f, coal.smoothing, 1e-6f)      // slow_tracking 2.0 ÷ 10
        assertEquals(0.4f, coal.opacity.base)          // opaque 0.4, inside 0..1, unclamped
        assertEquals(0f, coal.sizeJitter)
        // `opaque`'s own pressure curve is *not* mapped — only `opaque_multiply` is — so it is kept.
        assertNotNull(coal.extensions["myb.opaque"])
        warnsAbout(convert(charcoal), "opaque", "pressure", "not mapped")
        val digital = convert(basicDigital).preset
        // 2·e^3.02 = 2 × 20.0855369 × 1.0202013 = 2 × 20.4912910 = 40.9825830.
        assertEquals(40.9825830f, digital.size.base, 1e-4f)
        // dabs_per_actual_radius 2.0 + dabs_per_basic_radius 4.63 = 6.63; 1 ÷ (2 × 6.63) = 0.0754148.
        assertEquals(0.0754148f, digital.spacing, 1e-6f)
        assertEquals(0.8f, digital.tip.hardness.base)
        assertEquals(0f, digital.smoothing)                  // slow_tracking 0
        assertEquals(2.541528e-5f, digital.opacity.base, 1e-8f)
        // This is the one file that says its licence: "license: CC-Zero/Public-Domain", in `notes`.
        assertEquals("CC0", digital.license)
        assertEquals("MyPaint brush file", digital.author)
        assertEquals(8, digital.opacity.inputs.single().curve.size)
    }

    // ---- 3. spacing ------------------------------------------------------------------------------

    @Test
    fun spacingIsOneOverTwiceTheDabsPerRadius() {
        fun spacingOf(actual: String, basic: String): Float = convert(file(
            """"dabs_per_actual_radius": { "base_value": $actual, "inputs": {} },
               "dabs_per_basic_radius": { "base_value": $basic, "inputs": {} }""",
        )).preset.spacing

        assertEquals(0.1f, spacingOf("5.0", "0.0"), 1e-6f)     // 1 ÷ 10
        assertEquals(0.05f, spacingOf("10.0", "0.0"), 1e-6f)    // 1 ÷ 20
        assertEquals(0.04f, spacingOf("10.0", "2.5"), 1e-6f)    // 1 ÷ 25
        assertEquals(0.2f, spacingOf("0.0", "2.5"), 1e-6f)      // the basic term counts too

        // Both zero means no dab would ever be placed, so there is a default and it is said.
        val neither = convert(file(
            """"dabs_per_actual_radius": { "base_value": 0, "inputs": {} },
               "dabs_per_basic_radius": { "base_value": 0, "inputs": {} }""",
        ))
        assertEquals(0.1f, neither.preset.spacing, 1e-6f)
        warnsAbout(neither, "both 0", "spacing set to 0.1")

        // Absent settings mean absent, which is 0, which is the case above.
        assertEquals(0.1f, convert(file(""""smudge": { "base_value": 0, "inputs": {} }""")).preset.spacing, 1e-6f)

        // A density that would need more than one dab per thousandth of a diameter, or fewer than
        // one per five diameters, is clamped into BrushValidate's range rather than refused. The
        // warning says the value it was and the value it is now — the sentence around them is not
        // pinned, because the sentence is the part that will change.
        val dense = convert(file(""""dabs_per_actual_radius": { "base_value": 1e9, "inputs": {} }"""))
        assertEquals(0.005f, dense.preset.spacing)
        warnsAbout(dense, "spacing ", "clamped to 0.005")
        assertEquals(emptyList(), BrushValidate.validate(dense.preset))

        val sparse = convert(file(""""dabs_per_actual_radius": { "base_value": 0.01, "inputs": {} }"""))
        assertEquals(5f, sparse.preset.spacing)
        warnsAbout(sparse, "spacing ", "clamped to 5.0")
        assertEquals(emptyList(), BrushValidate.validate(sparse.preset))
    }

    // ---- 4. what does not map goes to extensions, loudly -------------------------------------------

    @Test
    fun unmappedSettingsLandInExtensionsWithAWarningEach() {
        val result = convert(pen)
        val ext = result.preset.extensions

        // A whole setting with no Joy Brush home, kept verbatim under "myb.<setting>".
        assertNotNull(ext["myb.anti_aliasing"])
        assertTrue(ext.getValue("myb.anti_aliasing").contains("\"base_value\":1.0"))
        assertNotNull(ext["myb.opaque_linearize"])
        assertNotNull(ext["myb.change_color_h"])
        assertNotNull(ext["myb.snap_to_pixel"])
        warnsAbout(result, "anti_aliasing", "no Joy Brush setting")
        warnsAbout(result, "opaque_linearize", "no Joy Brush setting")

        // A setting that *is* mapped but carries an input that is not: the whole setting is kept, so
        // the dropped curve is still in the file. The pen's radius falls off with speed (speed1) and
        // so does its hardness; neither is mapped, and both are said once.
        assertNotNull(ext["myb.radius_logarithmic"])
        assertNotNull(ext["myb.hardness"])
        warnsAbout(result, "radius_logarithmic", "speed1", "not mapped")
        warnsAbout(result, "hardness", "pressure", "speed1", "not mapped")

        // `radius_by_random` is MyPaint's *own* size-jitter setting and the spec reads jitter off
        // radius_logarithmic's "random" input instead, so it has no home. Kept, and said.
        assertNotNull(ext["myb.radius_by_random"])
        warnsAbout(result, "radius_by_random", "no Joy Brush setting")

        // One warning per setting, not per curve and not per point.
        val speed1OnRadius = result.warnings.count { it.contains("radius_logarithmic: input") }
        assertEquals(1, speed1OnRadius, "one setting, one warning: ${result.warnings}")
    }

    @Test
    fun aSettingNobodyHasHeardOfIsKeptNotGuessedAt() {
        val result = convert(file(
            """"wobble": { "base_value": 3.5, "inputs": { "pressure": [[0, 0], [1, 1]] } }""",
        ))
        assertEquals(emptyList(), BrushValidate.validate(result.preset))
        val kept = result.preset.extensions.getValue("myb.wobble")
        assertTrue(kept.contains("3.5"), "kept: $kept")
        assertTrue(kept.contains("pressure"), "the whole setting is kept, not just the base: $kept")
        warnsAbout(result, "wobble", "no Joy Brush setting")
        // …and it changed nothing about the brush.
        assertEquals(1f, result.preset.flow.base)
        assertEquals(0f, result.preset.scatter.amount.base)
    }

    @Test
    fun smudgeIsKeptAndSaysTheBrushWillStamp() {
        val off = convert(file(""""smudge": { "base_value": 0.0, "inputs": {} }"""))
        assertNotNull(off.preset.extensions["myb.smudge"])
        warnsAbout(off, "smudge", "no Joy Brush equivalent")
        assertTrue(
            off.warnings.none { it.contains("stamps instead") },
            "smudge 0 is off, nothing is lost: ${off.warnings}",
        )

        val on = convert(file(""""smudge": { "base_value": 0.9, "inputs": {} }"""))
        assertEquals("stamp", on.preset.engine)
        assertNotNull(on.preset.extensions["myb.smudge"])
        warnsAbout(on, "smudge", "not built", "stamps instead")

        // smudge_length travels with it, with the wording that says it is a smudge setting.
        val both = convert(file(
            """"smudge": { "base_value": 0.9, "inputs": {} },
               "smudge_length": { "base_value": 0.5, "inputs": {} }""",
        ))
        assertNotNull(both.preset.extensions["myb.smudge_length"])
        warnsAbout(both, "smudge_length", "no Joy Brush equivalent")
    }

    // ---- 5. the header ---------------------------------------------------------------------------

    @Test
    fun licenseIsOnlyClaimedWhenTheFileClaimsIt() {
        assertEquals("unknown", convert(file("", comment = "MyPaint brush file")).preset.license)
        assertEquals("unknown", convert(file("", comment = "by someone")).preset.license)
        assertEquals("CC0", convert(file("", comment = "CC0-1.0 by someone")).preset.license)
        assertEquals("CC0", convert(file("", comment = "released into the cc-zero")).preset.license)
        // Real files put it in `notes`, not `comment` — this is deevad/basic_digital_brush.myb.
        val noted = """{ "comment": "MyPaint brush file", "version": 3,
            "notes": "license: CC-Zero/Public-Domain", "settings": {} }"""
        assertEquals("CC0", convert(noted).preset.license)
        assertEquals("MyPaint brush file", convert(noted).preset.author)
        // No free text at all is not a crash, and is not a guess.
        assertEquals("unknown", convert("""{ "version": 3, "settings": {} }""").preset.license)
        assertEquals("", convert("""{ "version": 3, "settings": {} }""").preset.author)
    }

    // ---- 6. the inputs that do map ---------------------------------------------------------------

    @Test
    fun tiltIsNormalisedToZeroOneAndThenTreatedAsPressure() {
        // tilt_declination runs 0..90°; Joy's tilt input runs 0..1. So x is divided by 90 first and
        // the ordinate goes through the same e^y the pressure curve gets.
        val result = convert(file(
            """"radius_logarithmic": { "base_value": 0.0, "inputs": {
                 "tilt_declination": [[0.0, 0.0], [45.0, 0.5], [90.0, 1.0]] } }""",
        ))
        val tilt = result.preset.size.inputs.single()
        assertEquals(BrushInput.tilt, tilt.input)
        assertEquals(listOf(0f, 1f), tilt.curve[0])                // 0 °, e^0 = 1
        assertEquals(0.5f, tilt.curve[1][0], 1e-6f)           // 45 ÷ 90
        assertEquals(1.6487213f, tilt.curve[1][1], 1e-6f)     // e^0.5, the pressure rule again
        assertEquals(1f, tilt.curve[2][0], 1e-6f)             // 90 ÷ 90
        assertEquals(2.7182817f, tilt.curve[2][1], 1e-6f)     // e^1

        // On a linear setting the tilt ordinate is copied, not exponentiated.
        val linear = convert(file(
            """"opaque_multiply": { "base_value": 0.0, "inputs": {
                 "tilt_declination": [[0.0, 0.0], [90.0, 0.5]] } }""",
        ))
        val lin = linear.preset.opacity.inputs.single()
        assertEquals(BrushInput.tilt, lin.input)
        assertEquals(1f, lin.curve[1][0], 1e-6f)
        assertEquals(0.5f, lin.curve[1][1], 1e-6f)
    }

    @Test
    fun sizeJitterIsAnApproximationAndSaysSo() {
        // min(1, e^maxY − 1). Here maxY is 0.4, so e^0.4 = 1.4918247 and less 1 is 0.4918247.
        val result = convert(file(
            """"radius_logarithmic": { "base_value": 0.0, "inputs": {
                 "random": [[0.0, 0.0], [1.0, 0.4]] } }""",
        ))
        assertEquals(0.4918247f, result.preset.sizeJitter, 1e-6f)
        warnsAbout(result, "sizeJitter", "approximation")
        // The curve is kept, because Joy Brush has one sizeJitter and MyPaint has a curve.
        assertNotNull(result.preset.extensions["myb.radius_logarithmic"])
        assertEquals(emptyList(), BrushValidate.validate(result.preset))

        // A curve that only ever asks for a smaller radius is no jitter at all, and is clamped there.
        val shrinking = convert(file(
            """"radius_logarithmic": { "base_value": 0.0, "inputs": {
                 "random": [[0.0, 0.0], [1.0, -2.0]] } }""",
        ))
        assertEquals(0f, shrinking.preset.sizeJitter)

        // A curve that asks for more than 100% is capped, because BrushValidate says 0..1.
        val huge = convert(file(
            """"radius_logarithmic": { "base_value": 0.0, "inputs": {
                 "random": [[0.0, 0.0], [1.0, 500.0]] } }""",
        ))
        assertEquals(1f, huge.preset.sizeJitter)
        assertEquals(emptyList(), BrushValidate.validate(huge.preset))
    }

    // ---- 7. unreadable files are refused, not guessed at -------------------------------------------

    @Test
    fun unreadableFilesAreRefusedWithABrushException() {
        // Truncated, empty, and not JSON at all.
        for (bad in listOf(
            "",
            "{",
            """{ "version": 3, "settings": { "hardness": { "base_value": 0.9, "inputs": {} } """,
            pen.substring(0, pen.indexOf("\"dabs_per_second\"")),
            "not json",
            "[]",
            "3",
            """{ "version": 3, "settings": 7 }""",
            """{ "version": 3 }""",
        )) {
            assertFailsWith<BrushException>("should refuse: $bad") { convert(bad) }
        }
        // A version we do not read: refused by name, because the file means something else to us.
        for (version in listOf(0, 1, 2, 4, 99, -3)) {
            val thrown = assertFailsWith<BrushException>("version $version") {
                convert(file("", version = version))
            }
            assertTrue(thrown.message.orEmpty().contains("version"), "message was: ${thrown.message}")
        }
        // A version that is not an integer, and a brush with no id.
        assertFailsWith<BrushException> { convert("""{ "version": 3.5, "settings": {} }""") }
        assertFailsWith<BrushException> { convert("""{ "version": "3", "settings": {} }""") }
        assertFailsWith<BrushException> { MypaintImport.convert(pen, "  ", "n") }
    }

    @Test
    fun aSettingThatIsNotASettingIsRefused() {
        for (bad in listOf(
            """"hardness": 3""",
            """"hardness": { "inputs": {} }""",                       // no base_value
            """"hardness": { "base_value": "hard", "inputs": {} }""", // a string
            """"hardness": { "base_value": [], "inputs": {} }""",
            """"hardness": { "base_value": 1e999, "inputs": {} }""",  // overflows a Float
            """"hardness": { "base_value": 0.5, "inputs": 7 }""",
            """"hardness": { "base_value": 0.5, "inputs": { "pressure": "up" } }""",
        )) {
            assertFailsWith<BrushException>("should refuse: $bad") { convert(file(bad)) }
        }
        // `1e40` is well-formed JSON and is not a Float, which is the point of going via Double.
        assertFailsWith<BrushException> {
            convert(file(""""hardness": { "base_value": 1e40, "inputs": {} }"""))
        }
        // An unmapped setting's `base_value` is never read, so junk in one cannot refuse a good
        // brush. (Its *curves* are a different matter — see aCurveShapeIsCheckedEverywhere.)
        val ok = convert(file(
            """"radius_logarithmic": { "base_value": 0.0, "inputs": {} },
               "wobble": { "base_value": "not a number", "inputs": {} }""",
        ))
        assertEquals(emptyList(), BrushValidate.validate(ok.preset))
        assertNotNull(ok.preset.extensions["myb.wobble"])
    }

    /**
     * A malformed curve is refused **wherever** it sits — on a mapped input, on an input this
     * importer drops, and on a setting it never reads. This is the tier that did not exist: the same
     * `"inputs": { "pressure": "up" }` used to throw under `radius_logarithmic` and sail through
     * under `hardness`, so whether a file was legal depended on which setting you happened to look at.
     */
    @Test
    fun aCurveShapeIsCheckedEverywhereNotOnlyWhereItIsMapped() {
        // Mapped input: refused (as it always was).
        assertFailsWith<BrushException> {
            convert(file(""""radius_logarithmic": { "base_value": 0.0, "inputs": { "pressure": "up" } }"""))
        }
        // Dropped input on a mapped setting — the case that used to sail through.
        assertFailsWith<BrushException>("hardness maps no inputs, but the shape is still the shape") {
            convert(file(""""hardness": { "base_value": 0.5, "inputs": { "pressure": "up" } }"""))
        }
        // On a setting this importer never reads at all.
        assertFailsWith<BrushException> {
            convert(file(""""wobble": { "base_value": 0.0, "inputs": { "pressure": "up" } }"""))
        }
        // And the rest of the shape family, on an unmapped setting.
        for (bad in listOf(
            """"inputs": { "pressure": [] }""",
            """"inputs": { "pressure": [[0, 0, 0]] }""",
            """"inputs": { "pressure": [[0]] }""",
            """"inputs": { "pressure": [[0, 0], 7] }""",
            """"inputs": { "pressure": [[0, 0], [1, "a"]] }""",
            """"inputs": { "pressure": [[0, 1e999]] }""",
            """"inputs": 7""",
        )) {
            assertFailsWith<BrushException>("should refuse: $bad") { convert(file(""""wobble": { "base_value": 0.0, $bad }""")) }
        }
    }

    /**
     * …but a shape rule is not a domain rule. Several MyPaint inputs are not 0..1 — `direction_angle`
     * runs 0..360, `gridmap_x/y` 0..256, `barrel_rotation` ±180 — so a curve on an input this
     * importer does not map may honestly carry an x outside 0..1 and must be kept, not refused.
     */
    @Test
    fun anUnmappedCurveMayHaveAnXOutsideZeroToOne() {
        val wide = convert(file(""""dabs_per_second": { "base_value": 0.0, "inputs": {
            "direction_angle": [[0.0, 0.0], [360.0, 1.0]] } }"""))
        assertNotNull(wide.preset.extensions["myb.dabs_per_second"])
        assertEquals(emptyList(), BrushValidate.validate(wide.preset))
        // The same x is refused on a curve Joy will actually evaluate, because there the range is
        // known and 0..1 is what `Curve.eval` expects.
        assertFailsWith<BrushException> {
            convert(file(""""radius_logarithmic": { "base_value": 0.0, "inputs": {
                "pressure": [[0.0, 0.0], [360.0, 1.0]] } }"""))
        }
    }

    /**
     * The other half of the symmetry: an input **name** Joy Brush has never heard of is not a defect,
     * it is a loss — so it is raw-kept and named, exactly as an unknown setting is. Mapped inputs on
     * the same setting still map, so one unknown input never costs you the known ones.
     */
    @Test
    fun anInputNameNobodyHasHeardOfIsKeptLikeAnUnknownSetting() {
        val result = convert(file(""""radius_logarithmic": { "base_value": 0.0, "inputs": {
            "pressure": [[0.0, 0.0], [1.0, 0.3]],
            "tilt_ascension": [[0.0, 0.0], [1.0, 0.5]] } }"""))
        // The known input maps, through the log rule: e^0.3 = 1.3498588.
        val mapped = result.preset.size.inputs.single()
        assertEquals(BrushInput.pressure, mapped.input)
        assertEquals(1.3498588f, mapped.curve[1][1], 1e-6f)
        // The unknown one is raw-kept and named, and the warning is one per setting.
        val kept = result.preset.extensions.getValue("myb.radius_logarithmic")
        assertTrue(kept.contains("tilt_ascension"), "kept: $kept")
        warnsAbout(result, "radius_logarithmic", "tilt_ascension", "not mapped")
        assertEquals(1, result.warnings.count { it.contains("tilt_ascension") }, "${result.warnings}")
    }

    /**
     * `hardness` is "as is" per the MyPaint spec, so 1e30 is passed through untouched — and the three
     * facts this test pinned before JB-0.03c are all still true: the value crosses intact, the
     * importer warns that it did, and the three real brushes get no such warning.
     *
     * What changed is the fourth: the preset the importer produced is now REFUSED by [BrushValidate].
     * That is rule 24 doing in the validator what `TipMath.coverage` used to do silently at the
     * canvas, and it is why this test was REWRITTEN rather than deleted. The importer is deliberately
     * left alone: clamping 1e30 here would invent a rendering decision (does `jb_tip.glsl` saturate
     * above 1, or is it an error?) that is not the importer's to make. So the value crosses intact,
     * the importer says so, and the importer's own validation sweep says the caller a second time
     * that the brush it has just built is one Joy Brush will not load.
     */
    @Test
    fun aHardnessOutsideZeroToOneIsPassedThroughWarnedAboutAndNowRefused() {
        val wild = convert(file(""""hardness": { "base_value": 1e30, "inputs": {} }"""))
        assertEquals(1e30f, wild.preset.tip.hardness.base, 1e20f)   // untouched — still no clamp
        warnsAbout(wild, "hardness", "outside", "passed through unchanged")
        // …and refused, in the validator's own words. `1e30` is a legal Float, so this is the range
        // rule and not rule 16's finiteness: 1e30 is very much a number.
        val problems = BrushValidate.validate(wild.preset)
        assertTrue(
            problems.any { it.contains("tip.hardness") && it.contains("outside 0..1") },
            "the preset the importer produced must now be refused: $problems",
        )
        warnsAbout(wild, "imported brush would be refused")
        // The three real brushes are all inside 0..1, so none of them gets this warning. (Checked on
        // the message, not on the word "hardness" — the pen does warn about a *dropped hardness
        // input*, and that is a different sentence about a different thing.)
        for (json in listOf(pen, charcoal, basicDigital)) {
            val r = convert(json)
            assertTrue(r.warnings.none { it.contains("passed through unchanged") }, "${r.warnings}")
        }
    }

    @Test
    fun aCurveIsRefusedRatherThanTrimmedToFit() {
        // 65 points, one over BrushValidate's cap. Refused, with the count, before one point is copied.
        val tooMany = (0..64).joinToString(", ") { "[${it / 64.0}, 0.0]" }
        val thrown = assertFailsWith<BrushException> {
            convert(file(""""radius_logarithmic": { "base_value": 0.0, "inputs": {
                 "pressure": [$tooMany] } }"""))
        }
        assertTrue(thrown.message.orEmpty().contains("65 points, at most 64"), "message was: ${thrown.message}")
        // 64 is fine, and the cap's edge is included.
        val exactly = (0..63).joinToString(", ") { "[${it / 63.0}, 0.0]" }
        val ok = convert(file(""""radius_logarithmic": { "base_value": 0.0, "inputs": {
             "pressure": [$exactly] } }"""))
        assertEquals(64, ok.preset.size.inputs.single().curve.size)
        assertEquals(emptyList(), BrushValidate.validate(ok.preset))
    }

    @Test
    fun aCurveWithNoPointsOrAWrongShapeIsRefused() {
        for (bad in listOf(
            """"pressure": []""",
            """"pressure": [[0, 0, 0]]""",
            """"pressure": [[0]]""",
            """"pressure": [[0, 0], 5]""",
            """"pressure": [[-0.5, 0.0]]""",           // x below 0
            """"pressure": [[1.5, 0.0]]""",            // x above 1
            """"pressure": [[0, 0], [0, "a"]]""",
            """"pressure": [[0, 1000]]""",             // e^1000 is not a number
        )) {
            assertFailsWith<BrushException>("should refuse: $bad") {
                convert(file(""""radius_logarithmic": { "base_value": 0.0, "inputs": { $bad } }"""))
            }
        }
        // A tilt x outside 0..90° is refused on the same rule, after the division.
        assertFailsWith<BrushException> {
            convert(file(""""radius_logarithmic": { "base_value": 0.0, "inputs": {
                 "tilt_declination": [[0.0, 0.0], [120.0, 0.5]] } }"""))
        }
    }

    @Test
    fun aHugeOrDeepFileIsRefusedBeforeItIsParsed() {
        // The length cap. 300 kB of a real brush's header repeated is not a brush file.
        val enormous = "{ \"comment\": \"" + "x".repeat(300_000) + "\", \"version\": 3, \"settings\": {} }"
        val thrown = assertFailsWith<BrushException> { convert(enormous) }
        assertTrue(thrown.message.orEmpty().contains("at most"), "message was: ${thrown.message}")
        // The depth cap, which is the one that stops `[[[[[[…` from running the parser out of stack.
        assertFailsWith<BrushException> { convert("[".repeat(500)) }
        assertFailsWith<BrushException> { convert("[".repeat(500) + "]".repeat(500)) }
        // More settings than libmypaint ships. Built, not parsed twice: the check is on the count.
        val many = (0..300).joinToString(",") { """"s$it": { "base_value": 0.0, "inputs": {} }""" }
        val crowded = assertFailsWith<BrushException> { convert(file(many)) }
        assertTrue(crowded.message.orEmpty().contains("at most 256 settings"), "message was: ${crowded.message}")
    }

    // ---- 8. what survives being clamped is still a legal brush ------------------------------------

    @Test
    fun clampedNumbersLandSomewhereBrushValidateAccepts() {
        val result = convert(file(
            """"radius_logarithmic": { "base_value": 200.0, "inputs": {} },
               "opaque": { "base_value": 4.0, "inputs": {} },
               "offset_by_random": { "base_value": -4.0, "inputs": {} },
               "slow_tracking": { "base_value": -5.0, "inputs": {} },
               "elliptical_dab_ratio": { "base_value": 0.0, "inputs": {} }""",
        ))
        val p = result.preset
        // 2·e^200 overflows a Float, so it is a clamp rather than a number.
        assertEquals(4096f, p.size.base)
        assertEquals(1f, p.opacity.base)
        assertEquals(0f, p.scatter.amount.base)
        assertEquals(0f, p.smoothing)
        assertEquals(0f, p.tip.aspect)          // ratio below 1 is the same round dab as 1
        assertEquals(emptyList(), BrushValidate.validate(p))
        for (w in listOf("size", "opaque 4.0", "offset_by_random -4.0", "smoothing -0.5")) {
            warnsAbout(result, w)
        }
        // …and an underflowing size is floored rather than becoming a zero-diameter brush.
        val tiny = convert(file(""""radius_logarithmic": { "base_value": -1000.0, "inputs": {} }"""))
        assertEquals(0.01f, tiny.preset.size.base)
        assertEquals(emptyList(), BrushValidate.validate(tiny.preset))
        // A setting that is simply absent does not warn about a value it never had.
        assertEquals(0f, convert(file(""""smudge": { "base_value": 0, "inputs": {} }""")).preset.scatter.amount.base)
        assertEquals(1f, convert(file(""""smudge": { "base_value": 0, "inputs": {} }""")).preset.opacity.base)
    }

    /** A `.myb` with no settings at all still converts: it is a small round brush, and it says so. */
    @Test
    fun anEmptyBrushIsStillABrush() {
        val result = convert("""{ "version": 3, "settings": {} }""")
        assertEquals(emptyList(), BrushValidate.validate(result.preset))
        val p = result.preset
        // 2·e^2 = 2 × 7.3890561 = 14.7781122 — MyPaint's own default base of 2.0.
        assertEquals(14.7781122f, p.size.base, 1e-4f)
        assertEquals(0.1f, p.spacing, 1e-6f)
        assertEquals(0.9f, result.preset.tip.hardness.base)
        assertEquals(0f, result.preset.smoothing)
        assertTrue(result.preset.extensions.isEmpty())
        warnsAbout(result, "no radius_logarithmic", "default base 2.0", "px diameter")
    }

    /**
     * The absent-setting fallback must go through the *same* `2·e^b` transform as the mapped path.
     *
     * This pins the bug this file once had: the fallback assigned the log base 2.0 straight into
     * `size.base`, so a `.myb` with no `radius_logarithmic` came out at 2 **pixels** instead of
     * 14.78 — legal, valid, and a completely different brush from the one MyPaint would have drawn.
     */
    @Test
    fun theAbsentRadiusFallbackIsInDiameterNotLogSpace() {
        val absent = convert(file(""""smudge": { "base_value": 0, "inputs": {} }""")).preset.size.base
        val explicit = convert(file(""""radius_logarithmic": { "base_value": 2.0, "inputs": {} }""")).preset.size.base
        assertEquals(2f * 7.3890561f, explicit, 1e-4f)     // 2·e^2 = 14.7781122 px
        assertEquals(explicit, absent, 0f, "the fallback must be the same size as base 2.0")
        // …and it is a real size, not a log radius that happened to be small.
        assertTrue(absent > 14f && absent < 15f, "was $absent")
    }

    /** Every [Param] the importer builds is a multiply, which is what BrushValidate allows. */
    @Test
    fun everyParamCombinesByMultiply() {
        val p = convert(basicDigital).preset
        for (param in listOf(p.size, p.opacity, p.flow, p.tip.angle, p.tip.hardness, p.scatter.amount)) {
            assertEquals("multiply", param.combine, "$param")
        }
        assertEquals(Param(1f).combine, p.flow.combine)
    }
}
