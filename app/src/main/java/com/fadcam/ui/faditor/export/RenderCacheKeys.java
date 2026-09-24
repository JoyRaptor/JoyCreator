package com.fadcam.ui.faditor.export;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMART RE-EXPORT (export speed Stage 4, 2026-09-24): fingerprints that say whether a rendered
 * part of a long export can be reused.
 *
 * <p>The old resume key was the WHOLE project, so one mask tweak at minute 40 re-rendered all
 * 48 minutes (~35 min on the Note 20). A part's picture depends only on what is inside its own
 * time window, so each part is keyed on a filtered copy of the project: its own clips, the
 * overlays/sprites/audio-clip captions whose time spans touch its window, the transcripts those
 * reference, plus everything this filter does not understand (kept whole — an unknown field
 * can only cost a re-render, never a stale part). The sound pass gets its own key with the
 * picture-only data removed, so a visual edit never re-runs the ~5-9 min sound pass.</p>
 *
 * <p>Pure JSON in, hex out — no Android types, so the rules are testable on the PC.</p>
 */
final class RenderCacheKeys {

    private RenderCacheKeys() { }

    /** Slack around a part's window: transitions, loop tails, fades that bleed across a cut. */
    static final long WINDOW_MARGIN_MS = 2_000L;

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Clip fields that only change the picture or the captions — dropped from the sound key. */
    private static final String[] PICTURE_ONLY_PREFIXES = {
            "caption", "crop", "rotation", "flip", "overlayTransform", "transcript",
            "activeTranscript", "waveform", "label"
    };

