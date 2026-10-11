package cc.joycreator.joybrush.android.chrome

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.core.media.PaperSlope
import cc.joycreator.joybrush.core.media.PaperTilt

/**
 * Tips the paper (M5.6, owner 2026-10-11) from two places and hands the answer to the canvas's media slope, so wet paint
 * runs and drips downhill:
 *
 * - the **tilt pad** ([TiltPadView]): a thumb-driven puck. Whenever the puck is off the middle it wins.
 * - the **phone**: the gravity sensor (the accelerometer, low-passed, on a phone that has no gravity sensor), taken
 *   relative to the pose the phone was held in when it was switched on ([PaperTilt]). Listening only while it is switched
 *   on AND the screen is resumed, so it costs nothing in the pocket or in the lobby.
 *
 * With neither, the paper is level. Main thread throughout (sensor callbacks arrive there).
 */
class PaperTiltController(private val activity: Activity, private val canvas: JbCanvasView) : SensorEventListener {

    private val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer: Sensor? = if (gravitySensor == null) sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
    private val tilt = PaperTilt()
    private val accel = FloatArray(3)
    private var haveAccel = false

    private var padScreen = PaperSlope.FLAT
    private var phoneSlope = PaperSlope.FLAT
    private var sent = PaperSlope.FLAT
    private var listening = false
    private var resumed = false

    /** Whether this phone has any sensor to tip the paper with. */
    val phoneTiltAvailable: Boolean get() = gravitySensor != null || accelerometer != null

    /** The "Tilt with phone" switch. Switching it on takes the pose the phone is in as level. */
    var phoneTilt = false
        set(value) {
            if (field == value) return
            field = value
            sync()
        }

    /** The pad's puck, in screen axes. */
    fun padMoved(slope: PaperSlope) {
        padScreen = slope
        apply()
    }

    /** The page was panned, zoomed or turned: a tilt is fixed to the screen, so its document direction follows the turn. */
    fun viewMoved() {
        if (padScreen != PaperSlope.FLAT) apply()
    }

    fun onResume() { resumed = true; sync() }

    fun onPause() { resumed = false; sync() }

    private fun sync() {
        val want = phoneTilt && resumed && phoneTiltAvailable
        if (want == listening) return
        listening = want
        if (want) {
            tilt.reset()
            haveAccel = false
            sensors.registerListener(this, gravitySensor ?: accelerometer, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensors.unregisterListener(this)
            tilt.reset()
            phoneSlope = PaperSlope.FLAT
            apply()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        var gx = v[0]
        var gy = v[1]
        var gz = v[2]
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Hand shake rides on the raw accelerometer: take out the quick part so only gravity is left.
            if (!haveAccel) { accel[0] = gx; accel[1] = gy; accel[2] = gz; haveAccel = true }
            else {
                accel[0] += ACCEL_SMOOTHING * (gx - accel[0])
                accel[1] += ACCEL_SMOOTHING * (gy - accel[1])
                accel[2] += ACCEL_SMOOTHING * (gz - accel[2])
            }
            gx = accel[0]; gy = accel[1]; gz = accel[2]
        }
        phoneSlope = tilt.feed(gx, gy, gz, displayRotation(), canvas.view.rotation)
        apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** The pad (turned into the page's axes) while the puck is off the middle, else the phone, else level. */
    private fun apply() {
        val pad = if (padScreen == PaperSlope.FLAT) PaperSlope.FLAT else {
            val (x, y) = PaperTilt.screenToDoc(padScreen.x, padScreen.y, canvas.view.rotation)
            PaperSlope(x, y)
        }
        val now = PaperTilt.combine(pad, phoneSlope)
        if (now == sent) return
        sent = now
        canvas.setMediaSlope(now.x, now.y)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) activity.display?.rotation ?: 0 else activity.windowManager.defaultDisplay.rotation

    private companion object {
        /** The raw accelerometer's low-pass, per reading: slow enough to lose a hand's shake, quick enough to follow a tip. */
        const val ACCEL_SMOOTHING = 0.15f
    }
}
