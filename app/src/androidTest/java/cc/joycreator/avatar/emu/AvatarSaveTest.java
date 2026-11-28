package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.joyfolder.android.JoyFolderHome;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import cc.joycreator.avatar.AvatarRoomActivity;
import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.file.CharacterStore;

/**
 * The room's saving and opening in the real Joy Creator folder (X6 audit R2, R3, R4, P2): an edit younger than the autosave is kept when
 * another character is opened at once (R3); two saves of a character that was never saved make one file (R4); each file keeps its own
 * character (R2); and saving the same character twice writes the same uid (P2).
 *
 * <p>Passes on the fixed room. Not proved to fail on the old one: that needs the old code, which this run does not have.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AvatarSaveTest {

    private static <T> T onFolderThread(Callable<T> c) throws Exception {
        return JoyFolderHome.io().submit(c).get();
    }

    private static List<CharacterDoc> files(Context app, List<String> names) throws Exception {
        return onFolderThread(() -> {
            List<CharacterDoc> out = new ArrayList<>();
            for (CharacterStore.Entry e : CharacterStore.list(app)) {
                CharacterDoc d = CharacterStore.read(app, e.path);
                if (d != null) {
                    out.add(d);
                    names.add(e.name);
                }
            }
            return out;
        });
    }

    @Test
    public void editsAreKeptWhenAnotherCharacterOpensAndOneFileIsMade() throws Exception {
        Context app = Emu.ins().getTargetContext();
        File out = Emu.dir("save");
        StringBuilder log = new StringBuilder("save and open\n");
        try {
            AvatarSendToStudioTest.chooseFolder(app, out, log);
            List<String> before = new ArrayList<>();
            int had = files(app, before).size();
            try (ActivityScenario<AvatarRoomActivity> sc = ActivityScenario.launch(AvatarRoomActivity.class)) {
                Emu.sleep(4000);
                AvatarRoomActivity[] a = new AvatarRoomActivity[1];
                sc.onActivity(x -> a[0] = x);
                // R3: an edit, and at once another character: the first is saved, not lost
                Emu.ins().runOnMainSync(() -> {
                    a[0].setSetting("blinkAuto", false, "a test edit");
                    a[0].openBuiltIn(CharacterAssets.PIP);
                });
                Emu.sleep(5000);
                List<String> names = new ArrayList<>();
                List<CharacterDoc> docs = files(app, names);
                log.append("after the switch: ").append(names).append('\n');
                boolean kept = false;
                for (CharacterDoc d : docs) {
                    org.json.JSONObject st = d.json.optJSONObject("settings");
                    if (st != null && d.json.optString("name").toLowerCase().contains("topps") && !st.optBoolean("blinkAuto", true)) kept = true;
                }
                assertTrue("the edit made just before another character opened was saved (R3); files: " + names, kept);

                // R4: two saves of a never-saved character, one after the other: one file for Pip
                Emu.ins().runOnMainSync(() -> {
                    a[0].setSetting("idle", false, "a second test edit");
                    a[0].saveNow();
                    a[0].saveNow();
                });
                Emu.sleep(5000);
                names.clear();
                docs = files(app, names);
                int pips = 0;
                for (CharacterDoc d : docs) if (d.json.optString("name").toLowerCase().contains("pip")) pips++;
                log.append("after two saves of Pip: ").append(names).append('\n');
                assertEquals("two quick saves of a new character make one file (R4); files: " + names, 1, pips);

                // R2: Topps's file is still Topps's (Pip's save never took its place)
                int toppses = 0;
                for (CharacterDoc d : docs) if (d.json.optString("name").toLowerCase().contains("topps")) toppses++;
                assertTrue("Topps still has its file (R2)", toppses >= 1);

                // P2: saving Pip again keeps its uid
                String uid1 = null, uid2 = null;
                for (CharacterDoc d : docs) if (d.json.optString("name").toLowerCase().contains("pip")) uid1 = d.json.optString("uid");
                Emu.ins().runOnMainSync(() -> {
                    a[0].setSetting("breathe", false, "a third test edit");
                    a[0].saveNow();
                });
                Emu.sleep(4000);
                names.clear();
                for (CharacterDoc d : files(app, names)) if (d.json.optString("name").toLowerCase().contains("pip")) uid2 = d.json.optString("uid");
                assertNotNull("Pip has a uid", uid1);
                assertTrue("a second save keeps the same uid (P2): " + uid1 + " then " + uid2, uid1.length() > 0 && uid1.equals(uid2));
            }
            log.append("had ").append(had).append(" files before the test\n");
        } finally {
            Log.i(Emu.TAG, "save and open\n" + log);
        }
    }
}
