import android.os.SystemClock;

import com.fadcam.ui.faditor.undo.EditAction;
import com.fadcam.ui.faditor.undo.UndoManager;
import com.fadcam.ui.faditor.undo.UndoManager.HistoryEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Proves the audit-1.6 fix: the DEFERRED ROLLING BASELINE makes a persisted (snapshot-only)
 * entry's snapshotBefore a TRUE pre-state, so undo after a restart reverts the edit instead of
 * silently no-op'ing. Each check is paired with a positive control that would fail if the
 * instrument were blind.
 *
 * The "project" is modelled as a single mutable String (the state). A FakeRestorer serialises /
 * restores it and posts the baseline refresh into a controllable "pending tick" — in production
 * that post runs on the main looper AFTER the edit handler unwinds, which is exactly what makes
 * the captured baseline correct regardless of whether the call site recorded before or after
 * mutating the model.
 *
 * Run:
 *   javac -nowarn -d tools/jvm-harness/out5 \
 *     tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
 *     tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
 *     tools/jvm-harness/stubs/android/os/SystemClock.java \
 *     tools/jvm-harness/stubs/com/fadcam/Log.java \
 *     tools/jvm-harness/stubs/com/fadcam/FLog.java \
 *     app/src/main/java/com/fadcam/ui/faditor/undo/EditAction.java \
 *     app/src/main/java/com/fadcam/ui/faditor/undo/UndoManager.java \
 *     tools/jvm-harness/UndoManagerTest.java
 *   java -cp tools/jvm-harness/out5 UndoManagerTest
 */
public class UndoManagerTest {

    static int pass = 0, fail = 0;

    static void eq(String what, String want, String got) {
        if (want.equals(got)) { pass++; System.out.println("PASS  " + what + " -> " + got); }
        else { fail++; System.out.println("FAIL  " + what + "\n      want=" + want + " got=" + got); }
    }
    static void eqB(String what, boolean want, boolean got) { eq(what, "" + want, "" + got); }
    static void eqI(String what, int want, int got) { eq(what, "" + want, "" + got); }

    /** The whole "project" is one string. */
    static final class Model { String state; Model(String s) { state = s; } }

    static final class FakeRestorer implements UndoManager.SnapshotRestorer {
        final Model m;
        Runnable pending;            // at most one (UndoManager dedups its scheduling)
        int captures = 0, restores = 0;
        FakeRestorer(Model m) { this.m = m; }
        public String captureSnapshot() { captures++; return m.state; }
        public void restoreFromSnapshot(String json) { restores++; m.state = json; }
        public void scheduleBaselineRefresh(Runnable r) { pending = r; }
        /** Simulate the looper firing the posted baseline refresh AFTER the handler unwinds. */
        void tick() { Runnable r = pending; pending = null; if (r != null) r.run(); }
    }

    static final class FakeAction implements EditAction {
        final Model m; final String desc, before, after;
        FakeAction(Model m, String desc, String before, String after) {
            this.m = m; this.desc = desc; this.before = before; this.after = after;
        }
        public void execute() { m.state = after; }
        public void undo() { m.state = before; }
        public String getDescription() { return desc; }
    }

    static long t = 100_000L;
    static void advance(long ms) { t += ms; SystemClock.NOW = t; }

    /** Rebuild a fresh session from persisted history (the "restart"). Returns the new manager. */
    static UndoManager restart(UndoManager old, Model reopened, FakeRestorer[] outRestorer) {
        List<String> descs = new ArrayList<>(), snaps = new ArrayList<>();
        List<Boolean> ais = new ArrayList<>();
        for (HistoryEntry e : old.getUndoHistoryForPersist()) {
            descs.add(e.getDescription());
            snaps.add(e.getSnapshotBefore());
            ais.add(e.isAiOrigin());
        }
        UndoManager um = new UndoManager();
        FakeRestorer r = new FakeRestorer(reopened);
        um.setSnapshotRestorer(r);
        um.loadHistory(descs, snaps, ais);
        um.resetBaseline();          // seeds baseline from the reopened (current) state
        outRestorer[0] = r;
        return um;
    }

