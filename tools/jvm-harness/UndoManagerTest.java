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

    /** An action that appends {@code onDo} when applied and {@code onUndo} when reversed, so a
     *  test can assert the ORDER several actions ran in, not merely that they ran. */
    static EditAction act(StringBuilder log, String onDo, String onUndo, String desc) {
        return new EditAction() {
            @Override public void execute() { log.append(onDo); }
            @Override public void undo() { log.append(onUndo); }
            @Override public String getDescription() { return desc; }
        };
    }
    static void eqI(String what, int want, int got) { eq(what, "" + want, "" + got); }

    /** The whole "project" is one string. */
    static final class Model { String state; Model(String s) { state = s; } }

    static final class FakeRestorer implements UndoManager.SnapshotRestorer {
        final Model m;
        Runnable pending;            // the newest post; any older one is superseded (a no-op)
        long dueAt;
        int captures = 0, restores = 0;
        FakeRestorer(Model m) { this.m = m; }
        public String captureSnapshot() { captures++; return m.state; }
        public void restoreFromSnapshot(String json) { restores++; m.state = json; }
        public void scheduleBaselineRefresh(Runnable r, long delayMs) {
            pending = r;
            dueAt = SystemClock.NOW + delayMs;
        }
        /** Simulate the looper firing the posted baseline refresh AFTER the handler unwinds,
         *  once its delay (the throttle's trailing edge) has passed. */
        void tick() {
            if (pending == null || SystemClock.NOW < dueAt) return;
            Runnable r = pending; pending = null; r.run();
        }
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

        // ── 1b. Undo PAST the session start, then redo everything (device, 2026-10-14) ────────
        // A history loaded from disk (snapshot-only) with a new in-session edit on top. Undoing
        // both restores a snapshot for the older one, which invalidates every action; the newer
        // edit, already on the redo stack, used to have no snapshot either, so redo dropped it:
        // "Redo skipped an unrestorable entry: Add clip".
        {
            Model m = new Model("S0");
            UndoManager um = new UndoManager();
            FakeRestorer r = new FakeRestorer(m);
            um.setSnapshotRestorer(r);
            um.resetBaseline();
            advance(2000);
            m.state = "S1";
            um.recordAction(new FakeAction(m, "Old edit", "S0", "S1"));
            advance(20); r.tick();
            Model reopened = new Model("S1");
            FakeRestorer[] hr = new FakeRestorer[1];
            UndoManager um2 = restart(um, reopened, hr);      // "Old edit" is snapshot-only now
            advance(2000);
            reopened.state = "S2";
            um2.recordAction(new FakeAction(reopened, "Add clip", "S1", "S2"));
            advance(20); hr[0].tick();
            um2.undo();                                       // Add clip: by action
            eq("1h undo the new edit", "S1", reopened.state);
            um2.undo();                                       // Old edit: by snapshot (swaps the graph)
            eq("1i undo past the session start", "S0", reopened.state);
            eqI("1j both are redoable", 2, um2.getRedoCount());
            eqB("1k redo the old edit", true, um2.redo());
            eq("1l state after redo 1", "S1", reopened.state);
            eqB("1m THE FIX: redo the new edit too", true, um2.redo());
            eq("1n state after redo 2", "S2", reopened.state);
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

            // ── 3k. The burst's after-state is captured LATER, not dropped (Note 9, 2026-10-22).
            // The throttled refresh used to return without rescheduling, so the baseline stayed
            // B1 until some later edit: edit4, however much later, recorded B1 as well and was
            // merged into the burst, so after a restart one press reverted edit2..edit4.
            advance(2000); r.tick();                          // the trailing edge: baseline := B3
            m.state = "B4";
            um.recordAction(new FakeAction(m, "edit4", "B3", "B4"));
            advance(20); r.tick();
            List<HistoryEntry> persist4 = um.getUndoHistoryForPersist();
            eqI("3k edit4 is a step of its own: 3 persisted", 3, persist4.size());
            eq("3l THE FIX: edit4's before is the post-burst state", "B3",
               persist4.get(2).getSnapshotBefore());
            Model reopened4 = new Model("B4");
            UndoManager um4 = restart(um, reopened4, hr);
            um4.undo();
            eq("3m one press after a restart reverts exactly edit4", "B3", reopened4.state);
            eq("3n ...and its row is edit4", "edit4", um4.peekRedoDescription());
        }

        // ── 3o. undo while the trailing capture is pending must not wait behind it ───────
        // The undo leaves the screen at a new state at once; the next edit records against
        // whatever the baseline is then, so the forced refresh supersedes the deferred one.
        {
            Model m = new Model("U0");
            UndoManager um = new UndoManager();
            FakeRestorer r = new FakeRestorer(m);
            um.setSnapshotRestorer(r);
            um.resetBaseline();
            advance(2000);
            m.state = "U1";
            um.recordAction(new FakeAction(m, "a", "U0", "U1"));
            advance(20); r.tick();                            // baseline := U1
            advance(300);
            m.state = "U2";
            um.recordAction(new FakeAction(m, "b", "U1", "U2"));
            advance(20); r.tick();                            // throttled: deferred ~1.2s
            eqB("3o control: a deferred capture is pending", true, r.pending != null);
            Runnable deferred = r.pending;
            um.undo();                                        // b undone by action: U2 -> U1
            advance(20); r.tick();                            // the FORCED refresh runs now
            eqB("3p nothing left pending", true, r.pending == null);
            int captured = r.captures;
            advance(2000); deferred.run();                    // the looper still fires the old post
            eqI("3p2 ...and the superseded deferred post captures nothing", captured, r.captures);
            m.state = "U3";
            um.recordAction(new FakeAction(m, "c", "U1", "U3"));
            eq("3q the edit after the undo records the post-undo state", "U1",
               um.getUndoHistoryForPersist().get(1).getSnapshotBefore());
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
            // The stale action is nulled, so it is NOT replayed on the orphan. Since 2026-10-14 its
            // after-state was kept when it was undone (an older snapshot-only entry sat below it),
            // so redo restores H2 BY VALUE instead of no-op'ing and losing the edit.
            int restoresBefore = r.restores;
            boolean redo2 = um.redo();
            eqB("6e stale action's redo succeeds", true, redo2);
            eq("6f ...back at H2", "H2", m.state);
            eqI("6g ...by snapshot restore, not by replaying the orphaned action", restoresBefore + 1, r.restores);
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

        // ── mergeNextIntoTop: the DISLODGE case (§3A.5b) ─────────────────────────────────
        // Pulling a clip off the spine and dragging it is ONE gesture but TWO recorded mutations
        // (the demote, then the drop). Without the merge it cost two undo presses, and the FIRST
        // press left the clip on a layer at a position the user never chose — a state that existed
        // at no instant during the gesture.
        {
            UndoManager um = new UndoManager();
            StringBuilder log = new StringBuilder();
            um.recordAction(act(log, "D", "d", "demote"));     // the dislodge's own action
            um.mergeNextIntoTop();
            um.recordAction(act(log, "P", "p", "place"));      // the drop
            eqB("merge: the drop did NOT push a second entry", true, um.canUndo());
            log.setLength(0);
            um.undo();
            eq("merge: one press reverses the drop THEN the demote", "pd", log.toString());
            eqB("merge: ONE press emptied the stack — one gesture, one undo", false, um.canUndo());

            // The flag is ONE-SHOT. A second unrelated edit must not be swallowed too.
            UndoManager um2 = new UndoManager();
            um2.recordAction(act(log, "A", "a", "first"));
            um2.mergeNextIntoTop();
            um2.recordAction(act(log, "B", "b", "merged"));
            um2.recordAction(act(log, "C", "c", "separate"));
            um2.undo();
            eqB("merge: is one-shot — the NEXT action still records normally", true, um2.canUndo());

            // Cancelled drag: armed, then disarmed without ever recording. The next unrelated
            // edit must stand alone. This is the leak that made me clear it on ACTION_CANCEL.
            UndoManager um3 = new UndoManager();
            um3.recordAction(act(log, "A", "a", "first"));
            um3.mergeNextIntoTop();
            um3.clearMergeNextIntoTop();                       // drag cancelled, nothing recorded
            um3.recordAction(act(log, "B", "b", "later"));
            um3.undo();
            eqB("merge: a cancelled drag does not swallow the next edit", true, um3.canUndo());

            // Arming with nothing beneath must not throw away the action.
            UndoManager um4 = new UndoManager();
            um4.mergeNextIntoTop();
            um4.recordAction(act(log, "A", "a", "orphan"));
            eqB("merge: with an EMPTY stack it records normally instead of vanishing",
                    true, um4.canUndo());
        }

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
