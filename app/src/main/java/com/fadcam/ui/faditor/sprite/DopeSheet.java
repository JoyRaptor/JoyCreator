package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A sprite sheet's animations as a dope sheet: for each one, which cell is on screen for how long
 * (BOT-A31, so Joybot can read a sheet's timing instead of guessing). Pure arithmetic over the sheet
 * and {@link SequenceTiming}; nothing is changed.
 *
 * <p>A frame's WEIGHT is how many ticks it holds; a tick lasts 1 / fps seconds (the preset's own fps,
 * or the sheet's when the preset says 0). A ping-pong cycle runs forward and back without repeating
 * its two end frames ({@link SequenceTiming#pingPongPeriod}); "loop" and "once" run the frames
 * once through.</p>
 */
public final class DopeSheet {
    private DopeSheet() {}

    /** One animation. {@code holds[i]} is the ticks cell {@code frames[i]} stays up. */
    public static final class Row {
        @NonNull public final String id, name, type;
        public final float fps;
        @NonNull public final int[] frames, holds;
        /** Ticks in one pass (a whole ping-pong cycle for a ping-pong). */
        public final long ticks;
        /** Seconds one pass takes at {@link #fps}. */
        public final double seconds;

        Row(@NonNull String id, @NonNull String name, @NonNull String type, float fps,
            @NonNull int[] frames, @NonNull int[] holds, long ticks) {
            this.id = id; this.name = name; this.type = type; this.fps = fps;
            this.frames = frames; this.holds = holds; this.ticks = ticks;
            this.seconds = fps > 0f ? Math.round(ticks / (double) fps * 1000.0) / 1000.0 : 0.0;
        }
    }

    @NonNull
    public static List<Row> of(@NonNull SpriteSheet sheet) {
        List<Row> out = new ArrayList<>();
        for (SpriteSheet.Preset p : sheet.getPresets()) {
            int n = p.frames.size();
            int[] frames = new int[n], holds = new int[n];
            for (int i = 0; i < n; i++) {
                frames[i] = p.frames.get(i);
                holds[i] = SequenceTiming.weightAt(p.weights, i);
            }
            float fps = p.fps > 0f ? p.fps : sheet.getFps();
            boolean pingPong = "pingpong".equals(p.type);
            long ticks = pingPong ? SequenceTiming.pingPongPeriod(p.weights, n)
                    : SequenceTiming.totalWeight(p.weights, n);
            out.add(new Row(p.id, p.name, p.type, fps, frames, holds, ticks));
        }
        return out;
    }
}
