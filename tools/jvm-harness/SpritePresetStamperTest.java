import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpritePresetStamper;

import java.util.List;

/**
 * JVM harness for SpritePresetStamper (fast-follow A dope-sheet preset chips —
 * pure Java, no Android deps). Run like the other harnesses: compile against the
 * app's built classes + gson + annotation-jvm (see the stamper task recipe).
 *
 * <p>Covers: cycle covers every enabled cell at the right fps-derived spacing;
 * ping-pong reverses without double-hitting either endpoint; hold emits exactly
 * one key at the anchor; disabled cells are skipped; empty/1-cell sheets degrade
 * gracefully (no crash, sensible key counts).</p>
 */
public class SpritePresetStamperTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        cycleCoversAllEnabledAtFpsSpacing();
        cyclePingpongUseSheetFpsStep();
        pingpongReversesNoDoubledEndpoints();
        pingpongTwoCellsNoReturnLeg();
        holdEmitsSingleKeyAtAnchor();
        holdFallsBackWhenCellInvalid();
        disabledCellsSkipped();
        emptySheetDegradesGracefully();
        singleCellDegradesGracefully();
        anchorClampedNonNegative();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** A cols×1 sheet at the given fps; every cell enabled (no explicit Cell metas). */
    static SpriteSheet sheet(int cols, float fps) {
        SpriteSheet s = new SpriteSheet("id", "test", "uri");
        s.setGrid(cols, 1);
        s.setFps(fps);
        return s;
    }

    /** Disable cell {@code index} by adding an explicit disabled Cell meta. */
    static void disable(SpriteSheet s, int index) {
        SpriteSheet.Cell c = new SpriteSheet.Cell(index, "");
        c.enabled = false;
        s.getCells().add(c);
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    static void cycleCoversAllEnabledAtFpsSpacing() {
        SpriteSheet s = sheet(4, 10f); // 10fps → 100ms step
        List<FrameTrack.Key> keys = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.CYCLE_ALL, s, 500, -1);
        check("cycle: one key per enabled cell", keys.size() == 4);
        check("cycle: cells in order", cell(keys, 0) == 0 && cell(keys, 1) == 1
                && cell(keys, 2) == 2 && cell(keys, 3) == 3);
        check("cycle: anchored at start", time(keys, 0) == 500);
        check("cycle: 100ms spacing", time(keys, 1) == 600
                && time(keys, 2) == 700 && time(keys, 3) == 800);
    }

    static void cyclePingpongUseSheetFpsStep() {
        // 8fps → round(1000/8) = 125ms; 0 fps guards to the 8fps fallback.
        SpriteSheet s = sheet(3, 8f);
        List<FrameTrack.Key> keys = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.CYCLE_ALL, s, 0, -1);
        check("fps-step: 125ms @ 8fps", time(keys, 1) == 125 && time(keys, 2) == 250);
        SpriteSheet bad = sheet(2, 8f);
        bad.setFps(0.1f); // setFps clamps to >=0.1; stamper still yields a >=1ms step
        List<FrameTrack.Key> k2 = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.CYCLE_ALL, bad, 0, -1);
        check("fps-step: never zero", k2.size() == 2 && time(k2, 1) > time(k2, 0));
    }

    static void pingpongReversesNoDoubledEndpoints() {
        SpriteSheet s = sheet(4, 10f); // cells 0,1,2,3
        List<FrameTrack.Key> keys = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.PINGPONG, s, 0, -1);
        // Expected sequence: 0,1,2,3,2,1  (endpoints 0 and 3 hit once each).
        check("pingpong: length", keys.size() == 6);
        check("pingpong: forward leg", cell(keys, 0) == 0 && cell(keys, 1) == 1
                && cell(keys, 2) == 2 && cell(keys, 3) == 3);
        check("pingpong: return leg", cell(keys, 4) == 2 && cell(keys, 5) == 1);
        check("pingpong: endpoint 0 once", countCell(keys, 0) == 1);
        check("pingpong: endpoint 3 once", countCell(keys, 3) == 1);
        check("pingpong: monotonic times", monotonic(keys));
    }

    static void pingpongTwoCellsNoReturnLeg() {
        SpriteSheet s = sheet(2, 10f); // cells 0,1 — no interior to reverse
        List<FrameTrack.Key> keys = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.PINGPONG, s, 0, -1);
        check("pingpong-2: two keys only", keys.size() == 2);
        check("pingpong-2: no doubled endpoints",
                cell(keys, 0) == 0 && cell(keys, 1) == 1);
    }

    static void holdEmitsSingleKeyAtAnchor() {
        SpriteSheet s = sheet(5, 10f);
        List<FrameTrack.Key> keys = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.HOLD_CURRENT, s, 700, 3);
        check("hold: exactly one key", keys.size() == 1);
        check("hold: at anchor", time(keys, 0) == 700);
        check("hold: holds current cell", cell(keys, 0) == 3);
    }

    static void holdFallsBackWhenCellInvalid() {
        SpriteSheet s = sheet(4, 10f);
        // -1 (NO_CELL) → first enabled cell.
        List<FrameTrack.Key> neg = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.HOLD_CURRENT, s, 0, -1);
        check("hold: -1 → first enabled", neg.size() == 1 && cell(neg, 0) == 0);
        // Out-of-range cell → first enabled cell too.
        List<FrameTrack.Key> oob = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.HOLD_CURRENT, s, 0, 99);
        check("hold: OOB → first enabled", oob.size() == 1 && cell(oob, 0) == 0);
    }

    static void disabledCellsSkipped() {
        SpriteSheet s = sheet(4, 10f);
        disable(s, 1); // enabled set becomes 0,2,3
        List<FrameTrack.Key> keys = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.CYCLE_ALL, s, 0, -1);
        check("disabled: skipped in cycle", keys.size() == 3
                && cell(keys, 0) == 0 && cell(keys, 1) == 2 && cell(keys, 2) == 3);
        check("disabled: cell 1 absent", countCell(keys, 1) == 0);
        // Hold's fallback (first ENABLED) skips the disabled leading cell too.
        SpriteSheet s2 = sheet(4, 10f);
        disable(s2, 0);
        List<FrameTrack.Key> h = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.HOLD_CURRENT, s2, 0, -1);
        check("disabled: hold fallback skips cell 0", h.size() == 1 && cell(h, 0) == 1);
    }

    static void emptySheetDegradesGracefully() {
        // Every cell disabled → no enabled cells at all.
        SpriteSheet s = sheet(2, 10f);
        disable(s, 0);
        disable(s, 1);
        check("empty: cycle empty", SpritePresetStamper.generate(
                SpritePresetStamper.Kind.CYCLE_ALL, s, 0, -1).isEmpty());
        check("empty: pingpong empty", SpritePresetStamper.generate(
                SpritePresetStamper.Kind.PINGPONG, s, 0, -1).isEmpty());
        // Hold still yields one key (falls back to cell 0) — never crashes.
        List<FrameTrack.Key> h = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.HOLD_CURRENT, s, 0, -1);
        check("empty: hold degrades to one key", h.size() == 1);
    }

    static void singleCellDegradesGracefully() {
        SpriteSheet s = sheet(1, 10f);
        check("single: cycle one key", SpritePresetStamper.generate(
                SpritePresetStamper.Kind.CYCLE_ALL, s, 0, -1).size() == 1);
        // One-cell pingpong has no interior return leg — a single key, no dupes.
        List<FrameTrack.Key> pp = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.PINGPONG, s, 0, -1);
        check("single: pingpong one key", pp.size() == 1 && cell(pp, 0) == 0);
        check("single: hold one key", SpritePresetStamper.generate(
                SpritePresetStamper.Kind.HOLD_CURRENT, s, 0, 0).size() == 1);
    }

    static void anchorClampedNonNegative() {
        SpriteSheet s = sheet(3, 10f);
        List<FrameTrack.Key> keys = SpritePresetStamper.generate(
                SpritePresetStamper.Kind.CYCLE_ALL, s, -1234, -1);
        check("anchor: negative clamped to 0", time(keys, 0) == 0);
        check("anchor: still spaced from 0", time(keys, 1) == 100);
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    static int cell(List<FrameTrack.Key> keys, int i) { return keys.get(i).cellIndex; }
    static long time(List<FrameTrack.Key> keys, int i) { return keys.get(i).timeMs; }

    static int countCell(List<FrameTrack.Key> keys, int cellIndex) {
        int n = 0;
        for (FrameTrack.Key k : keys) if (k.cellIndex == cellIndex) n++;
        return n;
    }

    static boolean monotonic(List<FrameTrack.Key> keys) {
        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i).timeMs <= keys.get(i - 1).timeMs) return false;
        }
        return true;
    }

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  PASS " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }
}