    public static void main(String[] args) {
        SystemClock.NOW = t;

        // ── 1. THE REPRO: mutate-before-record edit, restart, undo reverts it ──────────────
        {
            Model m = new Model("S0");
            UndoManager um = new UndoManager();
            FakeRestorer r = new FakeRestorer(m);
            um.setSnapshotRestorer(r);
            um.resetBaseline();                               // baseline = S0
            advance(2000);
            m.state = "S1";                                   // the drag mutates FIRST
            um.recordAction(new FakeAction(m, "Trim", "S0", "S1"));
            advance(20); r.tick();                            // deferred: baseline := S1

            // the recorded entry's before is the PRE-edit state, not the post-edit state
            eq("1a repro: persisted before = pre-edit", "S0",
               um.getUndoHistoryForPersist().get(0).getSnapshotBefore());
            eq("1b positive-control: current state IS post-edit", "S1", m.state);

            // restart → undo
            Model reopened = new Model("S1");                 // project.json saved the post-edit state
            FakeRestorer[] hr = new FakeRestorer[1];
            UndoManager um2 = restart(um, reopened, hr);
            eqI("1c one entry survived to disk", 1, um2.getUndoCount());
            boolean ok = um2.undo();
            eqB("1d undo reported success", true, ok);
            eq("1e THE FIX: undo restored the PRE-edit state", "S0", reopened.state);
            eqI("1f redo now available", 1, um2.getRedoCount());
            um2.redo();
            eq("1g redo re-applies the edit", "S1", reopened.state);
        }

        // ── 2. record-BEFORE-mutate site (rotate-style) is correct via the deferred tick ───
        // The action is recorded while the model is still pre-edit; the mutation lands after.
        // The posted refresh runs after the whole handler, so the NEXT entry's before is right.
        {
            Model m = new Model("R0");
            UndoManager um = new UndoManager();
            FakeRestorer r = new FakeRestorer(m);
            um.setSnapshotRestorer(r);
            um.resetBaseline();                               // baseline = R0
            advance(2000);
            um.recordAction(new FakeAction(m, "Rotate", "R0", "R1")); // RECORD first...
            m.state = "R1";                                   // ...mutate AFTER
            advance(20); r.tick();                            // deferred: baseline := R1 (post-edit)
            advance(2000);
            m.state = "R2";
            um.recordAction(new FakeAction(m, "Flip", "R1", "R2"));   // before must be R1, not R0
            advance(20); r.tick();

            List<HistoryEntry> h = um.getUndoHistoryForPersist();
            eq("2a rotate entry before = R0", "R0", h.get(0).getSnapshotBefore());
            eq("2b flip entry before = R1 (NOT R0 — no over-revert)", "R1", h.get(1).getSnapshotBefore());
            // restart, undo the flip only → back to R1 (exactly one edit)
            Model reopened = new Model("R2");
            FakeRestorer[] hr = new FakeRestorer[1];
            UndoManager um2 = restart(um, reopened, hr);
            um2.undo();
            eq("2c undo flip reverts EXACTLY one edit", "R1", reopened.state);
        }

        // ── 3. throttle-collapse: a BURST inside one window persists as ONE honest step ────
        // edit1 is spaced out, so its post-edit refresh fires and B1 becomes the baseline.
        // edit2 and edit3 land inside the next 1.5s window, so the refresh is throttled and
        // BOTH receive the same baseline object (B1). Persisting both would make one undo
        // restore B1 (correct) and the other a silent no-op — so the burst collapses to one.
        {
            Model m = new Model("B0");
            UndoManager um = new UndoManager();
            FakeRestorer r = new FakeRestorer(m);
            um.setSnapshotRestorer(r);
            um.resetBaseline();                               // baseline = B0
            advance(2000);
            m.state = "B1";
            um.recordAction(new FakeAction(m, "edit1", "B0", "B1"));
            advance(20); r.tick();                            // baseline := B1 (>1.5s since reset)
            advance(300);                                     // < 1.5s — next refresh throttled
            m.state = "B2";
            um.recordAction(new FakeAction(m, "edit2", "B1", "B2"));
            advance(20); r.tick();                            // throttled: baseline stays B1
            advance(300);
            m.state = "B3";
            um.recordAction(new FakeAction(m, "edit3", "B2", "B3"));
            advance(20); r.tick();                            // throttled: baseline stays B1

            eqI("3a in-session all three entries visible", 3, um.getUndoHistory().size());
            // positive control: the burst really did share ONE baseline object (identity),
            // and edit1 did NOT — otherwise the dedup below would be testing nothing.
            eqB("3b control: edit2 and edit3 share one baseline object", true,
                um.getUndoHistory().get(1).getSnapshotBefore()
                    == um.getUndoHistory().get(2).getSnapshotBefore());
            eqB("3c control: edit1's baseline is a DIFFERENT object", false,
                um.getUndoHistory().get(0).getSnapshotBefore()
                    == um.getUndoHistory().get(1).getSnapshotBefore());

            List<HistoryEntry> persist = um.getUndoHistoryForPersist();
            eqI("3d burst collapsed: 3 in-session -> 2 persisted", 2, persist.size());
            eq("3e edit1 survives with its own pre-state", "B0", persist.get(0).getSnapshotBefore());
            eq("3f collapsed entry keeps the NEWEST description", "edit3",
               persist.get(1).getDescription());
            eq("3g its before is the pre-BURST state", "B1", persist.get(1).getSnapshotBefore());

            // restart → one undo reverts the whole burst, a second reverts edit1
            Model reopened = new Model("B3");
            FakeRestorer[] hr = new FakeRestorer[1];
            UndoManager um2 = restart(um, reopened, hr);
            eqI("3h two entries after restart", 2, um2.getUndoCount());
            um2.undo();
            eq("3i one undo reverts the whole burst to pre-burst", "B1", reopened.state);
            um2.undo();
            eq("3j next undo reverts edit1", "B0", reopened.state);
        }

        // ── 4. AI checkpoint: undo after a restart restores the PRE-AI state ───────────────
        {
            Model m = new Model("A0");
            UndoManager um = new UndoManager();
            FakeRestorer r = new FakeRestorer(m);
            um.setSnapshotRestorer(r);
            um.resetBaseline();
            advance(2000);
            // recordAiCheckpoint captures the CURRENT (pre-AI) state as the AI entry's before.
            boolean rec = um.recordAiCheckpoint("Added a title card");
            eqB("4a AI checkpoint recorded", true, rec);
            um.invalidateActionsForProjectSwap();
            m.state = "A1";                                   // the AI's on-disk result
            um.resetBaseline();                               // reseed baseline = A1 (post-AI)

            List<HistoryEntry> h = um.getUndoHistoryForPersist();
            eq("4b AI entry before = pre-AI state", "A0", h.get(0).getSnapshotBefore());
            eqB("4c AI origin flag round-trips", true, h.get(0).isAiOrigin());
            Model reopened = new Model("A1");
            FakeRestorer[] hr = new FakeRestorer[1];
            UndoManager um2 = restart(um, reopened, hr);
            um2.undo();
            eq("4d undo restores the pre-AI state (AI work reverted)", "A0", reopened.state);
        }

        // ── 5. HAZARD 1: empty undo returns false; a restorable entry returns true ─────────
        {
            Model m = new Model("Z1");
            UndoManager um = new UndoManager();
            FakeRestorer r = new FakeRestorer(m);
            um.setSnapshotRestorer(r);
            List<String> d = new ArrayList<>(), s = new ArrayList<>();
            d.add("real"); s.add("Zpre");
            um.loadHistory(d, s, new ArrayList<>());          // one restorable snapshot entry
            um.resetBaseline();
            boolean ok = um.undo();
            eqB("5a control: a restorable snapshot entry returns true", true, ok);
            eq("5b control: it restored", "Zpre", m.state);
            eqB("5c empty undo returns false (does not lie)", false, um.undo());
            eqI("5d empty undo left redo untouched", 1, um.getRedoCount());
        }

        // ── 6. HAZARD 2: a snapshot-path undo invalidates stale actions on BOTH stacks ─────
        // Mixed stack: a loaded snapshot entry (bottom) + an in-session action entry (top).
        // Undo the action (action path), then undo the snapshot entry → the graph is swapped, so
        // the session action now closes over ORPHANED objects. The FakeAction here shares the
        // one test Model, so if the action were NOT nulled a redo would replay it and reach H2
        // — masking the very orphan-mutation the fix prevents. So the correct, safe outcome is:
        // the stale action is nulled, its redo has no snapshot-after, and redo SAFELY no-ops.
        {
            Model m = new Model("H1");                        // reopened state after one loaded edit
            UndoManager um = new UndoManager();
            FakeRestorer r = new FakeRestorer(m);
            um.setSnapshotRestorer(r);
            List<String> d = new ArrayList<>(), s = new ArrayList<>();
            d.add("loaded"); s.add("H0");                     // loaded snapshot-only: before=H0
            um.loadHistory(d, s, new ArrayList<>());
            um.resetBaseline();                               // baseline = H1
            advance(2000);
            m.state = "H2";
            um.recordAction(new FakeAction(m, "session", "H1", "H2")); // action entry on top
            advance(20); r.tick();

            eqI("6a stack has both entries", 2, um.getUndoCount());
            um.undo();                                        // action path: H2 -> H1
            eq("6b action undo (live objects)", "H1", m.state);
            um.undo();                                        // snapshot path: restore H0, swap graph
            eq("6c snapshot undo restored pre-loaded state", "H0", m.state);
            um.redo();                                        // snapshot redo -> H1
            eq("6d snapshot redo -> H1", "H1", m.state);
            boolean redo2 = um.redo();                        // stale action entry (action nulled)
            eqB("6e stale action's redo SAFELY no-ops (not replayed on orphan)", false, redo2);
            eq("6f state is NOT the orphan-replay H2", "H1", m.state);
        }

        // ── amendTopAction: ONE gesture must stay ONE undo press ─────────────────────────
        // Added for the delete-with-anchored-objects case, where part of the gesture's outcome is
        // decided in a dialog AFTER the delete is already recorded.
        {
            UndoManager um = new UndoManager();
            StringBuilder log = new StringBuilder();
            um.recordAction(new EditAction() {
                @Override public void execute() { log.append("A"); }
                @Override public void undo() { log.append("a"); }
                @Override public String getDescription() { return "first"; }
            });
            boolean amended = um.amendTopAction(new EditAction() {
                @Override public void execute() { log.append("B"); }
                @Override public void undo() { log.append("b"); }
                @Override public String getDescription() { return "extra"; }
            });
            eqB("amend: reported success when a top entry exists", true, amended);
            log.setLength(0);
            um.undo();
            eq("amend: undo reverses the EXTRA first, then the original", "ba", log.toString());
            eqB("amend: ONE press emptied the stack, not two", false, um.canUndo());
            log.setLength(0);
            um.redo();
            eq("amend: redo applies original then extra", "AB", log.toString());
            eqB("amend: still amendable after redo", true, um.amendTopAction(new EditAction() {
                @Override public void execute() {}
                @Override public void undo() {}
                @Override public String getDescription() { return "x"; }
            }));

            UndoManager empty = new UndoManager();
            eqB("amend: refuses on an EMPTY stack so the caller can record normally", false,
                    empty.amendTopAction(new EditAction() {
                        @Override public void execute() {}
                        @Override public void undo() {}
                        @Override public String getDescription() { return "x"; }
                    }));
        }

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
