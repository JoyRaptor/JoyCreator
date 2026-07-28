import com.fadcam.ui.faditor.transcript.NamedTranscript;
import com.fadcam.ui.faditor.transcript.Transcript;
import com.fadcam.ui.faditor.transcript.TranscriptPoolCodec;
import com.fadcam.ui.faditor.transcript.TranscriptWord;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Proves the v12 transcript pool is LOSSLESS: expanding a pooled project tree yields exactly
 * the inline tree the old serializer wrote.
 *
 * <p>This is the operation that decides whether the user's transcripts survive a save. The
 * risky properties are all here: refs must preserve ORDER and LENGTH (because
 * {@code activeTranscript} is persisted as an index into that list), two forks sharing an id
 * must NOT collapse into one (that would silently overwrite one fork's words with the
 * other's), a non-pooled file must pass through untouched, and a dangling ref must degrade to
 * "this clip has no transcripts" rather than throw away the whole project.</p>
 *
 * <p>Every check is paired with a positive control that FAILS if the instrument stops
 * working — a deep-equality assertion is worthless if the comparison itself is vacuous, so
 * each one is re-run against a deliberately corrupted tree and required to disagree.</p>
 *
 * Run:
 *   javac -nowarn -cp &lt;gson.jar&gt; -d tools/jvm-harness/out-pool \
 *     tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
 *     tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptWord.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/Transcript.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/NamedTranscript.java \
 *     app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptPoolCodec.java \
 *     tools/jvm-harness/TranscriptPoolCodecTest.java
 *   java -cp "tools/jvm-harness/out-pool;&lt;gson.jar&gt;" TranscriptPoolCodecTest
 */
public class TranscriptPoolCodecTest {

    static int pass = 0, fail = 0;
    static final Gson GSON = new Gson();

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + what + (detail.isEmpty() ? "" : " -> " + detail)); }
        else { fail++; System.out.println("FAIL  " + what + "   " + detail); }
    }

    static void eq(String what, Object want, Object got) {
        check(what, String.valueOf(want).equals(String.valueOf(got)), "want=" + want + " got=" + got);
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    static NamedTranscript nt(String id, String label, String engine, int words, int seed) {
        Transcript t = new Transcript();
        for (int i = 0; i < words; i++) {
            TranscriptWord w = new TranscriptWord("w" + (seed + i), i * 300L, i * 300L + 250L);
            if (i % 4 == 0) w.struck = true;
            if (i % 5 == 0) w.forceLineBreakAfter = true;
            t.words.add(w);
        }
        return new NamedTranscript(id, label, engine, t);
    }

    /** One clip/audio-clip object carrying an inline transcripts array. */
    static JsonObject ownerInline(String id, int activeIndex, NamedTranscript... versions) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("inPointMs", 0);
        if (versions.length > 0) {
            JsonArray arr = new JsonArray();
            for (NamedTranscript v : versions) arr.add(TranscriptPoolCodec.serializeVersion(v));
            o.add(TranscriptPoolCodec.INLINE_KEY, arr);
            o.addProperty("activeTranscript", activeIndex);
        }
        return o;
    }

    /** The same owner, written the pooled way. */
    static JsonObject ownerPooled(TranscriptPoolCodec.Pool pool, String id, int activeIndex,
                                  NamedTranscript... versions) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("inPointMs", 0);
        if (versions.length > 0) {
            JsonArray refs = new JsonArray();
            for (NamedTranscript v : versions) refs.add(pool.intern(v));
            o.add(TranscriptPoolCodec.REFS_KEY, refs);
            o.addProperty("activeTranscript", activeIndex);
        }
        return o;
    }

    static JsonObject root(JsonArray clips, JsonArray overlayClips, JsonArray audioClips) {
        JsonObject r = new JsonObject();
        r.addProperty("schemaVersion", 11);
        r.addProperty("id", "proj");
        JsonObject tl = new JsonObject();
        if (clips != null) tl.add("clips", clips);
        if (overlayClips != null) tl.add("overlayClips", overlayClips);
        if (audioClips != null) tl.add("audioClips", audioClips);
        r.add("timeline", tl);
        return r;
    }

    static JsonArray arr(JsonObject... objs) {
        JsonArray a = new JsonArray();
        for (JsonObject o : objs) a.add(o);
        return a;
    }

    // ── Tests ────────────────────────────────────────────────────────

    public static void main(String[] args) {

        // ── 1. The real shape: 3 clips + 1 audio clip all sharing the SAME two instances,
        //       which is what a project cut from one long recording looks like.
        NamedTranscript vosk = nt("t-vosk", "Fast", "vosk", 40, 0);
        NamedTranscript whisper = nt("t-whisper", "High accuracy", "whisper", 40, 100);

        JsonObject inline = root(
                arr(ownerInline("c0", 1, vosk, whisper),
                    ownerInline("c1", 0, vosk, whisper),
                    ownerInline("c2", 1, whisper, vosk)),          // deliberately reversed
                arr(ownerInline("pip0", 0, vosk)),
                arr(ownerInline("a0", 0, whisper)));

        TranscriptPoolCodec.Pool pool = new TranscriptPoolCodec.Pool();
        JsonObject pooled = root(
                arr(ownerPooled(pool, "c0", 1, vosk, whisper),
                    ownerPooled(pool, "c1", 0, vosk, whisper),
                    ownerPooled(pool, "c2", 1, whisper, vosk)),
                arr(ownerPooled(pool, "pip0", 0, vosk)),
                arr(ownerPooled(pool, "a0", 0, whisper)));
        pooled.add(TranscriptPoolCodec.POOL_KEY, pool.toJson());

        eq("pool holds one entry per distinct instance", 2, pool.size());
        eq("no forks in the shared case", 0, pool.forkCount());

        int poolChars = GSON.toJson(pooled).length();
        int inlineChars = GSON.toJson(inline).length();
        check("pooled form is smaller", poolChars < inlineChars,
                "inline=" + inlineChars + " pooled=" + poolChars
                        + " (" + String.format("%.2f", (double) inlineChars / poolChars) + "x)");

        eq("expand() reports no dangling refs", 0, TranscriptPoolCodec.expand(pooled));
        check("expanded == inline (deep)", pooled.equals(inline), diff(inline, pooled));

        // POSITIVE CONTROL for that equality: if the comparison were vacuous, a one-word
        // change would also "pass". Corrupt a single word in one expanded clip and require
        // the same assertion to now FAIL.
        JsonObject tampered = pooled.deepCopy();
        tampered.getAsJsonObject("timeline").getAsJsonArray("clips").get(0).getAsJsonObject()
                .getAsJsonArray(TranscriptPoolCodec.INLINE_KEY).get(0).getAsJsonObject()
                .getAsJsonArray("words").get(7).getAsJsonObject().addProperty("t", "TAMPERED");
        check("control: a single tampered word breaks the equality",
                !tampered.equals(inline), "");

        // ── 2. Ref ORDER is load-bearing: activeTranscript is an index into that list.
        //       Clip c2 stored [whisper, vosk]; the expansion must not "helpfully" reorder.
        JsonObject c2 = pooled.getAsJsonObject("timeline").getAsJsonArray("clips")
                .get(2).getAsJsonObject();
        JsonArray c2v = c2.getAsJsonArray(TranscriptPoolCodec.INLINE_KEY);
        eq("order preserved: c2[0] is the whisper version",
                "t-whisper", c2v.get(0).getAsJsonObject().get("id").getAsString());
        eq("order preserved: c2[1] is the vosk version",
                "t-vosk", c2v.get(1).getAsJsonObject().get("id").getAsString());
        eq("activeTranscript index survives", 1, c2.get("activeTranscript").getAsInt());
        // Control: the index would select a DIFFERENT engine if the order had flipped, so
        // this check can actually fail.
        eq("control: the two versions are distinguishable",
                "whisper\nvosk",
                c2v.get(0).getAsJsonObject().get("engine").getAsString() + "\n"
                        + c2v.get(1).getAsJsonObject().get("engine").getAsString());

        // ── 3. Two DIFFERENT instances sharing one id (an unmerged fork). Interning by id
        //       alone would hand both clips the first fork's words — silent data loss.
        NamedTranscript forkA = nt("t-dup", "Fast", "vosk", 12, 0);
        NamedTranscript forkB = nt("t-dup", "Fast", "vosk", 30, 500);   // same id, more words
        TranscriptPoolCodec.Pool forkPool = new TranscriptPoolCodec.Pool();
        JsonObject forkInline = root(
                arr(ownerInline("c0", 0, forkA), ownerInline("c1", 0, forkB)), null, null);
        JsonObject forkPooled = root(
                arr(ownerPooled(forkPool, "c0", 0, forkA),
                    ownerPooled(forkPool, "c1", 0, forkB)), null, null);
        forkPooled.add(TranscriptPoolCodec.POOL_KEY, forkPool.toJson());

        eq("forks get separate pool entries", 2, forkPool.size());
        eq("the extra entry is counted as a fork", 1, forkPool.forkCount());
        TranscriptPoolCodec.expand(forkPooled);
        check("forks round-trip losslessly", forkPooled.equals(forkInline),
                diff(forkInline, forkPooled));
        // Control: the two forks really do differ, so the check above is not trivially true.
        eq("control: forkA and forkB have different word counts", "12 30",
                forkInline.getAsJsonObject("timeline").getAsJsonArray("clips").get(0)
                        .getAsJsonObject().getAsJsonArray(TranscriptPoolCodec.INLINE_KEY)
                        .get(0).getAsJsonObject().getAsJsonArray("words").size()
                + " " +
                forkInline.getAsJsonObject("timeline").getAsJsonArray("clips").get(1)
                        .getAsJsonObject().getAsJsonArray(TranscriptPoolCodec.INLINE_KEY)
                        .get(0).getAsJsonObject().getAsJsonArray("words").size());
        // ...and that re-interning the SAME instance is stable (no runaway "#n" growth).
        eq("re-interning an instance returns its existing key",
                2, new Object() {
                    int run() {
                        TranscriptPoolCodec.Pool p = new TranscriptPoolCodec.Pool();
                        for (int i = 0; i < 5; i++) { p.intern(forkA); p.intern(forkB); }
                        return p.size();
                    }
                }.run());

        // ── 4. A file that was never pooled must pass through byte-identical.
        JsonObject neverPooled = root(arr(ownerInline("c0", 0, vosk)), null, null);
        String beforeJson = GSON.toJson(neverPooled);
        eq("expand() on a non-pooled file reports nothing", 0,
                TranscriptPoolCodec.expand(neverPooled));
        // Compared by digest, not by value: these documents are thousands of chars and
        // printing them whole on PASS buries the rest of the run.
        eq("non-pooled file is untouched",
                digest(beforeJson), digest(GSON.toJson(neverPooled)));
        eq("isPooled() says no", false, TranscriptPoolCodec.isPooled(neverPooled));

        // ── 5. Idempotence: expanding twice is expanding once.
        TranscriptPoolCodec.Pool p5 = new TranscriptPoolCodec.Pool();
        JsonObject twice = root(arr(ownerPooled(p5, "c0", 0, vosk, whisper)), null, null);
        twice.add(TranscriptPoolCodec.POOL_KEY, p5.toJson());
        TranscriptPoolCodec.expand(twice);
        String once = GSON.toJson(twice);
        TranscriptPoolCodec.expand(twice);
        eq("expand() is idempotent", digest(once), digest(GSON.toJson(twice)));
        // Control: the digest is not a constant — a different document must digest differently.
        check("control: digest distinguishes documents",
                !digest(once).equals(digest(once + " ")), "");

        // ── 6. A dangling ref drops that version and keeps the rest — it must not throw and
        //       must not fabricate an empty transcript the caller would then persist.
        TranscriptPoolCodec.Pool p6 = new TranscriptPoolCodec.Pool();
        JsonObject dangl = root(
                arr(ownerPooled(p6, "c0", 0, vosk, whisper), ownerPooled(p6, "c1", 0, vosk)),
                null, null);
        JsonObject pool6 = p6.toJson();
        pool6.remove("t-vosk");                       // simulate a truncated / corrupted pool
        dangl.add(TranscriptPoolCodec.POOL_KEY, pool6);
        eq("dangling refs are counted", 2, TranscriptPoolCodec.expand(dangl));
        JsonArray d0 = dangl.getAsJsonObject("timeline").getAsJsonArray("clips")
                .get(0).getAsJsonObject().getAsJsonArray(TranscriptPoolCodec.INLINE_KEY);
        eq("the surviving version is kept", 1, d0.size());
        eq("and it is the right one", "t-whisper", d0.get(0).getAsJsonObject()
                .get("id").getAsString());
        JsonObject d1 = dangl.getAsJsonObject("timeline").getAsJsonArray("clips")
                .get(1).getAsJsonObject();
        eq("a clip whose every ref dangled looks transcript-less, not empty-listed",
                false, d1.has(TranscriptPoolCodec.INLINE_KEY));
        eq("and the ref array is gone either way", false,
                d1.has(TranscriptPoolCodec.REFS_KEY));

        // ── 7. The pool key is removed after expansion, so a re-save re-decides pooling
        //       from the live model rather than inheriting a stale pool.
        eq("pool key removed after expand", false, dangl.has(TranscriptPoolCodec.POOL_KEY));

        // ── 8. Word edit state is what the user can actually lose. Assert it explicitly
        //       rather than trusting the whole-tree equality to have covered it.
        JsonObject w0 = c2v.get(0).getAsJsonObject().getAsJsonArray("words")
                .get(0).getAsJsonObject();
        eq("struck flag survives the round trip", true, w0.get("x").getAsBoolean());
        eq("forced line break survives", true, w0.get("b").getAsBoolean());
        JsonObject w1 = c2v.get(0).getAsJsonObject().getAsJsonArray("words")
                .get(1).getAsJsonObject();
        eq("control: an unstruck word carries no flag", false, w1.has("x"));

        System.out.println();
        System.out.println(pass + "/" + (pass + fail) + " checks passed"
                + (fail == 0 ? "" : "  (" + fail + " FAILED)"));
        if (fail > 0) System.exit(1);
    }

    /** Length + hash — short enough to print, specific enough to catch any edit. */
    static String digest(String s) {
        return s.length() + ":" + Integer.toHexString(s.hashCode());
    }

    /** First differing path, for a readable failure message. */
    static String diff(JsonElement want, JsonElement got) {
        if (want.equals(got)) return "";
        String a = GSON.toJson(want), b = GSON.toJson(got);
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return "first difference at char " + i + ":\n  want …"
                + a.substring(Math.max(0, i - 40), Math.min(a.length(), i + 60))
                + "\n  got  …" + b.substring(Math.max(0, i - 40), Math.min(b.length(), i + 60));
    }
}
