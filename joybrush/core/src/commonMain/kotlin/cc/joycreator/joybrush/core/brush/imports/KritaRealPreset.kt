package cc.joycreator.joybrush.core.brush.imports

/**
 * BRUSH-IMPORT (11-13): real Krita presets, into the layout [KritaImport] already maps.
 *
 * Krita writes a preset as `<Preset paintopid="paintbrush" name="…">` followed by a flat list of
 * `<param type="…" name="…">value</param>` entries (about 200 for the Pixel engine), with nested XML such as the tip
 * (`brush_definition`) and the sensor curves (`SizeSensor`, …) held as CDATA text. [KritaImport] was written against
 * a simplified `<paintop id="Pixel">` layout and refused every real file ("its preset XML has no <paintop id>").
 * This translator turns the real form into that layout, so every rule, limit and warning [KritaImport] already has
 * applies unchanged:
 *
 * - `paintopid` → `<paintop id>`, with Krita's internal engine ids turned into the names [KritaImport] gates on
 *   (`paintbrush` → `Pixel`, `colorsmudge` → `ColorSmudge`, `hairybrush` → `Hairy`, …; unknown ids pass through and
 *   are refused there by name).
 * - The tip: an `auto_brush` mask generator becomes `<brush_definition type="auto_mask">` with its `1;ratio` cell and
 *   `<param name="PixelSize">` from its diameter; a picture tip by file name becomes a named predefined tip (kept by
 *   name, not drawn: the file is not in the preset). The tip's `spacing` becomes `<spacing>`.
 * - Each sensor that is switched on (`PressureSize`, `PressureOpacity`, …) becomes one `<curve sensor= name=>` per
 *   input it lists, with the input's own curve (Krita's default straight line when it gives none).
 * - Every other short setting is kept as `<raw_…>` so [KritaImport] stores it raw in `extensions`, as it does for
 *   anything it cannot map.
 *
 * Returns null when [presetXml] is not the real form, so the old layout keeps reading exactly as before.
 */
internal object KritaRealPreset {
    private val PRESET = Regex("""^\s*(?:<\?xml[^>]*\?>\s*)?<Preset\b([^>]*)>""")
    private val PARAM = Regex("""<param\b([^>]*)>(.*?)</param>""", RegexOption.DOT_MATCHES_ALL)
    private val ATTR = Regex("""([A-Za-z_:][\w.:-]*)\s*=\s*("[^"]*"|'[^']*')""")
    private val CDATA = Regex("""^\s*<!\[CDATA\[(.*)]]>\s*$""", RegexOption.DOT_MATCHES_ALL)

    /** Krita's internal engine ids → the names [KritaImport]'s engine gate speaks. */
    private val ENGINES = mapOf(
        "paintbrush" to "Pixel", "colorsmudge" to "ColorSmudge", "hairybrush" to "Hairy",
        "spraybrush" to "Spray", "sketchbrush" to "Sketch", "deformbrush" to "Deform",
        "particlebrush" to "Particle", "curvebrush" to "Curve", "hatchingbrush" to "Hatching",
        "gridbrush" to "Grid", "experimentbrush" to "Shape", "filter" to "Filter",
        "tangentnormal" to "TangentNormal", "roundmarker" to "Quick",
    )

    /** A switched-on sensor setting → the curve name [KritaImport] maps it with. */
    private val SENSORS = listOf(
        "Size" to "size", "Opacity" to "opacity", "Flow" to "flow",
        "Rotation" to "rotation", "Scatter" to "scattering",
    )

