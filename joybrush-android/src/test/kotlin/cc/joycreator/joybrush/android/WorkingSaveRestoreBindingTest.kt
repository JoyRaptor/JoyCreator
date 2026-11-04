package cc.joycreator.joybrush.android

import android.os.Looper
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.core.io.SaveQueue
import cc.joycreator.joybrush.core.io.SaveReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercises Activity restore wiring without onCreate, a canvas, EGL, or owner drawing files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class WorkingSaveRestoreBindingTest {
    private data class Fixture(val activity: JoyBrushActivity, val ticket: Any)

    private fun fixture(): Fixture {
        val activity = Robolectric.buildActivity(JoyBrushActivity::class.java).get()
        val file = File(activity.cacheDir, "working-save-restore-${UUID.randomUUID()}.joybrush")
        check(!file.exists())
        // SaveQueue's stroke gate reads the canvas when finishStartup drains the queue. This
        // unattached surface never creates an EGL context or runs its renderer.
        setActivityField(activity, "canvas", JbCanvasView(activity))
        setActivityField(activity, "openedDrawing", file)
        setActivityField(activity, "workingSaveAllowed", false)
        setActivityField(activity, "loadingDrawing", true)
        val facade = Class.forName("cc.joycreator.joybrush.android.JoyBrushActivityKt")
        val barrier = facade.getDeclaredField("workingSaves")
            .also { it.isAccessible = true }.get(null)
        val ticket = barrier.javaClass.getDeclaredMethod("begin", String::class.java)
            .also { it.isAccessible = true }.invoke(barrier, file.absoluteFile.normalize().path)
        return Fixture(activity, ticket)
    }

    private fun startRestore(fixture: Fixture) {
        JoyBrushActivity::class.java.getDeclaredMethod("restoreWorkingFile")
            .also { it.isAccessible = true }.invoke(fixture.activity)
    }

    private fun complete(fixture: Fixture, problem: String?) {
        fixture.ticket.javaClass.getDeclaredMethod("complete", String::class.java)
            .also { it.isAccessible = true }.invoke(fixture.ticket, problem)
    }

    private fun setActivityField(activity: JoyBrushActivity, name: String, value: Any) {
        JoyBrushActivity::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(activity, value)
    }

    private fun booleanField(activity: JoyBrushActivity, name: String): Boolean =
        JoyBrushActivity::class.java.getDeclaredField(name).also { it.isAccessible = true }.getBoolean(activity)

    private fun drainMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /** Wait for already-submitted disk work without sleeping or blocking the main looper. */
    private fun drainFileIo() {
        val executor = Class.forName("cc.joycreator.joybrush.android.JoyBrushActivityKt")
            .getDeclaredField("fileIo")
            .also { it.isAccessible = true }.get(null) as ExecutorService
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun restoreStaysDisabledUntilPendingOutgoingHandoffSettles() {
        val fixture = fixture()
        startRestore(fixture)
        drainMainLooper()
        assertFalse(booleanField(fixture.activity, "workingSaveAllowed"))
        assertTrue(booleanField(fixture.activity, "loadingDrawing"))

        complete(fixture, null)
        drainMainLooper()
        drainFileIo()
        drainMainLooper()

        // The absent temporary working file reads as empty; startup may then permit a fresh save.
        assertTrue(booleanField(fixture.activity, "workingSaveAllowed"))
        assertFalse(booleanField(fixture.activity, "loadingDrawing"))
    }

    @Test
    fun failedHandoffRefusesRestoreAndKeepsAutosaveDisabled() {
        val fixture = fixture()
        startRestore(fixture)
        drainMainLooper()

        complete(fixture, "archive write failed")
        drainMainLooper()

        assertFalse(booleanField(fixture.activity, "workingSaveAllowed"))
        assertFalse(booleanField(fixture.activity, "loadingDrawing"))
    }

    @Test
    fun destroyedActivityIgnoresHandoffCompletion() {
        val fixture = fixture()
        startRestore(fixture)
        setActivityField(fixture.activity, "destroyed", true)

        complete(fixture, null)
        drainMainLooper()

        assertFalse(booleanField(fixture.activity, "workingSaveAllowed"))
        assertTrue(booleanField(fixture.activity, "loadingDrawing"))
    }

    @Test fun queuedWorkingSaveRejectedByDestroyedActivityReleasesLeavingHandoffAsFailure() {
        val fixture = fixture()
        complete(fixture, null)
        val barrier = Class.forName("cc.joycreator.joybrush.android.JoyBrushActivityKt")
            .getDeclaredField("workingSaves").also { it.isAccessible = true }.get(null) as WorkingSaveBarrier
        val file = JoyBrushActivity::class.java.getDeclaredField("openedDrawing")
            .also { it.isAccessible = true }.get(fixture.activity) as File
        val handoff = JoyBrushActivity::class.java.getDeclaredField("pauseSaveHandoff")
            .also { it.isAccessible = true }.get(fixture.activity) as WorkingSaveHandoff
        handoff.requested(file.absoluteFile.normalize().path)
        @Suppress("UNCHECKED_CAST")
        val queue = JoyBrushActivity::class.java.getDeclaredField("saves")
            .also { it.isAccessible = true }.get(fixture.activity) as SaveQueue<Any>
        val working = Class.forName("cc.joycreator.joybrush.android.JoyBrushActivity\$SaveDest\$Working")
            .getDeclaredField("INSTANCE").also { it.isAccessible = true }.get(null)
        queue.request(SaveReason.IDLE, working)
        var released = false
        var problem: String? = null
        barrier.whenSettled(file.absoluteFile.normalize().path) { released = true; problem = it }
        assertFalse(released)
        setActivityField(fixture.activity, "destroyed", true)
        queue.drain()
        assertTrue(released)
        assertTrue(problem?.contains("closed") == true)
        assertFalse(queue.busy)
    }
}
