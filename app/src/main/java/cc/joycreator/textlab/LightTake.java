package cc.joycreator.textlab;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.transform.mesh.MeshCurves;
import com.fadcam.ui.faditor.transform.mesh.MeshEasingFit;
import com.fadcam.ui.faditor.transform.mesh.MeshPoseTrack;

/**
 * Recording the light with the phone's tilt (the owner's "record live", 2026-11-12): arm, then hold a thumb on the
 * picture; the video plays from the playhead, the phone's tilt at that instant is the zero (the light starts where it
 * was), and the tilt is written as the light's keys until the thumb lifts. The sensor is read only during a take;
 * export reads the keys, never the sensor.
 *
 * <p>Reuses the puppet's take plumbing: a key per sample while recording (shown live), then the take thinned
 * ({@code MeshPoseTrack.simplifyRange}) and fitted with curves, overshoots included ({@link MeshEasingFit}). Keys
 * already there inside the recorded stretch are replaced; outside it they stay. One take = one undo step.</p>
 */
public final class LightTake implements SensorEventListener {

    /** What a take needs from the editor. */
    public interface Host {
        /** The playhead, in the title's own time (ms from its start). */
        long playheadLocalMs();

        boolean isPlaying();

        void play();

        void pause();

        /** Something changed under the preview (redraw it; no undo step). */
        void changed();

        /** The take is done: one undo step from {@code before} to {@code after}. */
        void recorded(@NonNull TitleLook before, @NonNull TitleLook after);
    }

    /** Degrees of tilt that move the light from the middle to an edge (the look book's 30). */
    private static final float FULL_TILT_DEG = 30f;
    private static final long STEP_MS = 33;
    /** How closely the thinned take must follow the hand, in tilt units (-1..1). */
    private static final float TOLERANCE = 0.012f;

    private final SensorManager sm;
    @Nullable private final Sensor sensor;
    private final float[] rot = new float[9], ori = new float[3];

    private TitleLook look;
    private TitleLook before;
    private Host host;
    private MeshPoseTrack dense;
    private boolean recording, startedPlay;
    private float roll0 = Float.NaN, pitch0;
    private long from, to;
    private long lastChanged;

    public LightTake(@NonNull Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm != null ? sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) : null;
        if (s == null && sm != null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensor = s;
    }

    /** False on a phone with no rotation sensor: recording the light cannot be offered. */
    public boolean available() {
        return sensor != null;
    }

    public boolean recording() {
        return recording;
    }

    /** Thumb down: the take starts. */
    public void start(@NonNull TitleLook look, @NonNull Host host) {
        if (recording || sensor == null) return;
        this.look = look;
        this.host = host;
        before = look.copy();
        look.light = TitleLook.TAKE;
        dense = new MeshPoseTrack(2);
        dense.setCurve(MeshCurves.APP_EASING);
        roll0 = Float.NaN;
        from = snap(host.playheadLocalMs());
        to = from;
        recording = true;
        startedPlay = !host.isPlaying();
        if (startedPlay) host.play();
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    /** Thumb up (or the gesture was taken away): the take ends, is thinned, and becomes one undo step. */
    public void stop() {
        if (!recording) return;
        recording = false;
        sm.unregisterListener(this);
        if (startedPlay) host.pause();
        if (dense.size() == 0) {
            look.light = before.light;
            host.changed();
            return;
        }
        // keys outside the take stay; inside it, the take replaces them
        for (String k : new String[]{TitleLook.LIGHT_X, TitleLook.LIGHT_Y}) {
            look.keys.removeProperty(k);
            KeyframeTrack old = before.keys.get(k);
            if (old == null) continue;
            KeyframeTrack t = look.keys.getOrCreate(k);
            for (com.fadcam.ui.faditor.keyframe.Keyframe kf : old.keyframes) {
                if (kf.timeMs < from || kf.timeMs > to) t.put(kf.timeMs, kf.value, kf.easing);
            }
        }
        MeshPoseTrack thin = dense.copy();
        if (dense.size() > 2) {
            thin.simplifyRange(from, to, TOLERANCE);
            MeshEasingFit.fit(thin, dense, new int[]{0, 1}, from, to, MeshCurves.APP_EASING);
        }
        KeyframeTrack x = look.keys.getOrCreate(TitleLook.LIGHT_X), y = look.keys.getOrCreate(TitleLook.LIGHT_Y);
        for (MeshPoseTrack.Pose p : thin.poses()) {
            Easing e = p.easing == null ? Easing.LINEAR : Easing.fromName(p.easing);
            x.put(p.timeMs, p.values[0], e);
            y.put(p.timeMs, p.values[1], e);
        }
        host.recorded(before, look.copy());
    }

    private static long snap(long ms) {
        return Math.max(0, Math.round(ms / (double) STEP_MS) * STEP_MS);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!recording) return;
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        SensorManager.getOrientation(rot, ori);
        float pitch = (float) Math.toDegrees(ori[1]), roll = (float) Math.toDegrees(ori[2]);
        if (Float.isNaN(roll0)) {
            roll0 = roll;
            pitch0 = pitch;
        }
        // right edge down = light to the right; top edge tipped back = light up (the look book's gamma and beta)
        float x = clamp(before.stillX + (roll - roll0) / FULL_TILT_DEG);
        float y = clamp(before.stillY - (pitch - pitch0) / FULL_TILT_DEG);
        long t = snap(host.playheadLocalMs());
        if (t < from) return;
        to = Math.max(to, t);
        dense.put(t, new float[]{x, y}, "LINEAR");
        // shown as it is recorded: the take's keys go straight in
        look.keys.getOrCreate(TitleLook.LIGHT_X).put(t, x, Easing.LINEAR);
        look.keys.getOrCreate(TitleLook.LIGHT_Y).put(t, y, Easing.LINEAR);
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastChanged > 40) {
            lastChanged = now;
            host.changed();
        }
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }
}
