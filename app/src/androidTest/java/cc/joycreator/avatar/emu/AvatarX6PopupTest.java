package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteOverlayView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.studio.CharacterLoader;
import cc.joycreator.avatar.studio.RoomExportSheet;
import cc.joycreator.avatar.ui.StageView;

/**
 * The X6 audit's findings in Studio's performing pop-up, with real taps (tasks/audits/claude/AVA_X6_20261128.md):
 * <ul>
 *   <li>M14: Take back in the pop-up takes the last pass back (before: it only showed a message);</li>
 *   <li>M13: Play after a pass plays (before: the pop-up's own flag could send pause to a paused player);</li>
 *   <li>M1: a pass the recorder ends by itself (its longest take) is ended and kept (before: nothing was kept);</li>
 *   <li>F10: going Home closes the pop-up, so its camera and microphone let go (before: it stayed open, holding them).</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class AvatarX6PopupTest {

    @Test
    public void thePerformPopupKeepsPassesAndLetsGo() throws Exception {
        StringBuilder log = new StringBuilder("x6 popup\n");
        Intent it = new Intent(Emu.ins().getTargetContext(), FaditorEditorActivity.class)
            .putExtra(RoomExportSheet.EXTRA_DROP_CHARACTER, CharacterLoader.assetRef(CharacterAssets.TOPPS))
            .putExtra(RoomExportSheet.EXTRA_DROP_KEY_COLOUR, 0)
            .putExtra(RoomExportSheet.EXTRA_OPEN_EXPORT, false);
        ActivityScenario<FaditorEditorActivity> sc = ActivityScenario.launch(it);
        List<String> failed = new ArrayList<>();
        try {
            SpriteOverlayItem item = waitForCharacter();
            assertNotNull("Studio opened with Topps on the picture", item);
            final FaditorEditorActivity[] ed = new FaditorEditorActivity[1];
            sc.onActivity(a -> ed[0] = a);

            // M14: a pass, then Take back in the pop-up
            open(sc, item);
            String before = item.getCharacterTake();
            Emu.tapText("Record a pass", 5000);
            Emu.sleep(600);
            dragAHand();
            Emu.tapText("Record a pass", 5000);
            Emu.sleep(1200);
            boolean passed = !String.valueOf(item.getCharacterTake()).equals(before);
            Emu.tapText("Take back the last pass", 5000);
            Emu.sleep(1200);
            boolean back = String.valueOf(item.getCharacterTake()).equals(before);
            log.append("M14: pass changed the take ").append(passed).append(", Take back restored it ").append(back).append('\n');
            if (!passed || !back) failed.add("M14 take back");

            // M13: Play, a pass, then Play again must play
            Emu.tapText("Play the take", 5000);
            Emu.sleep(800);
            Emu.tapText("Record a pass", 5000);
            Emu.sleep(1200);
            Emu.tapText("Record a pass", 5000);
            Emu.sleep(1000);
            boolean pausedAfterPass = !playing(ed[0]);
            Emu.tapText("Play the take", 5000);
            Emu.sleep(800);
            boolean playsAgain = playing(ed[0]);
            log.append("M13: paused after the pass ").append(pausedAfterPass).append(", Play plays again ").append(playsAgain).append('\n');
            if (!playsAgain) failed.add("M13 play");
            if (playing(ed[0])) Emu.tapText("Play the take", 2000);
            Emu.sleep(500);

            // M1: the item's take starts 150 s in: the recorder (longest take 120 s) ends the pass by itself at once
            Emu.ins().runOnMainSync(() -> item.setCharacterTakeOffsetMs(150_000));
            int stepsBefore = undoCount(ed[0]);
            Emu.tapText("Record a pass", 5000);
            Emu.sleep(2500);
            int stepsAfter = undoCount(ed[0]);
            boolean kept = stepsAfter == stepsBefore + 1;
            log.append("M1: undo steps ").append(stepsBefore).append(" -> ").append(stepsAfter).append(" (\"").append(undoName(ed[0]))
                .append("\"), pass kept ").append(kept).append('\n');
            if (!kept) failed.add("M1 pass kept");
            Emu.ins().runOnMainSync(() -> item.setCharacterTakeOffsetMs(0));

            // F10: Home closes the pop-up (its camera and microphone with it)
            boolean openBefore = popupStage() != null;
            Emu.ins().getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            Emu.sleep(2500);
            boolean closed = popupStage() == null;
            log.append("F10: pop-up open before Home ").append(openBefore).append(", closed after ").append(closed).append('\n');
            if (!closed) failed.add("F10 home closes the pop-up");
        } finally {
            Log.i(Emu.TAG, log.toString());
            Emu.closeSoon(sc, 15000);
        }
        assertTrue("the performing pop-up: " + failed + "\n" + log, failed.isEmpty());
    }

    private static SpriteOverlayItem waitForCharacter() throws Exception {
        long end = System.currentTimeMillis() + 40000;
        while (System.currentTimeMillis() < end) {
            Emu.sleep(1000);
            Activity a = Emu.resumed();
            if (a == null) continue;
            for (SpriteOverlayView sv : find(a.getWindow().getDecorView(), SpriteOverlayView.class)) {
                for (SpriteOverlayItem i : items(sv)) if (i.isCharacter()) return i;
            }
        }
        return null;
    }

    private static void open(ActivityScenario<FaditorEditorActivity> sc, SpriteOverlayItem item) {
        sc.onActivity(a -> {
            try {
                Method m = FaditorEditorActivity.class.getDeclaredMethod("openCharacterPerform", SpriteOverlayItem.class);
                m.setAccessible(true);
                m.invoke(a, item);
            } catch (Exception e) {
                throw new AssertionError("FaditorEditorActivity.openCharacterPerform(item) is gone; update this test", e);
            }
        });
        Emu.sleep(3000);
    }

    /** A finger lifts the left hand on the pop-up's stage (so the pass writes something). */
    private static void dragAHand() {
        StageView st = popupStage();
        if (st == null) return;
        Object[] r = new Object[1];
        Emu.ins().runOnMainSync(() -> r[0] = st.renderer());
        if (!(r[0] instanceof cc.joycreator.avatar.studio.SolvedStageRenderer)) return;
        java.util.Map<String, double[]> tips = ((cc.joycreator.avatar.studio.SolvedStageRenderer) r[0]).tips();
        double[] hand = tips.get("L") != null ? tips.get("L") : tips.get("R");
        if (hand == null) return;
        float[] at = new float[3];
        Emu.ins().runOnMainSync(() -> {
            int[] loc = new int[2];
            st.getLocationOnScreen(loc);
            at[0] = loc[0] + st.viewX() + (float) hand[0] * st.viewScale();
            at[1] = loc[1] + st.viewY() + (float) hand[1] * st.viewScale();
            at[2] = st.viewScale();
        });
        Emu.drag(at[0], at[1], at[0] + 20 * at[2], at[1] - 140 * at[2], 300, 1200);
    }

    private static boolean playing(FaditorEditorActivity a) {
        boolean[] p = new boolean[1];
        Emu.ins().runOnMainSync(() -> {
            try {
                Field f = FaditorEditorActivity.class.getDeclaredField("playerManager");
                f.setAccessible(true);
                Object pm = f.get(a);
                p[0] = pm != null && (Boolean) pm.getClass().getMethod("isPlaying").invoke(pm);
            } catch (Exception e) {
                throw new AssertionError("cannot read Studio's player", e);
            }
        });
        return p[0];
    }

    private static String undoName(FaditorEditorActivity a) {
        String[] n = new String[1];
        Emu.ins().runOnMainSync(() -> {
            try {
                Field f = FaditorEditorActivity.class.getDeclaredField("undoManager");
                f.setAccessible(true);
                Object um = f.get(a);
                n[0] = (String) um.getClass().getMethod("peekUndoDescription").invoke(um);
            } catch (Exception e) {
                throw new AssertionError("cannot read Studio's undo", e);
            }
        });
        return n[0];
    }

    private static int undoCount(FaditorEditorActivity a) {
        int[] n = new int[1];
        Emu.ins().runOnMainSync(() -> {
            try {
                Field f = FaditorEditorActivity.class.getDeclaredField("undoManager");
                f.setAccessible(true);
                Object um = f.get(a);
                n[0] = (Integer) um.getClass().getMethod("getUndoCount").invoke(um);
            } catch (Exception e) {
                throw new AssertionError("cannot read Studio's undo", e);
            }
        });
        return n[0];
    }

    private static StageView popupStage() {
        List<View> roots = new ArrayList<>();
        Emu.ins().runOnMainSync(() -> {
            try {
                Class<?> g = Class.forName("android.view.WindowManagerGlobal");
                Object inst = g.getMethod("getInstance").invoke(null);
                Field f = g.getDeclaredField("mViews");
                f.setAccessible(true);
                @SuppressWarnings("unchecked") List<View> v = (List<View>) f.get(inst);
                for (View x : v) if (x.isAttachedToWindow() && x.getVisibility() == View.VISIBLE) roots.add(x);
            } catch (Exception e) {
                throw new AssertionError("cannot list the windows", e);
            }
        });
        for (View root : roots) for (StageView s : find(root, StageView.class)) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<SpriteOverlayItem> items(SpriteOverlayView v) throws Exception {
        Field f = SpriteOverlayView.class.getDeclaredField("items");
        f.setAccessible(true);
        Object[] o = new Object[1];
        Emu.ins().runOnMainSync(() -> {
            try { o[0] = new ArrayList<>((List<SpriteOverlayItem>) f.get(v)); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
        });
        return (List<SpriteOverlayItem>) o[0];
    }

    private static <T extends View> List<T> find(View root, Class<T> type) {
        List<T> hits = new ArrayList<>();
        Emu.ins().runOnMainSync(() -> walk(root, type, hits));
        return hits;
    }

    private static <T extends View> void walk(View v, Class<T> type, List<T> hits) {
        if (type.isInstance(v)) hits.add(type.cast(v));
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) walk(((ViewGroup) v).getChildAt(i), type, hits);
    }
}
