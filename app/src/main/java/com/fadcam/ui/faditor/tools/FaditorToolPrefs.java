package com.fadcam.ui.faditor.tools;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Persistence + ordering logic for the Stage 3 customizable tools carousel.
 *
 * <p>Stored in {@link SharedPreferencesManager} as compact JSON strings:
 * <ul>
 *   <li>{@link Constants#PREF_FADITOR_TOOL_ORDER} — JSON array of tool ids in
 *       the user's manual (dragged) order.</li>
 *   <li>{@link Constants#PREF_FADITOR_TOOL_PINS} — JSON array of pinned tool
 *       ids (always sorted to the front, in pin order).</li>
 *   <li>{@link Constants#PREF_FADITOR_TOOL_ORDER_MODE} — {@code "manual"} or
 *       {@code "recent"}.</li>
 *   <li>{@link Constants#PREF_FADITOR_TOOL_RECENCY} — JSON object mapping tool
 *       id → last-used epoch millis, for RECENT ordering.</li>
 * </ul>
 *
 * <p><b>Forward-compatibility:</b> {@link #resolveOrder} always starts from the
 * full canonical tool list. Any tool id NOT present in stored order (e.g. a new
 * tool added in a future build) is appended at the end in its canonical
 * position, so new tools never vanish and old saved orders never break.</p>
 */
public final class FaditorToolPrefs {

    public static final String MODE_MANUAL = "manual";
    public static final String MODE_RECENT = "recent";

    private final SharedPreferencesManager prefs;

    public FaditorToolPrefs(@NonNull Context ctx) {
        this.prefs = SharedPreferencesManager.getInstance(ctx.getApplicationContext());
    }

    // ── Order mode ────────────────────────────────────────────────────

    @NonNull
    public String getOrderMode() {
        String m = prefs.sharedPreferences.getString(
                Constants.PREF_FADITOR_TOOL_ORDER_MODE, MODE_MANUAL);
        return MODE_RECENT.equals(m) ? MODE_RECENT : MODE_MANUAL;
    }

    public boolean isRecentMode() {
        return MODE_RECENT.equals(getOrderMode());
    }

    public void setOrderMode(@NonNull String mode) {
        prefs.sharedPreferences.edit()
                .putString(Constants.PREF_FADITOR_TOOL_ORDER_MODE,
                        MODE_RECENT.equals(mode) ? MODE_RECENT : MODE_MANUAL)
                .apply();
    }

    // ── Manual order ──────────────────────────────────────────────────

    @NonNull
    public List<String> getManualOrder() {
        return readJsonArray(Constants.PREF_FADITOR_TOOL_ORDER);
    }

    public void setManualOrder(@NonNull List<String> ids) {
        writeJsonArray(Constants.PREF_FADITOR_TOOL_ORDER, ids);
    }

    // ── Pins ──────────────────────────────────────────────────────────

    @NonNull
    public List<String> getPins() {
        return readJsonArray(Constants.PREF_FADITOR_TOOL_PINS);
    }

    public void setPins(@NonNull List<String> ids) {
        writeJsonArray(Constants.PREF_FADITOR_TOOL_PINS, ids);
    }

    public boolean isPinned(@NonNull String id) {
        return getPins().contains(id);
    }

    /** Toggles pin state for a tool. Returns the new pinned state. */
    public boolean togglePin(@NonNull String id) {
        List<String> pins = getPins();
        boolean nowPinned;
        if (pins.contains(id)) {
            pins.remove(id);
            nowPinned = false;
        } else {
            pins.add(id);
            nowPinned = true;
        }
        setPins(pins);
        return nowPinned;
    }

    // ── Recency ───────────────────────────────────────────────────────

    /** Records that a tool was just used (RECENT ordering timestamp). */
    public void recordUse(@NonNull String id) {
        Map<String, Long> map = getRecency();
        map.put(id, System.currentTimeMillis());
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            try { obj.put(e.getKey(), e.getValue()); } catch (JSONException ignored) {}
        }
        prefs.sharedPreferences.edit()
                .putString(Constants.PREF_FADITOR_TOOL_RECENCY, obj.toString())
                .apply();
    }

    @NonNull
    public Map<String, Long> getRecency() {
        Map<String, Long> map = new HashMap<>();
        String s = prefs.sharedPreferences.getString(Constants.PREF_FADITOR_TOOL_RECENCY, null);
        if (s == null || s.isEmpty()) return map;
        try {
            JSONObject obj = new JSONObject(s);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, obj.optLong(k, 0L));
            }
        } catch (JSONException ignored) {}
        return map;
    }

    // ── Order resolution ──────────────────────────────────────────────

    /**
     * Produces the final displayed tool order from the canonical list and the
     * persisted preferences.
     *
     * <p>Rules: pinned tools first (in pin order); then the rest ordered by
     * either the manual saved order or by recency (most-recent first, then
     * canonical order for never-used tools). Any canonical tool missing from
     * the saved structures is appended in canonical order so nothing is lost.</p>
     */
    @NonNull
    public List<FaditorTool> resolveOrder(@NonNull List<FaditorTool> canonical) {
        Map<String, FaditorTool> byId = new HashMap<>();
        List<String> canonicalIds = new ArrayList<>();
        for (FaditorTool t : canonical) {
            byId.put(t.id, t);
            canonicalIds.add(t.id);
        }

        List<String> pins = getPins();
        LinkedHashSet<String> result = new LinkedHashSet<>();

        // 1) Pinned first (only ids that still exist).
        for (String id : pins) {
            if (byId.containsKey(id)) result.add(id);
        }

        // 2) The body, per mode.
        if (isRecentMode()) {
            Map<String, Long> recency = getRecency();
            List<String> body = new ArrayList<>(canonicalIds);
            body.sort((a, b) -> {
                long ta = recency.getOrDefault(a, 0L);
                long tb = recency.getOrDefault(b, 0L);
                if (ta != tb) return Long.compare(tb, ta); // most recent first
                // Stable tiebreak: canonical order.
                return Integer.compare(canonicalIds.indexOf(a), canonicalIds.indexOf(b));
            });
            result.addAll(body);
        } else {
            // Manual: saved order first (existing ids), then any new canonical
            // tools appended in canonical order.
            for (String id : getManualOrder()) {
                if (byId.containsKey(id)) result.add(id);
            }
            result.addAll(canonicalIds); // LinkedHashSet ignores dups
        }

        // Build the ordered tool list.
        List<FaditorTool> ordered = new ArrayList<>(result.size());
        for (String id : result) {
            FaditorTool t = byId.get(id);
            if (t != null) ordered.add(t);
        }
        return ordered;
    }

    // ── JSON helpers ──────────────────────────────────────────────────

    @NonNull
    private List<String> readJsonArray(@NonNull String key) {
        List<String> out = new ArrayList<>();
        String s = prefs.sharedPreferences.getString(key, null);
        if (s == null || s.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, null);
                if (v != null && !v.isEmpty()) out.add(v);
            }
        } catch (JSONException ignored) {}
        return out;
    }

    private void writeJsonArray(@NonNull String key, @NonNull List<String> ids) {
        JSONArray arr = new JSONArray();
        for (String id : ids) arr.put(id);
        prefs.sharedPreferences.edit().putString(key, arr.toString()).apply();
    }
}
