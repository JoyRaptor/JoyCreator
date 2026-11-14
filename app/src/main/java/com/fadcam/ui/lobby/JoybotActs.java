package com.fadcam.ui.lobby;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The animations Joybot ships with: what each is called, which mood it belongs to, and its frames.
 *
 * <p>They come from the owner's big sprite sheet, packed by {@code tools/joybot/pack_joybot.py}
 * into {@code assets/joybot/}: one small picture per animation plus {@code acts.json}. A picture is
 * opened only while he plays it and a few are kept, because the whole sheet at once is about 66 MB.</p>
 *
 * <p>This class is the catalogue and the picture cache. Choosing what to play is
 * {@link JoybotDirector}'s job; drawing is {@link JoybotFilm}'s.</p>
 */
public final class JoybotActs {

    private static final String DIR = "joybot/";
    /** Pictures kept open. Nine frames at 240 px are about 2 MB, so this is roughly 12 MB at most. */
    private static final int KEEP = 6;
    /** A way out longer than this plays at double speed: putting things away must not hold up a reply. */
    private static final int LONG_OUT = 3;

    /** One animation. Frame numbers index into its own picture; frame 0 there is the home pose. */
    public static final class Act {
        public final String name, mood, file;
        public final boolean general;
        public final float fps;
        final int cols, rows;
        final int[] in, loop, out;

        Act(JSONObject o) throws Exception {
            name = o.getString("name");
            mood = o.getString("mood");
            file = o.getString("file");
            general = o.optBoolean("general", false);
            fps = (float) o.optDouble("fps", 8.0);
            cols = Math.max(1, o.getInt("cols"));
            rows = Math.max(1, o.getInt("rows"));
            in = ints(o.optJSONArray("in"));
            loop = ints(o.optJSONArray("loop"));
            out = ints(o.optJSONArray("out"));
            if (loop.length == 0) throw new IllegalArgumentException("no loop: " + name);
        }
    }

    public interface Ready { void onReady(@Nullable Bitmap picture); }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    @Nullable private static JoybotActs instance;
    private static boolean tried;

    private final Context app;
    private final List<Act> acts = new ArrayList<>();
    @Nullable private final RectF focus;
    /** Seconds of idleness → the animation he drifts into (reading, then sleeping). */
    private final List<long[]> idleAt = new ArrayList<>();
    private final List<String> idleWhat = new ArrayList<>();
    private final LruCache<String, Bitmap> open = new LruCache<>(KEEP);

    private JoybotActs(Context app, JSONObject doc) throws Exception {
        this.app = app;
        JSONArray f = doc.optJSONArray("focus");
        focus = f != null && f.length() == 4
                ? new RectF((float) f.getDouble(0), (float) f.getDouble(1), (float) f.getDouble(2), (float) f.getDouble(3))
                : null;
        JSONArray a = doc.getJSONArray("acts");
        for (int i = 0; i < a.length(); i++) {
            try { acts.add(new Act(a.getJSONObject(i))); } catch (Exception ignored) { /* one bad entry never costs the rest */ }
        }
        JSONObject idle = doc.optJSONObject("idleAfterSeconds");
        if (idle != null) {
            java.util.Iterator<String> k = idle.keys();
            while (k.hasNext()) {
                String key = k.next();
                try {
                    long s = Long.parseLong(key);
                    String what = idle.getString(key);
                    if (named(what) == null) continue;
                    int at = 0;
                    while (at < idleAt.size() && idleAt.get(at)[0] < s) at++;
                    idleAt.add(at, new long[]{s});
                    idleWhat.add(at, what);
                } catch (Exception ignored) { /* skip */ }
            }
        }
    }

    /** The shipped animations, or null when the app has none. Blocking the first time. */
    @WorkerThread
    @Nullable
    public static synchronized JoybotActs get(@NonNull Context c) {
        if (tried) return instance;
        tried = true;
        try {
            Context app = c.getApplicationContext();
            JSONObject doc = new JSONObject(new String(read(app, DIR + "acts.json"), StandardCharsets.UTF_8));
            JoybotActs a = new JoybotActs(app, doc);
            if (!a.acts.isEmpty()) instance = a;
        } catch (Exception | OutOfMemoryError ignored) {
            // No animations in this build: Joybot keeps his stills. Never an error on screen.
        }
        return instance;
    }

    @Nullable
    public Act named(@NonNull String name) {
        for (Act a : acts) if (a.name.equals(name)) return a;
        return null;
    }

    /** Every animation he may choose by himself for this mood. */
    @NonNull
    public List<Act> generalFor(@NonNull String mood) {
        List<Act> out = new ArrayList<>();
        for (Act a : acts) if (a.general && a.mood.equals(mood)) out.add(a);
        return out;
    }

