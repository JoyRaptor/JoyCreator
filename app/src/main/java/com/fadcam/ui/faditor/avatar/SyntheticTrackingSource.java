package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A2 (PLAN_AVATAR_STUDIO): a scripted, fully deterministic
 * {@link TrackingSource} — the dep-free stand-in that proves the whole
 * tracking pipeline (bus → smoothing → resolver → FABRIK → warp) on-device
 * while the MediaPipe source stays USER-GATED on its gradle dep. Also the
 * permanent harness fixture and a future "demo mode" candidate.
 *
 * <p>Emits at ~{@value #FPS}fps from its own thread: slow sinusoid head
 * yaw/pitch with a small deterministic jitter (so One-Euro smoothing is
 * visibly doing work), plus a circular {@code pinTarget.<partId>} orbit for
 * each requested part (phase-offset per part). Time advances by EXACT frame
 * steps — a frame counter, not the wall clock — so two runs produce identical
 * frame sequences (bake-replay + harness determinism); the sleep only paces
 * delivery. {@code audioDb} is NaN (no mic): the life package's idle motion
 * engages through the pipeline's silence default.</p>
 */
public final class SyntheticTrackingSource implements TrackingSource {

    private static final int FPS = 30;

    /** Head sweep: gentle, obviously-alive rates (full yaw cycle ~9s). */
    private static final double YAW_HZ = 0.11, PITCH_HZ = 0.07;
    private static final float YAW_AMP = 0.85f, PITCH_AMP = 0.55f;
    /** Deterministic jitter amplitude — big enough to prove smoothing. */
    private static final float JITTER = 0.04f;
    /** Pin-target orbit (view-normalized) around mid-canvas. */
    private static final double ORBIT_HZ = 0.09;
    private static final float ORBIT_CX = 0.5f, ORBIT_CY = 0.55f;
    private static final float ORBIT_RX = 0.22f, ORBIT_RY = 0.16f;

    private final List<String> pinTargetPartIds;
    @NonNull private volatile Thread worker = new Thread(); // never-started sentinel

    /** @param pinTargetPartIds parts to drive with an IK target orbit (may be empty). */
    public SyntheticTrackingSource(@NonNull List<String> pinTargetPartIds) {
        this.pinTargetPartIds = new ArrayList<>(pinTargetPartIds);
    }

    @Override
    public void start(@NonNull FrameListener listener) {
        stop();
        Thread t = new Thread(() -> run(listener), "synthetic-tracker");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    @Override
    public void stop() {
        Thread t = worker;
        t.interrupt();
    }

    private void run(@NonNull FrameListener listener) {
        long frame = 0;
        long lcg = 987654321L;
        Thread self = Thread.currentThread();
        while (!self.isInterrupted() && worker == self) {
            double t = frame / (double) FPS;
            Map<String, Float> p = new HashMap<>();
            // Deterministic jitter: two LCG draws per frame, always consumed
            // in the same order (frame-count-pure).
            lcg = lcg * 0x5DEECE66DL + 0xBL;
            float jYaw = (((lcg >>> 17) & 0x7FFFFFFF) / (float) (1L << 31) * 2f - 1f) * JITTER;
            lcg = lcg * 0x5DEECE66DL + 0xBL;
            float jPitch = (((lcg >>> 17) & 0x7FFFFFFF) / (float) (1L << 31) * 2f - 1f) * JITTER;
            p.put("yaw", (float) Math.sin(2 * Math.PI * YAW_HZ * t) * YAW_AMP + jYaw);
            p.put("pitch", (float) Math.sin(2 * Math.PI * PITCH_HZ * t + 1.3) * PITCH_AMP + jPitch);
            for (int i = 0; i < pinTargetPartIds.size(); i++) {
                String id = pinTargetPartIds.get(i);
                double ph = 2 * Math.PI * ORBIT_HZ * t + i * 2.1;
                p.put(TrackingFrame.pinTargetX(id), ORBIT_CX + ORBIT_RX * (float) Math.cos(ph));
                p.put(TrackingFrame.pinTargetY(id), ORBIT_CY + ORBIT_RY * (float) Math.sin(ph));
            }
            listener.onFrame(new TrackingFrame(t, p, Float.NaN));
            frame++;
            try {
                Thread.sleep(1000 / FPS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
