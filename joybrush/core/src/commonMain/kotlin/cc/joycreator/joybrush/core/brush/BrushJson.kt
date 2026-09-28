package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.json.Json

/** Thrown by [BrushJson] when a brush file cannot be read or written. */
class BrushException(message: String) : Exception(message)

/** The format tag and version in every brush.json. Bump with the defaults in [BrushPreset]. */
const val BRUSH_FORMAT = "joybrush.brush"
const val BRUSH_VERSION = 1

/**
 * brush.json — one brush, the smallest thing a person can share. It is written by Joy Brush, by the
 * Phase 8 importers (Photoshop `.abr`, Procreate, MyPaint, Krita) and by hand, so it is written
 * pretty with keys in declaration order, states its defaults outright, and ignores keys it does not
 * know when reading — a brush from a later Joy Brush still opens here.
 */
object BrushJson {

    private val FORMAT = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(p: BrushPreset): String = try {
        FORMAT.encodeToString(BrushPreset.serializer(), p)
    } catch (e: IllegalArgumentException) {
        // A preset built in memory can hold a NaN or an Infinity; JSON has no word for either, and
        // the person saving the brush should be told which brush failed, not which library threw.
        throw BrushException("brush cannot be written: ${e.message}")
    }

    fun decode(json: String): BrushPreset = try {
        FORMAT.decodeFromString(BrushPreset.serializer(), json)
    } catch (e: IllegalArgumentException) {
        // SerializationException and JsonDecodingException both land here.
        throw BrushException("brush.json cannot be read: ${e.message}")
    }
}
