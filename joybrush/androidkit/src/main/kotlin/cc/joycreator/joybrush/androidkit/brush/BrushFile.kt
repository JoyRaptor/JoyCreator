package cc.joycreator.joybrush.androidkit.brush

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * BRUSH-IMPORT step 1: one brush as ONE file the person owns, `Supplies/Brushes/<Name>.brush.json`. The file is the
 * ordinary brush recipe ([BrushJson], readable and hand-editable) with one more top-level key, `"assets"`: each tip
 * picture the recipe names, as base64 PNG under the exact path the recipe uses. [BrushJson] ignores keys it does not
 * know, so the recipe half reads as any other brush file; the pictures come back through [FrozenBrushPngs] with its
 * own count and size limits. A file with no pictures has no `"assets"` key at all.
 */
object BrushFile {
    const val ENDING = ".brush.json"
    private const val ASSETS = "assets"
    private val PRETTY = Json { prettyPrint = true }

    class Read(val preset: BrushPreset, val images: FrozenBrushPngs)

    fun write(preset: BrushPreset, images: FrozenBrushPngs): String {
        val recipe = Json.parseToJsonElement(BrushJson.encode(preset)).jsonObject
        if (images.paths.isEmpty()) return PRETTY.encodeToString(JsonObject.serializer(), recipe)
        val withAssets = buildJsonObject {
            for ((k, v) in recipe) put(k, v)
            put(ASSETS, buildJsonObject {
                for (path in images.paths.sorted()) put(path, JsonPrimitive(Base64.getEncoder().encodeToString(images.requirePng(path))))
            })
        }
        return PRETTY.encodeToString(JsonObject.serializer(), withAssets)
    }

    /** Reads and checks a brush file. Throws [BrushException] with a reason for the log; callers never show it. */
    fun read(text: String): Read {
        val preset = BrushJson.decodeChecked(text)
        val root = try { Json.parseToJsonElement(text).jsonObject } catch (e: Exception) { throw BrushException("not a JSON object") }
        val assets = root[ASSETS]?.let { el ->
            (el as? JsonObject ?: throw BrushException("\"assets\" is not an object")).mapValues { (path, v) ->
                try { Base64.getDecoder().decode(v.jsonPrimitive.content) }
                catch (e: Exception) { throw BrushException("tip picture $path is not base64") }
            }
        } ?: emptyMap()
        val images = try { FrozenBrushPngs.snapshot(assets) } catch (e: IllegalArgumentException) { throw BrushException(e.message ?: "bad tip pictures") }
        return Read(preset, images)
    }
}
