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
 * Persistence + ordering logic for the customizable tools carousel (v2 divider
 * model).
 *
 * <p>Stored in {@link SharedPreferencesManager} as compact JSON strings:
 * <ul>
 *   <li>{@link Constants#PREF_FADITOR_TOOL_PINS} — JSON array of PINNED tool
 *       ids. This is the <b>left / home section</b>, shown left of the divider
 *       in the exact order stored (the user's manual drag order). Always ≥1.</li>
 *   <li>{@link Constants#PREF_FADITOR_TOOL_RECENCY} — JSON object mapping tool
 *       id → last-used epoch millis. Drives the <b>right section</b> (everything
 *       not pinned), sorted most-recent first.</li>
 * </ul>
 *
 * <p><b>v2 divider model.</b> There is no longer a manual-vs-recent mode. The
 * carousel is split by a divider bar: pinned ids (left) keep their stored manual
 * order; unpinned ids (right) are auto-sorted by usage recency. Dragging an icon
 * across the divider pins/unpins it; dropping in the left section sets its manual
 * position. At least one item must stay pinned.</p>
 *
 * <p><b>Migration.</b> Legacy prefs from v1 are honoured: the old
 * {@code PREF_FADITOR_TOOL_PINS} array (if any) becomes the left section as-is;
 * the old {@code PREF_FADITOR_TOOL_ORDER} (manual order) and
 * {@code PREF_FADITOR_TOOL_ORDER_MODE} keys are ignored/removed. If no pins were
 * ever saved, the first canonical tool is seeded as the single pinned item so
 * the ≥1-pinned invariant holds on first run.</p>
 *
 * <p><b>Forward-compatibility:</b> {@link #resolveOrder} always starts from the
 * full canonical tool list. Any tool id NOT present in the pins list is treated
 * as unpinned and lands in the right (usage) section, so new tools added in a
 * future build never vanish and old saved pins never break.</p>
 */
public final class FaditorToolPrefs {

    private final SharedPreferencesManager prefs;
    private boolean migrated;

    public FaditorToolPrefs(@NonNull Context ctx) {
        this.prefs = SharedPreferencesManager.getInstance(ctx.getApplicationContext());
    }

    // ── One-time v1→v2 migration ──────────────────────────────────────

    /**
     * Drops the obsolete v1 order/mode keys. Called lazily on first
     * {@link #resolveOrder}. Pins are preserved verbatim (they become the left
     * section); if none exist, {@code seedIfEmpty} seeds one from canonical.
     */
    private void migrateIfNeeded(@NonNull List<String> canonicalIds) {
        if (migrated) return;
        migrated = true;
        boolean hadLegacy = prefs.sharedPreferences.contains(Constants.PREF_FADITOR_TOOL_ORDER)
                || prefs.sharedPreferences.contains(Constants.PREF_FADITOR_TOOL_ORDER_MODE);
        List<String> pins = getPins();
        // Enforce the ≥1-pinned invariant: seed the first canonical tool that is
        // a normal (non-hidden handled by caller) tool. We just take index 0 of
        // the canonical id list the caller passes (already filtered to visible).
        if (pins.isEmpty() && !canonicalIds.isEmpty()) {
            pins.add(canonicalIds.get(0));
            setPins(pins);
        }
        if (hadLegacy) {
            prefs.sharedPreferences.edit()
                    .remove(Constants.PREF_FADITOR_TOOL_ORDER)
                    .remove(Constants.PREF_FADITOR_TOOL_ORDER_MODE)
                    .apply();
        }
    }

    // ── Pins (the left / home section, in manual order) ───────────────

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

    /** Number of currently pinned (left-section) ids. */
    public int pinnedCount() {
        return getPins().size();
    }

    /**
     * Commits a drag drop. {@code leftSectionIds} is the full ordered list of
     * ids that should now be pinned (left of the divider), in their new manual
     * order. Everything else falls to the right (usage) section automatically.
     * Enforces ≥1 pinned: an empty list is rejected (returns false, no change).
     */
    public boolean commitLeftSection(@NonNull List<String> leftSectionIds) {
        if (leftSectionIds.isEmpty()) return false;
        setPins(new ArrayList<>(leftSectionIds));
        return true;
    }

    // ── Recency ───────────────────────────────────────────────────────

    /** Records that a tool was just used (RECENT ordering timestamp). */
    public void recordUse(@NonNull String id) {
        stampRecency(id, System.currentTimeMillis());
    }

    /**
     * Sets an explicit recency timestamp for a tool. Used when a drag drops an
     * item into the right (usage) section so the dropped left→right order sticks
     * for that gesture (leftmost = largest timestamp).
     */
    public void stampRecency(@NonNull String id, long millis) {
        Map<String, Long> map = getRecency();
        map.put(id, millis);
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
     * Produces the final displayed tool order (v2 divider model): pinned ids
     * first, in their stored manual order (the left/home section), then every
     * remaining canonical tool sorted by usage recency, most-recent first,
     * canonical order as the tiebreak for never-used tools (the right section).
     *
     * <p>Runs the one-time v1→v2 migration and enforces the ≥1-pinned invariant
     * (seeding the first canonical id if no pins were ever saved).</p>
     *
     * <p>The result never includes {@code alwaysHidden} tools in the pin/usage
     * split for sorting purposes, but they ARE still emitted (appended at the
     * end) so the activity can look up their cell ids — they are set GONE by the
     * adapter and never counted for the divider.</p>
     */
    @NonNull
    public List<FaditorTool> resolveOrder(@NonNull List<FaditorTool> canonical) {
        Map<String, FaditorTool> byId = new HashMap<>();
        List<String> visibleIds = new ArrayList<>();
        List<FaditorTool> hidden = new ArrayList<>();
        for (FaditorTool t : canonical) {
            byId.put(t.id, t);
            if (t.alwaysHidden) hidden.add(t);
            else visibleIds.add(t.id);
        }
        migrateIfNeeded(visibleIds);

        List<String> pins = getPins();
        LinkedHashSet<String> result = new LinkedHashSet<>();

        // 1) Pinned first (left section), in stored order — visible ids only.
        for (String id : pins) {
            if (byId.containsKey(id) && !byId.get(id).alwaysHidden) result.add(id);
        }

        // 2) Right section: remaining visible tools sorted by recency desc.
        Map<String, Long> recency = getRecency();
        List<String> body = new ArrayList<>();
        for (String id : visibleIds) {
            if (!result.contains(id)) body.add(id);
        }
        body.sort((a, b) -> {
            long ta = recency.getOrDefault(a, 0L);
            long tb = recency.getOrDefault(b, 0L);
            if (ta != tb) return Long.compare(tb, ta); // most recent first
            return Integer.compare(visibleIds.indexOf(a), visibleIds.indexOf(b));
        });
        result.addAll(body);

        // Build the ordered visible tool list, then append hidden tools last.
        List<FaditorTool> ordered = new ArrayList<>(result.size() + hidden.size());
        for (String id : result) {
            FaditorTool t = byId.get(id);
            if (t != null) ordered.add(t);
        }
        ordered.addAll(hidden);
        return ordered;
    }

    /**
     * The number of leading VISIBLE tools that are pinned, for the current
     * resolved order — i.e. how many cells sit left of the divider. Computed
     * from the same rules as {@link #resolveOrder} so the two agree.
     */
    public int dividerIndex(@NonNull List<FaditorTool> canonical) {
        Map<String, FaditorTool> byId = new HashMap<>();
        List<String> visibleIds = new ArrayList<>();
        for (FaditorTool t : canonical) {
            byId.put(t.id, t);
            if (!t.alwaysHidden) visibleIds.add(t.id);
        }
        migrateIfNeeded(visibleIds);
        int n = 0;
        for (String id : getPins()) {
            if (byId.containsKey(id) && !byId.get(id).alwaysHidden) n++;
        }
        return n;
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
