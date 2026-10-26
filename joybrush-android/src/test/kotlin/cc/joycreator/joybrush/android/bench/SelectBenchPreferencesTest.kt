package cc.joycreator.joybrush.android.bench

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectBenchPreferencesTest {
    private fun preferences() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("select-bench-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test fun restoreReturnsOnlyRequestedKeysToTheirCapturedTypedValues() {
        val prefs = preferences()
        prefs.edit()
            .putBoolean("flag", false)
            .putString("tools", "{\"active\":\"brush\",\"size\":12.0}")
            .putString("unrelated", "before")
            .commit()

        val saved = SelectBenchPreferences(prefs, setOf("absent", "flag", "tools"))
        prefs.edit()
            .putBoolean("flag", true)
            .putString("tools", "changed")
            .putString("absent", "added after capture")
            .putString("unrelated", "changed after capture")
            .putBoolean("new-unrelated", true)
            .commit()

        saved.restore()

        assertFalse(prefs.getBoolean("flag", true))
        assertEquals("{\"active\":\"brush\",\"size\":12.0}", prefs.getString("tools", null))
        assertFalse("captured absence is restored by removing the key", prefs.contains("absent"))
        assertEquals("changed after capture", prefs.getString("unrelated", null))
        assertTrue(prefs.getBoolean("new-unrelated", false))
    }

    @Test fun stringSetsAreDetachedAtCaptureAndRestoredAsSets() {
        val prefs = preferences()
        prefs.edit().putStringSet("set", mutableSetOf("ink", "brush")).commit()
        val saved = SelectBenchPreferences(prefs, setOf("set"))

        // Replace the stored collection after capture; restore must use its own detached copy.
        prefs.edit().putStringSet("set", setOf("replacement")).commit()
        saved.restore()

        assertEquals(setOf("ink", "brush"), prefs.getStringSet("set", emptySet()))
    }

    @Test fun secondRestoreDoesNotClobberChangesMadeAfterTheFirstRestore() {
        val prefs = preferences()
        prefs.edit().putString("tools", "before").commit()
        val saved = SelectBenchPreferences(prefs, setOf("tools"))

        prefs.edit().putString("tools", "during").commit()
        saved.restore()
        assertEquals("before", prefs.getString("tools", null))

        prefs.edit().putString("tools", "after first restore").commit()
        saved.restore()
        assertEquals("after first restore", prefs.getString("tools", null))
    }
}
