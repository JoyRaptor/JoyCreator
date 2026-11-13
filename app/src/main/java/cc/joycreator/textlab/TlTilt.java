package cc.joycreator.textlab;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The phone's tilt as a light position, -1..1 each way, measured from how the phone was held when it started:
 * right edge down = light to the right, top tipped back = light up, 30 degrees = all the way (the same rule as a
 * recorded take, {@link LightTake}). Only the TextLab room reads it live; Studio never does (owner ruling).
 */
final class TlTilt implements SensorEventListener {

    interface Moved {
        void moved(float x, float y);
    }

    private static final float FULL_TILT_DEG = 30f;
    @Nullable private final SensorManager sm;
    @Nullable private final Sensor sensor;
    private final float[] rot = new float[9], ori = new float[3];
    private float roll0 = Float.NaN, pitch0;
    @Nullable private Moved moved;

    TlTilt(@NonNull Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm != null ? sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) : null;
        if (s == null && sm != null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensor = s;
    }

    boolean available() {
        return sensor != null;
    }

    void start(@NonNull Moved m) {
        moved = m;
        roll0 = Float.NaN;
        if (sm != null && sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() {
        moved = null;
        if (sm != null) sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        Moved m = moved;
        if (m == null) return;
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        SensorManager.getOrientation(rot, ori);
        float pitch = (float) Math.toDegrees(ori[1]), roll = (float) Math.toDegrees(ori[2]);
        if (Float.isNaN(roll0)) {
            roll0 = roll;
            pitch0 = pitch;
        }
        m.moved(clamp((roll - roll0) / FULL_TILT_DEG), clamp(-(pitch - pitch0) / FULL_TILT_DEG));
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}
}
