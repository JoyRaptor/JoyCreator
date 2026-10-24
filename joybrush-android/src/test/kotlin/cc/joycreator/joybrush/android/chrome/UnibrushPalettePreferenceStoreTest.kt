package cc.joycreator.joybrush.android.chrome

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnibrushPalettePreferenceStoreTest {
    private lateinit var prefs: SharedPreferences

    @Before fun resetOwnedPreferences() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(FILE, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        assertTrue(prefs.edit().putString("tool-memory", "historical-brush").putInt("size", 17).commit())
    }

    @Test fun missingSecondaryDefaultsToOpaqueWhiteWithoutWriting() {
        val before = prefs.all.toMap()
        assertEquals(0xffffffff.toInt(), UnibrushPalettePreferenceStore(prefs).secondaryColorArgb)
        assertFalse(prefs.contains(UnibrushPalettePreferenceStore.SECONDARY_KEY))
        assertEquals(before, prefs.all)
    }

    @Test fun saveAndReopenPreserveEveryArgbByteAndUnrelatedSettings() {
        val colours = listOf(0x00000000, 0x00112233, 0x7f123456, 0xffabcdef.toInt(), Int.MIN_VALUE, Int.MAX_VALUE)
        for (colour in colours) {
            UnibrushPalettePreferenceStore(prefs).secondaryColorArgb = colour
            val reopened = RuntimeEnvironment.getApplication().getSharedPreferences(FILE, Context.MODE_PRIVATE)
            assertEquals(colour, UnibrushPalettePreferenceStore(reopened).secondaryColorArgb)
            assertEquals(colour, reopened.getInt(UnibrushPalettePreferenceStore.SECONDARY_KEY, -7))
            assertEquals("historical-brush", reopened.getString("tool-memory", null))
            assertEquals(17, reopened.getInt("size", 0))
        }
    }

    @Test fun wrongTypeFallsBackWithoutRemovingOrRewritingAnyPreference() {
        val key = UnibrushPalettePreferenceStore.SECONDARY_KEY
        val corruptWrites: List<(SharedPreferences.Editor) -> SharedPreferences.Editor> = listOf(
            { it.putString(key, "white") },
            { it.putBoolean(key, true) },
            { it.putLong(key, 0xffabcdefL) },
            { it.putFloat(key, 0.5f) },
            { it.putStringSet(key, setOf("white", "black")) }
        )
        for (write in corruptWrites) {
            assertTrue(write(prefs.edit()).commit())
            val before = prefs.all.toMap()
            val store = UnibrushPalettePreferenceStore(prefs)
            assertEquals(0xffffffff.toInt(), store.secondaryColorArgb)
            assertEquals(before, prefs.all)
            store.secondaryColorArgb = 0x12345678
            assertEquals(0x12345678, UnibrushPalettePreferenceStore(prefs).secondaryColorArgb)
            assertEquals("historical-brush", prefs.getString("tool-memory", null))
            assertEquals(17, prefs.getInt("size", 0))
        }
    }

    @Test fun instancesObserveLatestConfirmedValueAndRemovedKeyUsesDefault() {
        val first = UnibrushPalettePreferenceStore(prefs)
        val second = UnibrushPalettePreferenceStore(prefs)
        first.secondaryColorArgb = 0x65432100
        assertEquals(0x65432100, second.secondaryColorArgb)
        second.secondaryColorArgb = 0xff102030.toInt()
        assertEquals(0xff102030.toInt(), first.secondaryColorArgb)
        assertTrue(prefs.edit().remove(UnibrushPalettePreferenceStore.SECONDARY_KEY).commit())
        assertEquals(0xffffffff.toInt(), first.secondaryColorArgb)
        assertEquals("historical-brush", prefs.getString("tool-memory", null))
    }

    companion object {
        private const val FILE = "unibrush-secondary-palette-store-test"
    }
}
