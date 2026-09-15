import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CornerPin;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.transform.mesh.MeshWarpSpec;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * SPEC ZB — the Clip corner pin + mesh, off device.
 *
 * <p><b>Why this exists.</b> The sheet adds fields no renderer reads yet, so no
 * behavioural test can see them — exactly the shape of bug persist_lint was written
 * for (a live control that is never saved). This test proves the four things the
 * sheet promises instead: the accessors behave, a copy owns its bend, an unwarped
 * clip saves byte-identically, and old / warped / malformed JSON all load with the
 * clip intact.
 *
 * <p>Runs through the REAL {@code ProjectStorage.serializeClipObject} /
 * {@code deserializeClipObject} via reflection (both are private; the harness is
 * allowed to know that). The storage instance is built with
 * {@code Unsafe.allocateInstance} so no {@code Context} is needed — neither method
 * touches it.
 */
public class ClipWarpTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-4f; }

    static Clip clip() {
        Clip c = new Clip(android.net.Uri.parse("file:///sdcard/Movies/take.mp4"), 10_000L);
        c.setOutPointMs(10_000);
        return c;
    }

    static MeshWarpSpec bent() {
        MeshWarpSpec m = MeshWarpSpec.lattice(2);
        m.handles()[3] = 0.5f;
        return m;
    }

    static Object storage() throws Exception {
        // The class literal above forces javac to emit ProjectStorage (reflection alone
        // would not); instantiation still bypasses the Context-taking constructor.
        Class<?> ps = ProjectStorage.class;
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
        return u.allocateInstance(ps);
    }

    static JsonObject ser(Object ps, File dir, Clip c) throws Exception {
        Method m = ps.getClass().getDeclaredMethod(
                "serializeClipObject", File.class, Clip.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(ps, dir, c);
    }

    static Clip de(Object ps, File dir, JsonObject o) throws Exception {
        Method m = ps.getClass().getDeclaredMethod(
                "deserializeClipObject", File.class, JsonObject.class);
        m.setAccessible(true);
        return (Clip) m.invoke(ps, dir, o);
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "clipwarp-test");
        dir.mkdirs();

        // ── 1. Static accessors ──────────────────────────────────────────────
        Clip c = clip();
        check(!c.hasCornerPin(), "fresh clip is undistorted");
        check(!c.hasMesh() && c.getMesh() == null, "fresh clip has no bend");
        check(!c.wantsGl(), "fresh clip wants no GL path");
        c.setCornerPin(CornerPin.TL, CornerPin.DX, 0.25f);
        check(near(c.getCornerPin(CornerPin.TL, CornerPin.DX), 0.25f), "pin set/get one component");
        check(c.hasCornerPin(), "static pin trips the gate");
        c.setCornerPin(CornerPin.TL, CornerPin.DX, 99f);
        check(near(c.getCornerPin(CornerPin.TL, CornerPin.DX), CornerPin.MAX_OFFSET),
                "pin clamps to MAX_OFFSET like the sprite twin");
        check(c.getCornerPin(99, 7) == 0f, "bad corner/axis reads zero, never throws");
        c.setCornerPin(99, 7, 1f); // must not throw
        float[] packed = new float[CornerPin.SIZE];
        c.copyCornerPinInto(packed);
        check(near(packed[0], CornerPin.MAX_OFFSET), "copyCornerPinInto carries statics");
        float[] short8 = new float[3];
        c.copyCornerPinInto(short8); // must not throw
        c.setCornerPin((float[]) null); // must not throw
        c.setCornerPin(new float[3]); // too short: ignored
        check(near(c.getCornerPin(CornerPin.TL, CornerPin.DX), CornerPin.MAX_OFFSET),
                "short/null array set is ignored");
        c.clearCornerPin();
        check(!c.hasCornerPin(), "clearCornerPin restores undistorted");

        // ── 2. Animated evaluation on the clip-local clock ───────────────────
        Clip a = clip();
        a.setCornerPin(new float[]{0.1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});
        float[] out = new float[CornerPin.SIZE];
        a.animatedCornerPin(500, out);
        check(near(out[0], 0.1f), "animated pin falls back to statics with no keys");
        // A pin lane in the overlay envelope (the PiP's set) trips the gate by itself.
        // NOTE: the track name is CornerPin.trackFor ("pinTL.dx", dotted like MaskAnimator's
        // per-slot tracks) — NOT the flat JSON key ("pinTLdx"). The test first wrote the
        // JSON spelling here and all four track checks failed, which is exactly the confusion
        // the two spellings exist to prevent.
        String pinTLdx = CornerPin.trackFor(CornerPin.TL, CornerPin.DX);
        Clip t = clip();
        t.setOverlayTransform(new KeyframeSet());
        t.getOverlayTransform().getOrCreate(pinTLdx).put(0L, 0f, Easing.LINEAR);
        t.getOverlayTransform().getOrCreate(pinTLdx).put(1000L, 1f, Easing.LINEAR);
        check(t.hasCornerPin(), "pin keys trip the gate with flat statics");
        t.animatedCornerPin(500, out);
        check(near(out[0], 0.5f), "pin track interpolates on the clip-local clock");
        // Same for the spine envelope.
        Clip s = clip();
        s.getOrCreateSpineTransform().getOrCreate(pinTLdx).put(0L, 0f, Easing.LINEAR);
        s.getOrCreateSpineTransform().getOrCreate(pinTLdx).put(1000L, 1f, Easing.LINEAR);
        check(s.hasCornerPin(), "spine-envelope pin keys trip the gate too");
        // Overlay envelope wins when both name a track.
        Clip b = clip();
        b.setOverlayTransform(new KeyframeSet());
        b.getOrCreateSpineTransform().getOrCreate(pinTLdx).put(500L, 0.2f, Easing.LINEAR);
        b.getOverlayTransform().getOrCreate(pinTLdx).put(500L, 0.8f, Easing.LINEAR);
        b.animatedCornerPin(500, out);
        check(near(out[0], 0.8f), "overlay envelope wins when both sets name a track");

        // ── 3. Mesh accessors ────────────────────────────────────────────────
        Clip m = clip();
        m.setMesh(bent());
        check(m.hasMesh() && m.wantsGl(), "authored bend trips hasMesh and wantsGl");
        check(m.meshLocalTime(500) == 500 && m.meshLocalTime(-5) == 0,
                "meshLocalTime is the clip-local floor");
        m.setMesh(null);
        check(!m.hasMesh() && !m.wantsGl(), "setMesh(null) clears the bend");
        m.installMeshCurve(); // null-safe: must not throw

        // ── 4. A copy gets its OWN bend ──────────────────────────────────────
        Clip orig = clip();
        orig.setCornerPin(CornerPin.TR, CornerPin.DY, 0.3f);
        orig.setMesh(bent());
        String beforeJson = orig.getMesh().toJson().toString();
        Clip copy = new Clip(orig, "copy-id");
        check(near(copy.getCornerPin(CornerPin.TR, CornerPin.DY), 0.3f),
                "copy carries the pin");
        check(copy.getMesh() != null
                        && copy.getMesh().toJson().toString().equals(beforeJson),
                "copy carries an equal bend");
        copy.setCornerPin(CornerPin.TR, CornerPin.DY, 0.9f);
        copy.getMesh().handles()[3] = 0.9f;
        check(near(orig.getCornerPin(CornerPin.TR, CornerPin.DY), 0.3f),
                "bending the copy's pin leaves the original");
        check(near(orig.getMesh().handles()[3], 0.5f)
                        && orig.getMesh().toJson().toString().equals(beforeJson),
                "bending the copy's mesh leaves the original");
        Clip relinked = orig.relinked(android.net.Uri.parse("file:///sdcard/Movies/moved.mp4"));
        relinked.getMesh().handles()[3] = 0.1f;
        check(near(orig.getMesh().handles()[3], 0.5f),
                "relinked() owns its bend too");

        // ── 5. Undo snapshot carries both, compares by serialised form ───────
        Clip u = clip();
        u.setCornerPin(CornerPin.BL, CornerPin.DX, 0.4f);
        u.setMesh(bent());
        Clip.SpineSnapshot snap = u.snapshotSpineTransform();
        check(snap.matches(u.snapshotSpineTransform()), "identical snapshots match");
        u.setCornerPin(CornerPin.BL, CornerPin.DX, 0.41f);
        check(!snap.matches(u.snapshotSpineTransform()), "pin change breaks the match");
        u.restoreSpineTransform(snap);
        check(near(u.getCornerPin(CornerPin.BL, CornerPin.DX), 0.4f)
                        && snap.matches(u.snapshotSpineTransform()),
                "restore returns the pin");
        u.getMesh().handles()[3] = 0.9f;
        check(!snap.matches(u.snapshotSpineTransform()), "bend change breaks the match");
        u.restoreSpineTransform(snap);
        check(near(u.getMesh().handles()[3], 0.5f), "restore returns the bend");
        // Serialised-form equality: two separately built identical bends match.
        Clip v1 = clip();
        v1.setMesh(bent());
        Clip v2 = clip();
        v2.setMesh(bent());
        check(v1.snapshotSpineTransform().matches(v2.snapshotSpineTransform()),
                "equal bends match by serialised form, not identity");

        // ── 6. Persistence: byte-identical when unwarped ─────────────────────
        Object ps = storage();
        Clip plain = clip();
        JsonObject j1 = ser(ps, dir, plain);
        boolean pinKey = false, meshKey = false;
        for (String k : j1.keySet()) {
            if (k.startsWith("pin")) pinKey = true;
            if (k.equals("mesh")) meshKey = true;
        }
        check(!pinKey && !meshKey, "unwarped clip writes no pin*/mesh keys (not one byte)");
        JsonObject j2 = ser(ps, dir, plain);
        check(j1.toString().equals(j2.toString()), "serialisation is stable across saves");
        Clip rt = de(ps, dir, j1);
        check(!rt.hasCornerPin() && !rt.hasMesh()
                        && rt.getId().equals(plain.getId()),
                "unwarped round-trip is undistorted with the clip intact");

        // ── 7. Persistence: warped clip round-trips ──────────────────────────
        Clip w = clip();
        w.setCornerPin(CornerPin.TL, CornerPin.DX, 0.25f);
        w.setCornerPin(CornerPin.BR, CornerPin.DY, -0.125f);
        w.setMesh(bent());
        JsonObject jw = ser(ps, dir, w);
        check(jw.has("pinTLdx") && jw.has("pinBRdy") && jw.has("mesh"),
                "warped clip writes sparse pin keys plus a mesh object");
        check(!jw.has("pinTLdy") && !jw.has("pinTRdx"),
                "untouched corners write nothing (sparse)");
        Clip wr = de(ps, dir, jw);
        check(near(wr.getCornerPin(CornerPin.TL, CornerPin.DX), 0.25f)
                        && near(wr.getCornerPin(CornerPin.BR, CornerPin.DY), -0.125f)
                        && wr.hasMesh()
                        && near(wr.getMesh().handles()[3], 0.5f),
                "warped round-trip restores pin and bend");

        // ── 8. Tolerant read: old files, warped files on old builds, garbage ─
        String oldJson = "{\"id\":\"old1\",\"sourceUri\":\"file:///sdcard/Movies/take.mp4\","
                + "\"imageClip\":false,\"inPointMs\":0,\"outPointMs\":10000,"
                + "\"sourceDurationMs\":10000,\"speedMultiplier\":1.0,\"audioMuted\":false,"
                + "\"volumeLevel\":1.0,\"rotationDegrees\":0,\"flipHorizontal\":false,"
                + "\"flipVertical\":false,\"cropPreset\":\"none\",\"cropLeft\":0.0,"
                + "\"cropTop\":0.0,\"cropRight\":1.0,\"cropBottom\":1.0}";
        Clip old = de(ps, dir, JsonParser.parseString(oldJson).getAsJsonObject());
        check(old.getId().equals("old1") && !old.hasCornerPin() && !old.hasMesh(),
                "pre-sheet file (no warp keys) loads undistorted, clip intact");
        JsonObject future = JsonParser.parseString(oldJson).getAsJsonObject();
        future.addProperty("futureKey", 123);
        Clip fut = de(ps, dir, future);
        check(fut.getId().equals("old1"), "unknown keys are ignored (the tolerance pattern)");
        JsonObject badMesh = JsonParser.parseString(oldJson).getAsJsonObject();
        badMesh.add("mesh", JsonParser.parseString("{\"k\":\"nope-unknown\"}"));
        Clip bm = de(ps, dir, badMesh);
        check(bm.getId().equals("old1") && !bm.hasMesh(),
                "unknown mesh topology drops the bend, keeps the clip");
        JsonObject strMesh = JsonParser.parseString(oldJson).getAsJsonObject();
        strMesh.addProperty("mesh", "not-an-object");
        Clip sm = de(ps, dir, strMesh);
        check(sm.getId().equals("old1") && !sm.hasMesh(),
                "malformed mesh member drops the bend, keeps the clip");
        JsonObject nullPin = JsonParser.parseString(oldJson).getAsJsonObject();
        nullPin.add("pinTLdx", JsonNull.INSTANCE);
        Clip np = de(ps, dir, nullPin);
        check(np.getId().equals("old1") && !np.hasCornerPin(),
                "explicit-null pin key reads as undistorted");

        System.out.println(fails == 0 ? "ALL CLIPWARP PASS" : fails + " CLIPWARP FAILURES");
        if (fails != 0) System.exit(1);
    }
}
