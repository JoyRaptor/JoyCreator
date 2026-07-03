package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ONE new primitive of the sprite feature (PLAN_SPRITE_ANIMATION §Data model):
 * a discrete, STEP/HOLD frame track. Each entry says "from this item-local time,
 * show this cell (or run this preset)" and HOLDS until the next entry — there is
 * deliberately no interpolation here; eased motion lives in the item's
 * {@code KeyframeSet}, discrete frames live here. That split is the core
 * correctness rule of the feature.
 *
 * <p>Entries are kept sorted by {@link Key#timeMs}. Times are ITEM-LOCAL
 * ({@code timelineMs - item.startMs}), matching text-overlay keyframe semantics.</p>
 */
public class FrameTrack {

    /** One entry: at {@code timeMs}, either a direct cell index or a preset reference. */
    public static class Key {
        public long timeMs;
        /** Direct cell index, or -1 when {@link #presetId} is set. */
        public int cellIndex;
        /** Preset reference (runs from this entry's time at the preset's fps), or null. */
        @Nullable public String presetId;

        public static Key ofCell(long timeMs, int cellIndex) {
            Key k = new Key();
            k.timeMs = Math.max(0, timeMs);
            k.cellIndex = Math.max(0, cellIndex);
            return k;
        }

        public static Key ofPreset(long timeMs, @NonNull String presetId) {
            Key k = new Key();
            k.timeMs = Math.max(0, timeMs);
            k.cellIndex = -1;
            k.presetId = presetId;
            return k;
        }
    }

    @NonNull private final List<Key> keys = new ArrayList<>();

    /** Sorted, live list (mutations via {@link #put}/{@link #removeAt} preferred). */
    @NonNull public List<Key> keys() { return keys; }

    public boolean isEmpty() { return keys.isEmpty(); }
    public int size() { return keys.size(); }

    /**
     * Insert or replace the entry at {@code key.timeMs} (exact-time match replaces —
     * mirrors {@code KeyframeTrack.put} semantics). Keeps the list sorted.
     */
    public void put(@NonNull Key key) {
        for (int i = 0; i < keys.size(); i++) {
            long t = keys.get(i).timeMs;
            if (t == key.timeMs) { keys.set(i, key); return; }
            if (t > key.timeMs) { keys.add(i, key); return; }
        }
        keys.add(key);
    }

    /** Remove the entry at exactly {@code timeMs}; returns it, or null if none. */
    @Nullable
    public Key removeAt(long timeMs) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).timeMs == timeMs) return keys.remove(i);
        }
        return null;
    }

    /**
     * STEP/HOLD lookup: the entry in effect at item-local {@code timeMs} — the
     * latest entry with {@code entry.timeMs <= timeMs} — or null before the first
     * entry (caller decides the pre-first behavior; the resolver shows the first
     * entry's content, matching "what you placed is what you see").
     */
    @Nullable
    public Key atOrBefore(long timeMs) {
        Key best = null;
        for (Key k : keys) {
            if (k.timeMs > timeMs) break;
            best = k;
        }
        return best;
    }

    /** The entry AFTER the one in effect at {@code timeMs}, or null (for hold-span math). */
    @Nullable
    public Key after(long timeMs) {
        for (Key k : keys) {
            if (k.timeMs > timeMs) return k;
        }
        return null;
    }

    /** First entry or null. */
    @Nullable
    public Key first() { return keys.isEmpty() ? null : keys.get(0); }

    /** Defensive re-sort for entries mutated in place (e.g. a dope-sheet drag). */
    public void resort() {
        Collections.sort(keys, (a, b) -> Long.compare(a.timeMs, b.timeMs));
    }
}
