package cc.joycreator.textlab;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Studio's "Edit in TextLab" round trip: the title's own look (its sliders folded in) opens in the TextLab room;
 * "Use on the title" hands the edited look back to the title as one undo step. The hand-back is a callback held
 * here (Studio's editor stays alive underneath the room), so the 45,000-line editor needs no result plumbing.
 */
public final class TlTitleEdit {

    @Nullable private static Consumer<String> waiting;
    @Nullable private static Activity owner;

    private TlTitleEdit() {}

    /** Open the room on {@code t} (with {@code words}); {@code done} gets the edited look's JSON. */
    public static void start(@NonNull Activity from, @NonNull TitleLook t, @NonNull String words,
                             @NonNull Consumer<String> done) {
        waiting = done;
        owner = from;
        from.startActivity(new Intent(from, TextLabActivity.class)
                .putExtra(TextLabActivity.EXTRA_TITLE_LOOK, forEditing(t).toString())
                .putExtra(TextLabActivity.EXTRA_WORDS, words));
    }

    /** The title's look with its own slider changes written into it (what the room edits). */
    @NonNull
    static JsonObject forEditing(@NonNull TitleLook t) {
        JsonObject look = t.look.deepCopy();
        JsonObject p = look.has("p") && look.get("p").isJsonObject() ? look.getAsJsonObject("p") : new JsonObject();
        for (Map.Entry<String, JsonElement> k : t.knobs.entrySet()) p.add(k.getKey(), k.getValue());
        look.add("p", p);
        if (!look.has("name")) look.addProperty("name", t.name);
        return look;
    }

    /** The room: the person chose "Use on the title". False when Studio is gone (nothing to hand back to). */
    static boolean deliver(@NonNull String lookJson) {
        Consumer<String> w = waiting;
        Activity o = owner;
        waiting = null;
        owner = null;
        if (w == null || o == null || o.isFinishing() || o.isDestroyed()) return false;
        w.accept(lookJson);
        return true;
    }

    /** The room closed without handing anything back. */
    static void cancel() {
        waiting = null;
        owner = null;
    }
}