    /**
     * Key of one video part: clips {@code [clipStart, clipEnd)} of the timeline, shown during
     * editor/composition times {@code [winStartMs, winEndMs]}.
     *
     * @param extras renderer version, caption styles, flags — anything outside the project file
     *               that changes pixels
     */
    @Nullable
    static String partKey(@NonNull String projectJson, int clipStart, int clipEnd,
                          long winStartMs, long winEndMs, long totalMs, boolean lastPart,
                          @NonNull String extras) {
        try {
            JSONObject root = new JSONObject(projectJson);
            stripVolatile(root);
            long lo = winStartMs - WINDOW_MARGIN_MS;
            long hi = winEndMs + WINDOW_MARGIN_MS;
            Set<String> dropped = new HashSet<>();
            JSONObject tl = root.optJSONObject("timeline");
            boolean runsToEnd = lastPart;
            if (tl != null) {
                JSONArray clips = tl.optJSONArray("clips");
                if (clips != null) {
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < clips.length(); i++) {
                        JSONObject c = clips.optJSONObject(i);
                        if (i >= clipStart && i < clipEnd) {
                            kept.put(clips.get(i));
                        } else if (c != null && c.has("id")) {
                            dropped.add(c.optString("id"));
                        }
                    }
                    tl.put("clips", kept);
                }
                runsToEnd |= filterTimed(tl, lo, hi, totalMs, dropped);
            }
            dropReferences(root, dropped);
            keepReferencedTranscripts(root);
            if (tl != null) stripSoundOnlyUnlessDrawn(tl);
            // The project length only reaches the picture through items that run "to the end"
            // (their motion span is measured against it) and through the last part's tail.
            String total = runsToEnd ? String.valueOf(totalMs) : "-";
            return sha256(normalizeIds(canonical(root)) + "|clips=" + clipStart + ".." + clipEnd
                    + "|win=" + winStartMs + ".." + winEndMs + "|total=" + total
                    + "|files=" + fileStamps(root) + "|" + extras);
        } catch (Exception e) {
            return null;
        }
    }

    /** Key of the sound pass: the whole project minus picture-only data. */
    @Nullable
    static String audioKey(@NonNull String projectJson, @NonNull String extras) {
        try {
            JSONObject root = new JSONObject(projectJson);
            stripVolatile(root);
            root.remove("transcriptPool");
            root.remove("canvasPreset");
            JSONObject tl = root.optJSONObject("timeline");
            if (tl != null) {
                Set<String> visual = new HashSet<>();
                JSONArray overlays = tl.optJSONArray("textOverlays");
                if (overlays != null) {
                    for (int i = 0; i < overlays.length(); i++) {
                        JSONObject o = overlays.optJSONObject(i);
                        if (o != null && !mentionsSound(o)) visual.add(o.optString("id"));
                    }
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < overlays.length(); i++) {
                        JSONObject o = overlays.optJSONObject(i);
                        if (o == null || !visual.contains(o.optString("id"))) kept.put(overlays.get(i));
                    }
                    tl.put("textOverlays", kept);
                }
                for (String k : new String[]{"clips", "audioClips"}) {
                    JSONArray arr = tl.optJSONArray(k);
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c != null) removePictureOnly(c);
                    }
                }
                dropReferences(root, visual);
            }
            JSONObject settings = root.optJSONObject("exportSettings");
            if (settings != null) settings.remove("resolution");
            return sha256(normalizeIds(canonical(root)) + "|files=" + fileStamps(root)
                    + "|" + extras);
        } catch (Exception e) {
            return null;
        }
    }

    // ── filters ─────────────────────────────────────────────────────────────────────────────

    private static void stripVolatile(@NonNull JSONObject root) {
        root.remove("lastModified");
        root.remove("createdAt");
        root.remove("name");
        root.remove("assetDirHistory");
        root.remove("pinnedAssetDir");
    }

    /**
     * In each array directly under the timeline (overlays, sprites, waveforms, audio clips,
     * crossfades, adjustment layers): drop timed elements whose span misses [lo, hi]. Timed =
     * startMs/endMs, or offsetMs + in/out (audio clips). Only the TOP level is filtered: a
     * kept item's insides (keyframes, caption bindings) hash whole, whatever their clocks.
     * Returns true when a kept element runs to the project's end.
     */
    private static boolean filterTimed(@NonNull JSONObject tl, long lo, long hi, long totalMs,
                                       @NonNull Set<String> dropped) throws Exception {
        boolean runsToEnd = false;
        for (String k : keysOf(tl)) {
            if ("clips".equals(k)) continue;   // cut by index, above
            Object v = tl.get(k);
            if (!(v instanceof JSONArray)) continue;
            JSONArray arr = (JSONArray) v;
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                Object e = arr.get(i);
                long[] span = e instanceof JSONObject ? spanOf((JSONObject) e) : null;
                if (span != null && (span[1] < lo || span[0] > hi)) {
                    String id = ((JSONObject) e).optString("id", "");
                    if (!id.isEmpty()) dropped.add(id);
                    continue;
                }
                if (span != null && span[1] >= totalMs) runsToEnd = true;
                kept.put(e);
            }
            tl.put(k, kept);
        }
        return runsToEnd;
    }

    /**
     * A voice-over spans every part of a lecture, so its loudness knobs would re-render the
     * whole picture for a sound-only tweak. They only reach pixels through a waveform
     * visualizer; with none in the project, they leave the part keys (the sound key has them).
     */
    private static void stripSoundOnlyUnlessDrawn(@NonNull JSONObject tl) {
        JSONArray wf = tl.optJSONArray("waveformOverlays");
        if (wf != null && wf.length() > 0) return;
        JSONArray acs = tl.optJSONArray("audioClips");
        if (acs == null) return;
        for (int i = 0; i < acs.length(); i++) {
            JSONObject ac = acs.optJSONObject(i);
            if (ac == null) continue;
            for (String k : keysOf(ac)) {
                String lk = k.toLowerCase(Locale.US);
                if (lk.equals("volumelevel") || lk.equals("pan") || lk.contains("gain")
                        || lk.contains("duck") || lk.contains("loud") || lk.startsWith("eq")
                        || lk.startsWith("fx")) {
                    ac.remove(k);
                }
            }
        }
    }

    /** [start, end] of a timed element, or null when the element carries no time span. */
    @Nullable
    private static long[] spanOf(@NonNull JSONObject e) {
        if (e.has("startMs") && e.has("endMs")) {
            long s = e.optLong("startMs", 0L);
            long end = e.optLong("endMs", Long.MAX_VALUE);
            if (end <= s) end = Long.MAX_VALUE;   // the model's "visible to the end"
            return new long[]{s, end};
        }
        if (e.has("offsetMs") && e.has("inPointMs") && e.has("outPointMs")) {
            long off = e.optLong("offsetMs", 0L);
            long len = Math.max(0L, e.optLong("outPointMs", 0L) - e.optLong("inPointMs", 0L));
            double speed = e.optDouble("speedMultiplier", 1.0);
            if (speed > 0.01 && speed < 1.0) len = (long) Math.ceil(len / speed);
            return new long[]{off, off + len};
        }
        return null;
    }

    /** Remove, anywhere in the tree, lane items / link members / strings naming dropped ids. */
    private static void dropReferences(@NonNull Object node, @NonNull Set<String> dropped)
            throws Exception {
        if (dropped.isEmpty()) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : keysOf(o)) {
                Object v = o.get(k);
                if (v instanceof JSONArray) {
                    JSONArray arr = (JSONArray) v;
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < arr.length(); i++) {
                        Object e = arr.get(i);
                        if (e instanceof String && dropped.contains(e)) continue;
                        if (e instanceof JSONObject) {
                            JSONObject je = (JSONObject) e;
                            if (dropped.contains(je.optString("payloadId", "\u0000"))
                                    || dropped.contains(je.optString("id", "\u0000"))) continue;
                        }
                        dropReferences(e, dropped);
                        kept.put(e);
                    }
                    o.put(k, kept);
                } else if (v instanceof JSONObject) {
                    dropReferences(v, dropped);
                }
            }
        }
    }

    /** The transcript pool shrinks to the transcripts something still in the tree names. */
    private static void keepReferencedTranscripts(@NonNull JSONObject root) throws Exception {
        JSONObject pool = root.optJSONObject("transcriptPool");
        if (pool == null) return;
        root.remove("transcriptPool");
        Set<String> strings = new HashSet<>();
        collectStrings(root, strings);
        JSONObject kept = new JSONObject();
        for (String k : keysOf(pool)) {
            if (strings.contains(k)) kept.put(k, pool.get(k));
        }
        root.put("transcriptPool", kept);
    }

    private static boolean mentionsSound(@NonNull JSONObject o) {
        for (String k : keysOf(o)) {
            String lk = k.toLowerCase(Locale.US);
            if (lk.contains("audio") || lk.contains("volume") || lk.contains("video")
                    || lk.contains("mute") || lk.contains("sound")) return true;
        }
        return false;
    }

    private static void removePictureOnly(@NonNull JSONObject c) {
        for (String k : keysOf(c)) {
            for (String p : PICTURE_ONLY_PREFIXES) {
                if (k.startsWith(p)) { c.remove(k); break; }
            }
        }
    }

    // ── canonical form ─────────────────────────────────────────────────────────────────────

    private static void collectStrings(@NonNull Object node, @NonNull Set<String> out)
            throws Exception {
        if (node instanceof String) {
            out.add((String) node);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : keysOf(o)) collectStrings(o.get(k), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectStrings(a.get(i), out);
        }
    }

    /** Local files the tree points at, with size + mtime: a replaced picture re-renders. */
    @NonNull
    private static String fileStamps(@NonNull JSONObject root) throws Exception {
        Set<String> strings = new HashSet<>();
        collectStrings(root, strings);
        List<String> paths = new ArrayList<>();
        for (String s : strings) {
            String p = s.startsWith("file://") ? s.substring(7) : s;
            if (p.startsWith("/") && p.length() < 1024) paths.add(p);
        }
        Collections.sort(paths);
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            File f = new File(p);
            if (f.isFile()) sb.append(p).append(':').append(f.length()).append(':')
                    .append(f.lastModified()).append(';');
        }
        return sb.toString();
    }

    /** Sorted-key JSON, so the same content always serialises the same. */
    @NonNull
    static String canonical(@NonNull Object node) throws Exception {
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    private static void writeCanonical(Object node, StringBuilder sb) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            List<String> keys = keysOf(o);
            Collections.sort(keys);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(JSONObject.quote(keys.get(i))).append(':');
                writeCanonical(o.get(keys.get(i)), sb);
            }
            sb.append('}');
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            sb.append('[');
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(a.get(i), sb);
            }
            sb.append(']');
        } else if (node instanceof String) {
            sb.append(JSONObject.quote((String) node));
        } else if (node instanceof Number) {
            // 1, 1L and 1.0 are the same value: JSON writers disagree on which they print.
            double d = ((Number) node).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0e15) {
                sb.append((long) d);
            } else if (node instanceof Long || node instanceof java.math.BigInteger) {
                sb.append(node);   // beyond double precision (Long.MAX_VALUE "to the end")
            } else {
                sb.append(Double.toString(d));
            }
        } else {
            sb.append(String.valueOf(node));
        }
    }

    /**
     * Audio clip ids are re-minted on every load, so identities hash by ORDER OF FIRST
     * APPEARANCE — references still count, the random labels do not.
     */
    @NonNull
    static String normalizeIds(@NonNull String json) {
        Matcher m = UUID.matcher(json);
        Map<String, Integer> ordinals = new HashMap<>();
        StringBuffer out = new StringBuffer(json.length());
        while (m.find()) {
            String id = m.group().toLowerCase(Locale.US);
            Integer ord = ordinals.get(id);
            if (ord == null) { ord = ordinals.size(); ordinals.put(id, ord); }
            m.appendReplacement(out, "#" + ord);
        }
        m.appendTail(out);
        return out.toString();
    }

    @NonNull
    private static List<String> keysOf(@NonNull JSONObject o) {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) keys.add(it.next());
        return keys;
    }

    @NonNull
    static String sha256(@NonNull String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
