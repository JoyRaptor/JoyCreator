package com.fadcam.ui.lobby.arrival;

import android.graphics.Bitmap;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Everything one arrival needs, gathered on the UI thread and handed to the GL stage once. Nothing in
 * here changes after {@link com.fadcam.ui.lobby.arrival.stage.StageView#play}.
 */
public final class Show {

    @NonNull public final Flight flight;
    @NonNull public final Reveal reveal;
    /** Pixels per dp. */
    public final float density;
    /** When the show began, {@link System#nanoTime()}. */
    public final long startNanos;

    /** His home pose (frame 0 of the idle strip) and his tumble out of the button. */
    @NonNull public final Bitmap idle;
    public final int idleCols, idleRows;
    @Nullable public final Bitmap spin;
    public final int spinCols, spinRows, spinCount;
    /** His body inside a cell, as fractions (acts.json "focus"). */
    @NonNull public final RectF focus;
    /** The size of the chat's own Joybot, dp: the picture is fitted the same way so the hand-over is seamless. */
    public final float actorDp;

    /** The screen the chat opened over, and where it sits (dp). Null: no heat haze. */
    @Nullable public Bitmap back;
    @NonNull public final RectF backRect = new RectF();
    /** The chat drawer drawn fully open, at {@code reveal.panel}. */
    @Nullable public Bitmap panelPic;
    /** Frost: how blurred the screen behind the drawer looks (a mip bias for the haze). */
    public float frostBias;

    public Show(@NonNull Flight flight, @NonNull Reveal reveal, float density, long startNanos,
                @NonNull Bitmap idle, int idleCols, int idleRows,
                @Nullable Bitmap spin, int spinCols, int spinRows, int spinCount,
                @NonNull RectF focus, float actorDp) {
        this.flight = flight;
        this.reveal = reveal;
        this.density = density;
        this.startNanos = startNanos;
        this.idle = idle;
        this.idleCols = idleCols;
        this.idleRows = idleRows;
        this.spin = spin;
        this.spinCols = spinCols;
        this.spinRows = spinRows;
        this.spinCount = spinCount;
        this.focus = focus;
        this.actorDp = actorDp;
    }

    /** dp per cell pixel: his BODY fills the actor's box, as {@code JoybotView} draws him. */
    public float cellScale() {
        return cellScale(idle.getWidth() / (float) idleCols, idle.getHeight() / (float) idleRows, focus, actorDp);
    }

    public static float cellScale(float cellW, float cellH, @NonNull RectF focus, float actorDp) {
        return Math.min(actorDp / (cellW * focus.width()), actorDp / (cellH * focus.height()));
    }

    /** From his body centre down to his hover peg, dp. */
    public static float pegUp(float cellW, float cellH, @NonNull RectF focus, float actorDp) {
        return cellH * cellScale(cellW, cellH, focus, actorDp) * (focus.bottom - focus.centerY());
    }

    /** ms since the show began. */
    public long now() { return (System.nanoTime() - startNanos) / 1_000_000L; }

    /** The stage has nothing more to show after this. */
    public long endsAt() { return flight.tSettle() + StageEnd.CALM_MS + StageEnd.FADE_MS; }

    /** When the calm glow after landing fades out. */
    public static final class StageEnd {
        private StageEnd() {}
        public static final long CALM_MS = 1600L;
        public static final long FADE_MS = 600L;
    }
}
