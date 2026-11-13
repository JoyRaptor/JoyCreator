package cc.joycreator.textlab;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.gl.ShaderLib;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.util.Map;

/**
 * A look's own sliders and colours for a look worn outside the TextLab room (TX-02: VisLab's "Words in a look"; the
 * same panel can serve Studio's titles). It is the room's panel ({@link TlKnobPanel}, built from {@code parts.json})
 * without its Pieces tab: what a look is built from is changed in the room.
 *
 * <p>Changes go into {@link TitleLook#knobs} (the person's changes by knob name), never into the look itself, so
 * "back to the look as it came" is emptying them.</p>
 */
public final class LookKnobs {

    /** What the place wearing the look does with a change. */
    public interface Listener {
        /** Before a change, once (a slider grabbed, a colour dialog opened): one undo step. */
        void begin(@NonNull String what);

        /** A slider or colour moved (live, many times a drag): draw the look again. */
        void changed();
    }

    private static TlParts parts;

    private LookKnobs() {}

    /** The slider panel for {@code look}, or null when TextLab's parts list cannot be read on this phone. */
    @Nullable
    public static View panel(@NonNull Activity a, @NonNull TitleLook look, @NonNull Listener l) {
        TlParts p = parts(a);
        if (p == null) return null;
        TlKnobPanel panel = new TlKnobPanel(a, p, new TlKnobPanel.Host() {
            @NonNull
            @Override
            public JSONObject look() {
                return worn(look);
            }

            @Override
            public void beginChange(@NonNull String what) {
                l.begin(what);
            }

            @Override
            public void knob(@NonNull String name, @NonNull Object value) {
                if (value instanceof Number) look.knobs.addProperty(name, (Number) value);
                else look.knobs.addProperty(name, String.valueOf(value));
                l.changed();
            }

            @Override
            public void pieces() {
            }

            @Override
            public void settled() {
            }
        }, false);
        panel.refresh();
        return panel;
    }

    /** Show the panel again for the look as it is now (after an undo, or the changes were emptied). */
    public static void refresh(@Nullable View panel) {
        if (panel instanceof TlKnobPanel) ((TlKnobPanel) panel).refresh();
    }

    /** The look with the person's changes laid over its own values ({@code p}), as the room's panel reads it. */
    @NonNull
    public static JSONObject worn(@NonNull TitleLook t) {
        try {
            JSONObject o = new JSONObject(t.look.toString());
            JSONObject p = o.optJSONObject("p");
            if (p == null) o.put("p", p = new JSONObject());
            for (Map.Entry<String, JsonElement> k : t.knobs.entrySet()) {
                JsonElement v = k.getValue();
                if (!v.isJsonPrimitive()) continue;
                p.put(k.getKey(), v.getAsJsonPrimitive().isNumber() ? (Object) v.getAsDouble() : v.getAsString());
            }
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** True when the person has changed any of the look's sliders or colours. */
    public static boolean changed(@NonNull TitleLook t) {
        return t.knobs.size() > 0;
    }

    /** Back to the look as it came. */
    public static void reset(@NonNull TitleLook t) {
        t.knobs = new JsonObject();
    }

    @Nullable
    private static synchronized TlParts parts(@NonNull Context c) {
        if (parts == null) {
            try {
                parts = new TlParts(new JSONObject(ShaderLib.readAll(c.getAssets().open("textlab/parts.json"))));
            } catch (Exception e) {
                return null;
            }
        }
        return parts;
    }
}
