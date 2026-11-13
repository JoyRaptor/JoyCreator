package cc.joycreator.textlab;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeCodec;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A Studio title wearing a TextLab look (TEXT-1, Studio step). Saved inside the title as its own block
 * ({@code "textLook"}), never in the title's own keyframes (a track there would switch on the title's automatic
 * position keys when it is dragged).
 *
 * <ul>
 *   <li>{@code look}: a whole copy of the look file, so a project still draws the same if the pack it came from is
 *       removed or updated. The look is opaque data: kept whole, unknown keys untouched.</li>
 *   <li>{@code knobs}: the person's changes, by knob name (TextLab's own names).</li>
 *   <li>The light, by the owner's ruling never live in Studio: {@code still} (one point, set on a pad: the look
 *       book's Pinned), {@code sway} (a gentle loop round that point: the look book's default for video) or
 *       {@code take} (recorded with the phone's tilt, kept as keys in {@code keys}). Export reads only this.</li>
 *   <li>Progress, for looks that burn, blow away, grow or shatter: by itself from the title's start, unless the
 *       {@code progress} track pins it (frontGone with frontAuto 0, or shatter with shatterAuto 0).</li>
 * </ul>
 * Times in {@code keys} are the title's own (ms from its start), as the title's other keyframes are.
 *
 * <p>Plain saved data (Gson and the keyframe classes only): the drawing side, which sets this on a renderer, is
 * {@link StudioTextLab}. That keeps the project's own model free of the engine (and testable off the phone).</p>
 */
public final class TitleLook {

    public static final String STILL = "still", SWAY = "sway", TAKE = "take";
    public static final String LIGHT_X = "lightX", LIGHT_Y = "lightY", PROGRESS = "progress";

    @NonNull public String key;
    @NonNull public String name;
    @NonNull public JsonObject look;
    @NonNull public JsonObject knobs = new JsonObject();
    @NonNull public String light = SWAY;
    public float stillX, stillY;
    @NonNull public final KeyframeSet keys;

    public TitleLook(@NonNull String key, @NonNull String name, @NonNull JsonObject look) {
        this(key, name, look, new KeyframeSet());
    }

    private TitleLook(@NonNull String key, @NonNull String name, @NonNull JsonObject look, @NonNull KeyframeSet keys) {
        this.key = key;
        this.name = name;
        this.look = look;
        this.keys = keys;
    }

    /** A title's look from a look file's text (a shelf item: its key, its name and its JSON). */
    @NonNull
    public static TitleLook of(@NonNull String key, @NonNull String name, @NonNull String lookJson) {
        return new TitleLook(key, name, JsonParser.parseString(lookJson).getAsJsonObject());
    }

    @NonNull
    public TitleLook copy() {
        TitleLook c = new TitleLook(key, name, look.deepCopy(), keys.copy());
        c.knobs = knobs.deepCopy();
        c.light = light;
        c.stillX = stillX;
        c.stillY = stillY;
        return c;
    }

    // ---- saving --------------------------------------------------------------------------------------

    @NonNull
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("name", name);
        o.add("look", look);
        if (knobs.size() > 0) o.add("knobs", knobs);
        o.addProperty("light", light);
        if (stillX != 0) o.addProperty("stillX", stillX);
        if (stillY != 0) o.addProperty("stillY", stillY);
        if (!keys.isEmpty()) o.add("keys", KeyframeCodec.toJson(keys));
        return o;
    }

    /** A title's look from its saved block, or null when the block is not readable (the title keeps its plain text). */
    @Nullable
    public static TitleLook fromJson(@Nullable JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        JsonElement look = o.get("look");
        if (look == null || !look.isJsonObject()) return null;
        TitleLook t = new TitleLook(str(o, "key", ""), str(o, "name", "Look"), look.getAsJsonObject(),
                o.has("keys") && o.get("keys").isJsonObject() ? KeyframeCodec.fromJsonOrEmpty(o.getAsJsonObject("keys"))
                        : new KeyframeSet());
        if (o.has("knobs") && o.get("knobs").isJsonObject()) t.knobs = o.getAsJsonObject("knobs");
        String l = str(o, "light", SWAY);
        t.light = STILL.equals(l) || TAKE.equals(l) ? l : SWAY;
        t.stillX = num(o, "stillX");
        t.stillY = num(o, "stillY");
        return t;
    }

    private static String str(JsonObject o, String k, String d) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : d;
    }

    private static float num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsFloat() : 0f;
        } catch (RuntimeException ex) {
            return 0f;
        }
    }

    // ---- at a moment -----------------------------------------------------------------------------

    /** Where the light is at {@code localMs} (the title's own time), -1..1 each way. Never reads a sensor. */
    @NonNull
    public double[] tiltAt(long localMs) {
        double x = stillX, y = stillY;
        if (SWAY.equals(light)) {
            double s = localMs / 1000.0;
            x += 0.4 * Math.sin(1.1 * s);
            y += 0.28 * Math.sin(0.8 * s + 1);
        } else if (TAKE.equals(light)) {
            x = keys.valueAt(LIGHT_X, localMs, stillX);
            y = keys.valueAt(LIGHT_Y, localMs, stillY);
        }
        return new double[]{clamp(x), clamp(y)};
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    /** True when the look burns, blows away, grows or shatters: it has a progress the person can pin. */
    public boolean hasProgress() {
        return feature("FRONT") || feature("SHATTER");
    }

    public boolean progressPinned() {
        KeyframeTrack t = keys.get(PROGRESS);
        return t != null && !t.isEmpty();
    }

    /** Pin the progress at {@code localMs} to {@code value} (0..1): one key. */
    public void pinProgress(long localMs, float value) {
        keys.getOrCreate(PROGRESS).put(localMs, Math.max(0f, Math.min(1f, value)), Easing.LINEAR);
    }

    public boolean feature(@NonNull String f) {
        JsonElement fs = look.get("features");
        if (fs == null || !fs.isJsonArray()) return false;
        for (JsonElement e : fs.getAsJsonArray()) if (e.isJsonPrimitive() && f.equals(e.getAsString())) return true;
        return false;
    }

    /** Changes when what the look draws changes (not the light or the time): the renderer re-sets the look then. */
    @NonNull
    public String lookSignature() {
        return look.toString().hashCode() + "|" + knobs.toString().hashCode();
    }

    /** For a title's own cache keys: everything that changes the picture apart from time. */
    @NonNull
    public String signature() {
        // (the codec gives null for no keys at all)
        return lookSignature() + "|" + light + "|" + stillX + "," + stillY + "|"
                + (keys.isEmpty() ? 0 : String.valueOf(KeyframeCodec.toJson(keys)).hashCode());
    }

    /** The look's sample words, when the title has none. */
    @NonNull
    public String sample() {
        return str(look, "sample", "TEXT");
    }
}
