package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.layers.TrackFlags;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * The Studio's lock rule for Joybot, in one place (audit 2026-10-18 J12): a locked object, or
 * anything on a locked row, is not changed by an edit — the editor won't move it either. Every
 * edit tool, typed or older, is checked here before it runs; only {@link #EXEMPT} (the Lock /
 * Unlock action itself) may touch a locked thing, and only when the person asks.
 */
public final class ItemLocks {
    private ItemLocks() { }

    /** The action that locks and unlocks (and hides) — it must reach a locked object. */
    static final String EXEMPT = "set_layer_flags";

    /**
     * Null when every item the arguments name may be changed; else one plain sentence saying
     * which is locked. Any string argument (or list entry) equal to an item's id counts.
     */
    @Nullable
    public static String refusal(@NonNull String tool, @NonNull Timeline tl, @NonNull JSONObject args) {
        if (EXEMPT.equals(tool)) return null;
        Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            Object v = args.opt(keys.next());
            if (v instanceof String) {
                String r = lockedName(tl, (String) v);
                if (r != null) return sentence(r);
            } else if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    Object x = a.opt(i);
                    String r = x instanceof String ? lockedName(tl, (String) x) : null;
                    if (r != null) return sentence(r);
                }
            }
        }
        return null;
    }

    @NonNull
    private static String sentence(@NonNull String what) {
        return what + " is locked, so it wasn't changed. Ask the person whether to unlock it (set_layer_flags"
                + " locked=false) before changing it.";
    }

    /** "“Title” (text)" when {@code id} is a locked item or on a locked row; null otherwise. */
    @Nullable
    static String lockedName(@NonNull Timeline tl, @Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        for (TextOverlayItem t : tl.getTextOverlays()) {
            if (id.equals(t.getId())) return t.isLocked() || rowLocked(tl, t.getLayerId()) ? "“" + shortText(t.getText()) + "” (text)" : null;
        }
        for (SpriteOverlayItem s : tl.getSpriteOverlays()) {
            if (id.equals(s.getId())) return s.isLocked() || rowLocked(tl, s.getLayerId()) ? "That sprite" : null;
        }
        for (AdjustmentLayer a : tl.getAdjustmentLayers()) {
            if (id.equals(a.getId())) return a.isLocked() || rowLocked(tl, a.getLayerId()) ? "That adjustment layer" : null;
        }
        for (WaveformOverlayInstance w : tl.getWaveformOverlays()) {
            if (id.equals(w.getId())) return w.isLocked() ? "That visualizer" : null;
        }
        for (AudioClip a : tl.getAudioClips()) {
            if (id.equals(a.getId())) return a.isLocked() || rowLocked(tl, a.getLayerId()) ? "That sound" : null;
        }
        for (Clip c : tl.getOverlayClips()) {
            if (id.equals(c.getId())) return c.isLockedObject() || rowLocked(tl, c.getLayerId()) ? "That picture-in-picture video" : null;
        }
        return null;
    }

    private static boolean rowLocked(@NonNull Timeline tl, @Nullable String rowId) {
        if (rowId == null) return false;
        TrackFlags f = tl.getTrackFlags(rowId);
        return f != null && f.locked;
    }

    @NonNull
    private static String shortText(@Nullable String s) {
        String t = s == null ? "" : s.replace('\n', ' ').trim();
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }
}