    int idleSteps() { return idleAt.size(); }
    long idleSeconds(int step) { return idleAt.get(step)[0]; }
    @NonNull String idleAct(int step) { return idleWhat.get(step); }

    /**
     * The animation that fits a job described in words ("Trimming the clip", "Deleting 3 clips"),
     * or null when nothing specific fits and he should pick a general one.
     */
    @Nullable
    public Act forTask(@Nullable String task) {
        if (task == null) return null;
        String t = task.toLowerCase(Locale.US);
        String[][] table = {
                {"discarding-clip", "delet", "remov", "discard", "trash", "clear"},
                {"cutting-film", "trim", "split", "cut", "shorten"},
                {"joining-film", "join", "merg", "stitch", "combin"},
                {"transcribing-audio", "transcrib", "caption", "subtitle", "speech"},
                {"reading-transcript", "transcript"},
                {"color-grading", "grade", "lut", "look"},
                {"adjusting-colors", "colour", "color", "bright", "contrast", "saturat"},
                {"arranging-clips", "arrang", "reorder", "order", "move", "slide"},
                {"comparing-videos", "compar"},
                {"choosing-video", "choos", "pick"},
                {"inspecting-video", "check", "scan", "analy", "detect"},
                {"inspecting-files", "file", "import", "librar"},
        };
        for (String[] row : table) {
            for (int i = 1; i < row.length; i++) {
                if (t.contains(row[i])) {
                    Act a = named(row[0]);
                    if (a != null) return a;
                }
            }
        }
        return null;
    }

    /** The picture if it is already open, else null (ask {@link #open}). Main thread. */
    @Nullable
    public Bitmap opened(@NonNull Act a) {
        Bitmap b = open.get(a.file);
        return b != null && !b.isRecycled() ? b : null;
    }

    /** Open an animation's picture off the main thread; {@code then} runs on the caller's view thread via {@code post}. */
    public void open(@NonNull Act a, @NonNull android.view.View poster, @NonNull Ready then) {
        IO.execute(() -> {
            Bitmap b = null;
            try {
                byte[] bytes = read(app, DIR + a.file);
                b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception | OutOfMemoryError ignored) {
                // Falls back to the stills.
            }
            final Bitmap got = b;
            poster.post(() -> {
                if (got != null) open.put(a.file, got);
                then.onReady(got);
            });
        });
    }

    /** The way in, then the loop. With {@code still}, just his pose: reduced motion never loops. */
    @NonNull
    JoybotFilm film(@NonNull Act a, @NonNull Bitmap picture, boolean still) {
        if (still) {
            return new JoybotFilm(picture, new Rect[]{cell(a, picture, a.loop[0])}, null, 1f, true).asActor(focus);
        }
        Rect[] r = new Rect[a.in.length + a.loop.length];
        for (int i = 0; i < a.in.length; i++) r[i] = cell(a, picture, a.in[i]);
        for (int i = 0; i < a.loop.length; i++) r[a.in.length + i] = cell(a, picture, a.loop[i]);
        return new JoybotFilm(picture, r, null, a.fps, true).withIntro(a.in.length).asActor(focus);
    }

    /** The whole animation once through (in, loop, out), for an entrance or a flourish before something else. */
    @NonNull
    JoybotFilm onceFilm(@NonNull Act a, @NonNull Bitmap picture) {
        Rect[] r = new Rect[a.in.length + a.loop.length + a.out.length];
        int k = 0;
        for (int i : a.in) r[k++] = cell(a, picture, i);
        for (int i : a.loop) r[k++] = cell(a, picture, i);
        for (int i : a.out) r[k++] = cell(a, picture, i);
        return new JoybotFilm(picture, r, null, a.fps, false).asActor(focus);
    }

    /** The way out (played once), or null when this animation has none. */
    @Nullable
    JoybotFilm outFilm(@NonNull Act a, @NonNull Bitmap picture) {
        if (a.out.length == 0) return null;
        Rect[] r = new Rect[a.out.length];
        for (int i = 0; i < r.length; i++) r[i] = cell(a, picture, a.out[i]);
        float fps = a.out.length > LONG_OUT ? a.fps * 2f : a.fps;
        return new JoybotFilm(picture, r, null, fps, false).asActor(focus);
    }

    private static Rect cell(Act a, Bitmap picture, int i) {
        int cw = picture.getWidth() / a.cols, ch = picture.getHeight() / a.rows;
        int n = a.cols * a.rows;
        int k = Math.max(0, Math.min(i, n - 1));
        int x = (k % a.cols) * cw, y = (k / a.cols) * ch;
        return new Rect(x, y, x + cw, y + ch);
    }

    private static int[] ints(@Nullable JSONArray a) throws Exception {
        if (a == null) return new int[0];
        int[] out = new int[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.getInt(i);
        return out;
    }

    private static byte[] read(Context app, String path) throws Exception {
        try (InputStream in = app.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
