package com.fadcam.ui.lobby.arrival;

import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.ui.lobby.JoybotArrival;

/**
 * How far the chat drawer has opened at each moment, and how its title arrives: pure timing, read by
 * the UI thread (which moves the views) and the GL thread (whose heat haze redraws the drawer as it
 * is at that moment). All in dp, in the chat window's coordinates. Times in ms since the show began.
 *
 * <p>Owner, 15 Nov: "drops like a curtain is really good, especially on the tablet, but ... out of the
 * button, or even opens in his wake are solid options. And I think opens out of the button is
 * actually better for the phone". Titles: with the drawer, out of the button, or letters that land
 * after him.</p>
 */
public final class Reveal {

    @NonNull public final JoybotArrival.Reveal kind;
    @NonNull public final JoybotArrival.Title title;
    /** The hop: everything quicker, the title already in place. */
    public final boolean quick;
    /** The button's centre, dp. */
    public final float bx, by;
    /** The drawer at rest, dp. */
    @NonNull public final RectF panel;
    @NonNull private final Flight flight;
    private final float maxR;
    private final float[] p = new float[3];

    public Reveal(@NonNull JoybotArrival.Reveal kind, @NonNull JoybotArrival.Title title, boolean quick,
                  float bx, float by, @NonNull RectF panel, @NonNull Flight flight) {
        this.kind = kind;
        this.title = quick ? JoybotArrival.Title.DRAWER : title;
        this.quick = quick;
        this.bx = bx;
        this.by = by;
        this.panel = new RectF(panel);
        this.flight = flight;
        float ox = Math.max(bx - panel.left, panel.right - bx), oy = Math.max(by - panel.top, panel.bottom - by);
        maxR = (float) Math.hypot(Math.max(ox, 0f), Math.max(oy, 0f)) + 20f;
    }

    private long openMs() {
        if (quick) return 240L;
        return kind == JoybotArrival.Reveal.BUTTON ? 420L : 360L;
    }

    /** Circle radius at {@code t}, dp, or -1 when the drawer is not opening as a circle (or is fully open). */
    public float radius(long t) {
        if (kind == JoybotArrival.Reveal.CURTAIN) return -1f;
        if (kind == JoybotArrival.Reveal.BUTTON) {
            float rt = Flight.clamp((t - 40f) / openMs(), 0f, 1f);
            return rt >= 1f ? -1f : maxR * Flight.easeOut(rt);
        }
        // in his wake: the circle follows him, then opens the rest of the way as he lands
        long tLand = flight.tLand();
        if (t > tLand + 200L) return -1f;
        float pu = Flight.clamp((t - Flight.T0) / (float) flight.dur, 0f, 1f);
        flight.at(flight.quick ? Flight.easeIO(pu) : pu, p);
        float rad = Math.max((float) Math.hypot(p[0] - bx, p[1] - by) + 70f, 30f);
        if (t > tLand) rad = Flight.lerp(rad, maxR, Flight.smooth((t - tLand) / 200f));
        return rad;
    }

    /** How far above its place the drawer still is at {@code t} (the curtain), dp; 0 when in place. */
    public float shift(long t) {
        if (kind != JoybotArrival.Reveal.CURTAIN) return 0f;
        float rt = Flight.clamp((t - 40f) / openMs(), 0f, 1f);
        return (1f - Flight.drawerCurve(rt)) * -(panel.height() + panel.top);
    }

    /** How open the drawer is, 0..1 (frost blur follows it). */
    public float openness(long t) {
        if (kind == JoybotArrival.Reveal.WAKE) {
            float r = radius(t);
            return r < 0f ? (t < Flight.T0 ? 0f : 1f) : Flight.clamp(r / maxR, 0f, 1f);
        }
        return Flight.clamp((t - 40f) / openMs(), 0f, 1f);
    }

    /** Fully open, title in place, from this time on. */
    public long openedBy() {
        long open = kind == JoybotArrival.Reveal.WAKE ? flight.tLand() + 220L : 40L + openMs();
        if (title == JoybotArrival.Title.LETTERS) open = Math.max(open, letterStart() + 55L * 6 + 220L);
        if (title == JoybotArrival.Title.BUTTON) open = Math.max(open, 420L);
        return open;
    }

    // ── the title ───────────────────────────────────────────────────────────

    /** When the first letter starts to drop ("letters land after him": as he flies under the title). */
    public long letterStart() { return Flight.T0 + Math.round(flight.dur * .2f); }

    /** Letter {@code j}: {alpha, drop in dp (negative = above its place)}. */
    public void letter(long t, int j, @NonNull float[] out) {
        if (title != JoybotArrival.Title.LETTERS) { out[0] = 1f; out[1] = 0f; return; }
        float lt = Flight.clamp((t - letterStart() - j * 55f) / 200f, 0f, 1f);
        out[0] = lt;
        out[1] = (1f - Flight.easeOut(lt)) * -16f;
    }

    /**
     * The whole title out of the button: {alpha, x offset, y offset (dp, from its place), scale}.
     * {@code tx, ty}: the title's resting top-left, dp.
     */
    public void titleOut(long t, float tx, float ty, @NonNull float[] out) {
        if (title != JoybotArrival.Title.BUTTON) { out[0] = 1f; out[1] = 0f; out[2] = 0f; out[3] = 1f; return; }
        float ot = Flight.clamp((t - 60f) / 340f, 0f, 1f), e = Flight.easeOut(ot);
        float fromX = bx - 30f - tx, fromY = by - 50f - ty;
        out[0] = Flight.clamp(ot * 3f, 0f, 1f);
        out[1] = Flight.lerp(fromX, 0f, e);
        out[2] = Flight.lerp(fromY, 0f, e);
        out[3] = Flight.lerp(.3f, 1f, e);
    }
}
