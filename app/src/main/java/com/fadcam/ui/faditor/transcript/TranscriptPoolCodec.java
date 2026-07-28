package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores each transcript ONCE per project file instead of once per clip that carries it.
 *
 * <p>A transcript belongs to the SOURCE, and every clip cut from that source holds the same
 * {@link NamedTranscript} instance (see {@link TranscriptSharing}). The serializer, though,
 * wrote each clip's list out in full — so a project whose 11 clips are cuts of one long
 * recording stored the same multi-thousand-word transcript 11 times. Measured on the
 * reporter's project: transcripts are 99.2% of the JSON and 71.8% of the whole file is
 * byte-identical duplication.</p>
 *
 * <p>That duplication is not just disk. Cross-session undo snapshots the project to a JSON
 * string on the UI thread, so the same waste is paid per edit burst (~250ms / 5.3M chars on
 * that project) and again in the 22MB {@code undo_history.json} sidecar.</p>
 *
 * <p><b>The split matters.</b> Measured on a JVM at project scale, building the Gson tree is
 * 44% of the serialize cost and stringifying it is 56%. Collapsing duplicates AFTER the tree
 * is built therefore recovers only about half of what is available — which is why the writer
 * side of this class is called from inside the clip serializer (the duplicate word arrays are
 * never built at all) rather than as a post-pass.</p>
 *
 * <h3>Shape</h3>
 * <pre>
 * { "schemaVersion": 12,
 *   "transcriptPool": { "&lt;key&gt;": {"id":…, "label":…, "engine":…, "words":[…]}, … },
 *   "timeline": { "clips": [ { …, "transcriptRefs": ["&lt;key&gt;", …] }, … ] } }
 * </pre>
 *
 * <p>{@code transcriptRefs} preserves the clip's version list ORDER and LENGTH exactly,
 * because {@code activeTranscript} is persisted as an index into that list. Expanding a
 * pooled file therefore restores the identical list the writer saw.</p>
 *
 * <h3>Why the reader is a pre-pass and the writer is not</h3>
 * The clip/audio-clip deserializers are long and correctness-critical; rather than teach them
 * a second input shape, {@link #expand} rewrites a pooled tree back into the inline shape
 * before deserialization begins. Everything downstream is byte-for-byte the code path that
 * has always run. The writer cannot do the mirror trick without giving up the 44%.
 */
public final class TranscriptPoolCodec {

    /** Top-level object holding one entry per distinct transcript. */
    public static final String POOL_KEY = "transcriptPool";
    /** Per-clip array of pool keys, replacing the inline {@code transcripts} array. */
    public static final String REFS_KEY = "transcriptRefs";
    /** The inline (pre-pool) per-clip array. Still written when pooling would not pay. */
    public static final String INLINE_KEY = "transcripts";

    /**
     * Schema version that first understood {@link #POOL_KEY}. A pooled file is NOT readable
     * by an older build — the clips simply have no {@code transcripts} — so, unlike the
     * additive blocks around it, this one has to raise the stamp. Older builds then trip the
     * downgrade guard and open the project read-only instead of silently re-saving it
     * without its transcripts.
     */
    public static final int MIN_SCHEMA_VERSION = 12;

    private TranscriptPoolCodec() { }

    // ── Writer side ──────────────────────────────────────────────────

    /**
     * Accumulates the distinct transcripts of one serialization pass.
     *
     * <p>Interning is by transcript id, but guarded by INSTANCE IDENTITY: two different
     * {@link NamedTranscript} objects can legitimately share an id and differ in content —
     * that is exactly the "fork" shape {@link TranscriptSharing} exists to collapse, and a
     * project can be serialized before that migration has run. Interning those by id alone
     * would hand every clip the first fork's words and silently destroy the others. So a
     * second instance under a known id gets its OWN pool entry under a suffixed key; its
     * inner {@code "id"} field still carries the real id, so expansion is lossless and the
     * sharing migration still recognises it as a fork afterwards.</p>
     */
    public static final class Pool {
        private final JsonObject entries = new JsonObject();
        /** Pool key → the exact instance that produced it (identity, never equality). */
        private final Map<String, NamedTranscript> owners = new HashMap<>();
        private int forkKeys;

        /** Add {@code nt} if absent and return the key a clip should reference. */
        @NonNull
        public String intern(@NonNull NamedTranscript nt) {
            String key = nt.id;
            for (int attempt = 0; ; attempt++) {
                if (attempt > 0) key = nt.id + "#" + attempt;
                NamedTranscript owner = owners.get(key);
                if (owner == null) {
                    owners.put(key, nt);
                    entries.add(key, serializeVersion(nt));
                    if (attempt > 0) forkKeys++;
                    return key;
                }
                if (owner == nt) return key;   // same instance: this is the shared case
                // Same id, different instance — an unmerged fork. Try the next key.
            }
        }

        /** True when nothing was interned (no transcripts anywhere in the project). */
        public boolean isEmpty() { return entries.size() == 0; }

        /** Distinct pool entries written. */
        public int size() { return entries.size(); }

        /** How many entries exist only because two instances shared an id (unmerged forks). */
        public int forkCount() { return forkKeys; }

        @NonNull
        public JsonObject toJson() { return entries; }
    }

    /**
     * Serialize one transcript version — the single authority for this shape, which the clip
     * and audio-clip serializers previously each carried their own copy of.
     */
    @NonNull
    public static JsonObject serializeVersion(@NonNull NamedTranscript nt) {
        JsonObject vj = new JsonObject();
        vj.addProperty("id", nt.id);
        vj.addProperty("label", nt.label);
        vj.addProperty("engine", nt.engine);
        JsonArray wordsArr = new JsonArray();
        for (TranscriptWord w : nt.transcript.words) {
            JsonObject wj = new JsonObject();
            wj.addProperty("t", w.text);
            wj.addProperty("s", w.startMs);
            wj.addProperty("e", w.endMs);
            if (w.struck) wj.addProperty("x", true);
            if (w.forceLineBreakAfter) wj.addProperty("b", true);
            wordsArr.add(wj);
        }
        vj.add("words", wordsArr);
        return vj;
    }

    // ── Reader side ──────────────────────────────────────────────────

    /** True if {@code root} carries a transcript pool and needs {@link #expand} before parsing. */
    public static boolean isPooled(@Nullable JsonObject root) {
        return root != null && root.has(POOL_KEY) && root.get(POOL_KEY).isJsonObject();
    }

    /**
     * Rewrite a pooled project tree into the inline shape the deserializers expect, in place.
     * A tree that was never pooled is left exactly as it was, so this is safe to call
     * unconditionally on every load, and running it twice changes nothing the second time.
     *
     * <p>Expanded entries are SHARED, not copied: the same pool {@link JsonObject} is placed
     * into every clip that references it. Deserialization only ever reads the tree (it builds
     * fresh model objects from it), so sharing costs nothing and avoids re-inflating the very
     * duplication this class removed.</p>
     *
     * <p>A ref naming an absent pool key is DROPPED rather than fabricated or thrown on —
     * matching the per-item load tolerance around it, where one bad record must not cost the
     * user the whole project. It is logged by the caller via the returned count.</p>
     *
     * @return number of refs that could not be resolved (0 on a healthy or non-pooled file)
     */
    public static int expand(@Nullable JsonObject root) {
        if (!isPooled(root)) return 0;
        JsonObject pool = root.getAsJsonObject(POOL_KEY);
        int unresolved = 0;
        JsonElement tlEl = root.get("timeline");
        if (tlEl != null && tlEl.isJsonObject()) {
            JsonObject tl = tlEl.getAsJsonObject();
            unresolved += expandArray(tl.get("clips"), pool);
            unresolved += expandArray(tl.get("overlayClips"), pool);
            unresolved += expandArray(tl.get("audioClips"), pool);
        }
        root.remove(POOL_KEY);
        return unresolved;
    }

    /** Expand every owner object in {@code arrEl}. Returns the unresolved-ref count. */
    private static int expandArray(@Nullable JsonElement arrEl, @NonNull JsonObject pool) {
        if (arrEl == null || !arrEl.isJsonArray()) return 0;
        int unresolved = 0;
        for (JsonElement el : arrEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject owner = el.getAsJsonObject();
            JsonElement refsEl = owner.get(REFS_KEY);
            if (refsEl == null || !refsEl.isJsonArray()) continue;
            JsonArray inline = new JsonArray();
            for (JsonElement refEl : refsEl.getAsJsonArray()) {
                JsonElement entry = pool.get(refEl.getAsString());
                if (entry == null) { unresolved++; continue; }
                inline.add(entry);
            }
            owner.remove(REFS_KEY);
            // Only write the inline key when there is something in it: an owner whose refs all
            // dangled must look like a clip with no transcripts, not one with an empty list
            // (the deserializer keys "has transcripts" off the array's presence).
            if (inline.size() > 0) owner.add(INLINE_KEY, inline);
        }
        return unresolved;
    }
}