    fun translate(presetXml: String): String? {
        val head = PRESET.find(presetXml) ?: return null
        val presetAttrs = attrs(head.groupValues[1])
        val engineId = presetAttrs["paintopid"]?.trim().orEmpty()
        val params = LinkedHashMap<String, String>()
        for (m in PARAM.findAll(presetXml, head.range.last + 1)) {
            val name = attrs(m.groupValues[1])["name"] ?: continue
            val raw = m.groupValues[2]
            params[name] = CDATA.find(raw)?.groupValues?.get(1) ?: unescape(raw.trim())
        }
        val out = StringBuilder()
        val engine = if (engineId.isEmpty()) "" else ENGINES[engineId.lowercase()] ?: engineId
        out.append("<paintop")
        if (engine.isNotEmpty()) out.append(" id=\"").append(esc(engine)).append('"')
        out.append('>')
        params["brush_definition"]?.let { tip(it, out) }
        for ((setting, curveName) in SENSORS) {
            if (params["Pressure$setting"]?.trim() != "true") continue
            sensorCurves(params["${setting}Sensor"].orEmpty(), curveName, out)
        }
        for ((name, value) in params) {
            if (name == "brush_definition" || name.endsWith("Sensor") || value.length > MAX_RAW_CHARS) continue
            out.append("<raw_").append(elementName(name)).append(" value=\"").append(esc(value)).append("\"/>")
        }
        out.append("</paintop>")
        return out.toString()
    }

    private fun tip(definition: String, out: StringBuilder) {
        val brush = Regex("""<Brush\b([^>]*)>""").find(definition)?.let { attrs(it.groupValues[1]) } ?: return
        val type = brush["type"]?.lowercase().orEmpty()
        brush["spacing"]?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let {
            out.append("<spacing mode=\"space\" value=\"").append(it).append("\"/>")
        }
        if (type == "auto_brush") {
            val mask = Regex("""<MaskGenerator\b([^>]*)/?>""").find(definition)?.let { attrs(it.groupValues[1]) }
            val ratio = mask?.get("ratio")?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 1f
            out.append("<brush_definition type=\"auto_mask\"><cell row=\"0\" col=\"0\" value=\"1;")
                .append(ratio.coerceIn(0f, 1f)).append("\"/></brush_definition>")
            mask?.get("diameter")?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let {
                out.append("<param type=\"range\" name=\"PixelSize\" value=\"").append(it).append("\"/>")
            }
        } else {
            val file = brush["filename"] ?: brush["name"] ?: ""
            out.append("<brush_definition type=\"predefined\" name=\"").append(esc(file)).append("\"/>")
        }
    }

    /**
     * `<params id="pressure"><curve>…</curve></params>` (one input) or `<params id="sensorslist">` with
     * `<ChildSensor id="…">` children (several). A sensor with no `<curve>` is Krita's default straight line.
     */
    private fun sensorCurves(sensorXml: String, curveName: String, out: StringBuilder) {
        val children = Regex("""<ChildSensor\b([^>]*?)(/>|>(.*?)</ChildSensor>)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(sensorXml).toList()
        val inputs: List<Pair<String, String?>> = if (children.isNotEmpty()) {
            children.mapNotNull { m ->
                val id = attrs(m.groupValues[1])["id"] ?: return@mapNotNull null
                id to curveOf(m.groupValues[3])
            }
        } else {
            val id = Regex("""<params\b([^>]*)""").find(sensorXml)?.let { attrs(it.groupValues[1])["id"] }
                ?: "pressure"
            listOf(id to curveOf(sensorXml))
        }
        for ((id, curve) in inputs) {
            if (id == "sensorslist") continue
            out.append("<curve sensor=\"").append(esc(id)).append("\" name=\"").append(curveName)
                .append("\" commonCurve=\"").append(esc(curve ?: "0,0;1,1")).append("\"/>")
        }
    }

    private fun curveOf(xml: String): String? =
        Regex("""<curve>(.*?)</curve>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.trim()?.trimEnd(';')?.takeIf { it.isNotEmpty() }

    private fun attrs(text: String): Map<String, String> =
        ATTR.findAll(text).associate { it.groupValues[1] to unescape(it.groupValues[2].substring(1, it.groupValues[2].length - 1)) }

    /** "MaskingBrush/Preset/FlowValue" → "MaskingBrush_Preset_FlowValue": a legal element name, still readable. */
    private fun elementName(name: String): String =
        name.replace(Regex("""[^\w.-]"""), "_").ifEmpty { "unnamed" }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private const val MAX_RAW_CHARS = 200
}
