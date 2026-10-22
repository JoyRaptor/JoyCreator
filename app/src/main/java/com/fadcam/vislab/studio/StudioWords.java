package com.fadcam.vislab.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.transcript.Transcript;
import com.fadcam.ui.faditor.transcript.TranscriptWord;
import com.fadcam.vislab.words.WordFeed;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A clip's transcript as a VisLab look hears it (VISLAB_SPEC section 7, S5): the words that are
 * not struck out, timed in seconds of the clip's SOURCE (the clock the look already hears the
 * music on), with a key that changes whenever any word does, so the GL side rebuilds its glyph
 * feed only on a real change. Words a look's layer has typed itself always win over these.
 */
public final class StudioWords {

    /** Unstruck words, source seconds, in transcript order. */
    @NonNull public final List<WordFeed.Word> words;
    /** Changes when any word's text, time or struck state does. */
    public final long key;

    private StudioWords(@NonNull List<WordFeed.Word> words, long key) {
        this.words = Collections.unmodifiableList(words);
        this.key = key;
    }

    /** The words of {@code t}, or null when there is no transcript or every word is struck. */
    @Nullable
    public static StudioWords of(@Nullable Transcript t) {
        if (t == null || t.words.isEmpty()) return null;
        List<WordFeed.Word> out = new ArrayList<>(t.words.size());
        for (TranscriptWord w : t.words) {
            if (!shown(w)) continue;
            out.add(new WordFeed.Word(w.text.trim(), w.startMs / 1000.0, w.endMs / 1000.0));
        }
        return out.isEmpty() ? null : new StudioWords(out, keyOf(t));
    }

    /** {@link #key} without building the list: cheap enough to check every preview frame. */
    public static long keyOf(@Nullable Transcript t) {
        if (t == null) return 0;
        long h = 0xcbf29ce484222325L;   // FNV-1a over the shown words
        for (TranscriptWord w : t.words) {
            if (!shown(w)) continue;
            h = (h ^ w.text.hashCode()) * 0x100000001b3L;
            h = (h ^ w.startMs) * 0x100000001b3L;
            h = (h ^ w.endMs) * 0x100000001b3L;
        }
        return h == 0 ? 1 : h;
    }

    private static boolean shown(@NonNull TranscriptWord w) {
        return !w.struck && w.text != null && !w.text.trim().isEmpty();
    }

    /** True when the look shows the song's words: a Words layer with no typed words of its own. */
    public static boolean lookNeedsSongWords(@Nullable String lookJson) {
        if (lookJson == null) return false;
        try {
            JsonObject root = JsonParser.parseString(lookJson).getAsJsonObject();
            JsonArray layers = root.has("layers") ? root.getAsJsonArray("layers") : null;
            if (layers == null) return false;
            for (JsonElement e : layers) {
                if (!e.isJsonObject()) continue;
                JsonObject L = e.getAsJsonObject();
                if (!L.has("type") || !"words".equals(L.get("type").getAsString())) continue;
                JsonElement text = L.get("text");
                if (text == null || !text.isJsonPrimitive() || text.getAsString().trim().isEmpty()) return true;
            }
        } catch (RuntimeException ignored) {
            // an unreadable look asks for nothing
        }
        return false;
    }
}
