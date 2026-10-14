package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;
import com.fadcam.ui.lobby.JoybotFilm;
import com.fadcam.ui.lobby.JoybotView;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The owner's animated Joybot, when he has drawn it.
 *
 * <p>Owner, 2026-10-14: he will load a sprite sheet of Joybot with LABELLED animations (talking,
 * thinking, excited…). This reads it from the Joy Creator folder — {@code Joybot/joybot.png} beside
 * {@code Joybot/joybot.sprite.json}, the pair SpriteLab's "Write .sprite.json" produces — and turns
 * every animation whose name matches a {@link JoybotView.Mood} into that mood's film. Unnamed or
 * unmatched animations are ignored; a missing sheet simply leaves Joybot on his two stills.</p>
 *
 * <p>Names match loosely: "talk", "talking" and "Talk loop" all mean {@link JoybotView.Mood#TALK}.</p>
 */
public final class JoybotSheet {

    private JoybotSheet() {}

    public static final String DIR = "Joybot";
    public static final String IMAGE = DIR + "/joybot.png";
    public static final String SIDECAR = DIR + "/joybot.sprite.json";

    /** Cached for the process: the sheet is read once, not on every chat. */
    @Nullable private static Map<JoybotView.Mood, JoybotFilm> cache;
    private static boolean tried;

    /** Films by mood, or an empty map when there is no sheet. Blocking (reads the folder). */
    @WorkerThread
    @NonNull
    public static synchronized Map<JoybotView.Mood, JoybotFilm> load(@NonNull Context c) {
        if (tried) return cache == null ? new EnumMap<>(JoybotView.Mood.class) : cache;
        tried = true;
        Map<JoybotView.Mood, JoybotFilm> out = new EnumMap<>(JoybotView.Mood.class);
        try {
            if (!JoyFolderHome.isReachable(c)) return out;
            JoyFolder jf = JoyFolderHome.open(c);
            if (jf == null) return out;
            byte[] json = jf.readAll(SIDECAR);
            byte[] png = jf.readAll(IMAGE);
            JsonObject meta = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            SpriteSheet sheet = SpriteSheet.fromJson(meta);
            Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bmp == null) return out;
            float sheetFps = sheet.getFps() > 0 ? sheet.getFps() : 12f;
            for (SpriteSheet.Preset p : sheet.getPresets()) {
                JoybotView.Mood mood = moodFor(p.name);
                if (mood == null || out.containsKey(mood) || p.frames.isEmpty()) continue;
                Rect[] rects = new Rect[p.frames.size()];
                int[] weights = new int[p.frames.size()];
                for (int i = 0; i < rects.length; i++) {
                    rects[i] = SpriteSheetRenderer.cellRectSource(sheet, p.frames.get(i),
                            bmp.getWidth(), bmp.getHeight());
                    weights[i] = i < p.weights.size() && p.weights.get(i) != null ? p.weights.get(i) : 1;
                }
                boolean once = "once".equals(p.type);
                out.put(mood, new JoybotFilm(bmp, rects, weights, p.fps > 0 ? p.fps : sheetFps, !once));
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // No sheet, or one that won't read: Joybot keeps his stills. Never an error on screen.
        }
        cache = out;
        return out;
    }

    /** Forget the cached sheet (after the owner replaces it). */
    public static synchronized void forget() {
        tried = false;
        cache = null;
    }

    /** Which mood an animation name means, or null. */
    @Nullable
    static JoybotView.Mood moodFor(@NonNull String name) {
        String n = name.toLowerCase(Locale.US).trim();
        for (JoybotView.Mood m : JoybotView.Mood.values()) {
            if (n.equals(m.sheetName) || n.startsWith(m.sheetName)) return m;
        }
        if (n.startsWith("speak")) return JoybotView.Mood.TALK;
        if (n.startsWith("hear") || n.startsWith("listen")) return JoybotView.Mood.LISTEN;
        if (n.startsWith("ponder") || n.startsWith("think")) return JoybotView.Mood.THINK;
        if (n.startsWith("busy") || n.startsWith("working")) return JoybotView.Mood.WORK;
        if (n.startsWith("happy") || n.startsWith("cheer") || n.startsWith("joy")) return JoybotView.Mood.EXCITED;
        if (n.startsWith("present") || n.startsWith("ta-da") || n.startsWith("tada")) return JoybotView.Mood.SHOW;
        if (n.startsWith("sad") || n.startsWith("error") || n.startsWith("sorry")) return JoybotView.Mood.OOPS;
        if (n.startsWith("hover") || n.startsWith("rest") || n.startsWith("float")) return JoybotView.Mood.IDLE;
        return null;
    }

    /** Give a Joybot every film the sheet has (call on the main thread with {@link #load}'s result). */
    public static void dress(@NonNull JoybotView bot, @NonNull Map<JoybotView.Mood, JoybotFilm> films) {
        for (Map.Entry<JoybotView.Mood, JoybotFilm> e : films.entrySet()) bot.setMoodFilm(e.getKey(), e.getValue());
    }
}
