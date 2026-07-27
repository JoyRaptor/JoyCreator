package com.fadcam.ui.faditor.move;

import java.util.List;

import com.fadcam.ui.faditor.move.ObjectTimeMover.Lane;
import com.fadcam.ui.faditor.move.ObjectTimeMover.Result;
import com.fadcam.ui.faditor.move.ObjectTimeMover.Span;

/**
 * Owns one object-time-scrub gesture (SPEC_OBJECT_TIME_SCRUBBER §2/§3): it turns a stream of
 * shuttle ticks (or a jump-to-time) into resolved positions via {@link ObjectTimeMover}, tells
 * the host to reflect each change live (so the host can animate it), and commits exactly one
 * net move — or nothing — when the gesture ends.
 *
 * <p>Android-free so the tick/commit bookkeeping is provable off-device (see
 * {@code tools/jvm-harness/ObjectTimeScrubSessionTest.java}). The host does everything
 * Android/model-specific: it supplies the lane occupancy at {@link #begin()} and applies the
 * preview/commit.</p>
 *
 * <p><b>Origin-relative resolution.</b> The lane occupancy is snapshotted ONCE at {@code begin()}
 * from the object's HOME lane and the lane above it, and stays fixed for the whole gesture — this
 * is what makes "prefer the origin lane / return to it past the obstacle" fall out for free: every
 * tick re-resolves the same home-lane geometry against the new desired position.</p>
 *
 * <p><b>Lock-direction anchor.</b> {@link ObjectTimeMover}'s flush-lock assumes the position it is
 * given is valid (non-overlapping) on the ORIGIN lane. While the object is relayered ABOVE/NEW its
 * live position overlaps the origin obstacle, so feeding that back would break the lock math. We
 * therefore keep {@code originAnchor} = the last position at which the object was actually ON the
 * origin lane (always valid there) and pass THAT as the lock reference.</p>
 */
public final class ObjectTimeScrubSession {

    /** Everything Android/model-specific the session needs. */
    public interface Host {
        long durationMs();
        long startMs();                       // current committed start when the gesture begins
        List<Span> originLaneSpans();          // OTHER objects on the home lane
        List<Span> aboveLaneSpans();           // OTHER objects on the lane above home, or null
        boolean pushThrough();                 // the toggle
        long breakthroughMs();                 // push-past distance before a relayer fires
        long snapStepMs();                     // 0 = continuous; >0 = snap to this increment

        /** Reflect a change during the gesture (host moves/relayers the object; may animate). */
        void onPreview(long startMs, Lane lane, boolean laneChanged);
        /** Finalize the net move as ONE undo step (host mutates model + records undo). */
        void onCommit(long startMs, Lane lane);
    }

    private final Host host;

    private boolean active;
    private long originStart;                  // start at begin() — the no-op reference
    private long durMs;
    private List<Span> origin;
    private List<Span> above;
    private boolean push;
    private long breakthrough;
    private long snap;

    private long desired;                      // integrated target (pre-snap)
    private long originAnchor;                 // last valid ON-ORIGIN position (lock reference)
    private long lastStart;
    private Lane lastLane = Lane.ORIGIN;

    public ObjectTimeScrubSession(Host host) { this.host = host; }

    public void begin() {
        originStart = host.startMs();
        durMs = Math.max(1, host.durationMs());
        origin = host.originLaneSpans();
        above = host.aboveLaneSpans();
        push = host.pushThrough();
        breakthrough = Math.max(0, host.breakthroughMs());
        snap = Math.max(0, host.snapStepMs());
        desired = originStart;
        originAnchor = originStart;
        lastStart = originStart;
        lastLane = Lane.ORIGIN;
        active = true;
    }

    /** Shuttle tick: advance the desired start by {@code deltaMs} and reflect. */
    public void tick(long deltaMs) {
        if (!active) return;
        desired = Math.max(0, desired + deltaMs);
        resolveAndPreview();
    }

    /** Jump-to-time: set an absolute target; same collision/relayer behaviour applies. */
    public void jumpTo(long targetMs) {
        if (!active) return;
        desired = Math.max(0, targetMs);
        resolveAndPreview();
    }

    private void resolveAndPreview() {
        long d = (snap > 0) ? Math.round((double) desired / snap) * snap : desired;
        Result r = ObjectTimeMover.resolve(durMs, originAnchor, d, push, breakthrough, origin, above);
        if (r.lane == Lane.ORIGIN) originAnchor = r.startMs;   // valid-on-origin by construction
        boolean laneChanged = r.lane != lastLane;
        if (r.startMs != lastStart || laneChanged) {
            lastStart = r.startMs;
            lastLane = r.lane;
            host.onPreview(r.startMs, r.lane, laneChanged);
        }
    }

    /** Gesture finished (shuttle glide settled / jump applied): commit the net move, if any. */
    public void end() {
        if (!active) return;
        active = false;
        if (lastStart == originStart && lastLane == Lane.ORIGIN) return; // nothing changed
        host.onCommit(lastStart, lastLane);
    }

    public boolean isActive() { return active; }
    public long currentStart() { return lastStart; }
    public Lane currentLane() { return lastLane; }
}
