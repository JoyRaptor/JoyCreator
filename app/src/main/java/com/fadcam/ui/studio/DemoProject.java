package com.fadcam.ui.studio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.ai.AIToolExecutor;
import com.fadcam.ui.faditor.ai.joybot.Shape;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SHELL-38: a small Studio project a tester can press play on, made on the phone from what the app
 * already ships. Five built-in title cards (the phone's own designs: no AI, no cost), joined by
 * transitions, over VisLab's demo song (our own procedural synth recording, already in the APK, so
 * the demo adds no size) with a music visualizer reacting to it.
 *
 * <p>It is built through the same Studio tools Joybot uses (each loads, changes and saves the
 * project the normal way), so no save code is special to it. Made once; later taps open the same
 * project while it still exists, and make a fresh one if it was deleted.</p>
 */
public final class DemoProject {

    private static final String TAG = "DemoProject";
    private static final String SONG_ASSET = "vislab/demo/vislab_demo.m4a";
    private static final String PREFS = "demo_project";
    private static final String KEY_ID = "project_id";

    /** {kind, mood, words, milliseconds} for each card, in order. Kinds and moods: SlideDesigns. */
    private static final String[][] CARDS = {
            {"title",   "cinematic", "Joy Creator",                                   "4500"},
            {"chapter", "playful",   "Make it move",                                  "4000"},
            {"list",    "bold",      "Record\nDraw\nAnimate\nEdit",                    "5000"},
            {"quote",   "calm",      "Press play, then make it yours.\nJoy Creator",  "4500"},
            {"credits", "clean",     "Music made in VisLab\nEverything here made in Joy Creator", "4500"},
    };
    /** The transition into each card after the first. */
    private static final String[] INTO = {"CROSS_DISSOLVE", "WIPE_LEFT", "CROSS_DISSOLVE", "CROSS_DISSOLVE"};

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;

    private DemoProject() {}

    /** Opens the demo, making it first if it does not exist yet. Safe to call from any screen. */
    public static void open(@NonNull Activity activity) {
        if (busy) return;
        Context app = activity.getApplicationContext();
        ProjectStorage storage = new ProjectStorage(app);
        String known = prefs(app).getString(KEY_ID, null);
        if (known != null && storage.exists(known)) {
            launch(activity, known);
            return;
        }
        busy = true;
        Toast.makeText(activity, R.string.demo_project_making, Toast.LENGTH_SHORT).show();
        WORK.execute(() -> {
            String id = null;
            try {
                id = make(app, storage);
            } catch (Throwable t) {
                FLog.e(TAG, "demo not made", t);
            }
            final String made = id;
            new Handler(Looper.getMainLooper()).post(() -> {
                busy = false;
                if (made == null) {
                    Toast.makeText(app, R.string.demo_project_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                prefs(app).edit().putString(KEY_ID, made).apply();
                if (!activity.isFinishing() && !activity.isDestroyed()) launch(activity, made);
            });
        });
    }

    private static void launch(@NonNull Activity a, @NonNull String id) {
        a.startActivity(new Intent(a, FaditorEditorActivity.class)
                .putExtra(FaditorEditorActivity.EXTRA_PROJECT_ID, id));
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Builds the whole project; the id, or null when any step fails (a half-made demo is deleted). */
    @Nullable
    private static String make(@NonNull Context app, @NonNull ProjectStorage storage) throws Exception {
        FaditorProject p = new FaditorProject(storage.uniqueName(app.getString(R.string.demo_project_name)));
        p.setCanvasPreset(Shape.of(16, 9).studioCanvas());
        if (!storage.save(p)) return null;
        String id = p.getId();
        AIToolExecutor tools = new AIToolExecutor(app, id);
        try {
            long total = 0;
            for (int i = 0; i < CARDS.length; i++) {
                String[] c = CARDS[i];
                long ms = Long.parseLong(c[3]);
                String out = tools.executeTool("generate_slide", new JSONObject()
                        .put("mode", "fullscreen")
                        .put("kind", c[0])
                        .put("mood", c[1])
                        .put("title_or_text", c[2])
                        .put("duration_ms_hint", ms)
                        .put("built_in", true)
                        .put("placement", new JSONObject().put("insertAtClipIndex", i)));
                if (failed(out)) throw new IllegalStateException("card " + i + ": " + out);
                total += ms;
            }
            for (int i = 0; i < INTO.length; i++) {
                String out = tools.executeTool("add_transition", new JSONObject()
                        .put("type", INTO[i]).put("clipIndex", i + 1).put("durationMs", 600));
                if (failed(out)) FLog.w(TAG, "transition " + i + " left out: " + out);
            }

            // The song: copied into the project's own assets folder, as Studio's Add copies a file.
            File song = copySong(app, storage.projectDir(id));
            long songMs = durationOf(song);
            if (songMs <= 0) throw new IllegalStateException("song unreadable");
            FaditorProject withCards = storage.load(id);
            if (withCards == null) throw new IllegalStateException("reload failed");
            AudioClip music = new AudioClip(Uri.fromFile(song), songMs);
            music.setLabel(app.getString(R.string.demo_project_song));
            music.setOutPointMs(Math.min(songMs, total));
            music.setFadeInMs(400);
            music.setFadeOutMs(1500);
            withCards.getTimeline().addAudioClip(music);
            if (!storage.save(withCards)) throw new IllegalStateException("song not saved");

            String viz = tools.executeTool("add_visualizer", new JSONObject().put("audioClipId", music.getId()));
            if (failed(viz)) FLog.w(TAG, "visualizer left out: " + viz);

            // Draw the cards now, as a new Joybot project does, so the first play has pictures.
            FaditorProject done = storage.load(id);
            if (done != null) {
                java.util.List<com.fadcam.ui.faditor.model.Clip> pending =
                        com.fadcam.ui.faditor.slides.SlideRenderer.collectUnrendered(storage.projectDir(id), done);
                if (!pending.isEmpty() && com.fadcam.ui.faditor.slides.SlideRenderer
                        .renderAll(app, storage.projectDir(id), pending) == null) {
                    storage.save(done);
                }
            }
            return id;
        } catch (Exception e) {
            FLog.w(TAG, "demo stopped part way; removing it", e);
            storage.delete(id);
            throw e;
        }
    }

    private static boolean failed(@Nullable String toolText) {
        return toolText == null || toolText.startsWith("Error") || toolText.startsWith("Validation")
                || toolText.startsWith("Apply");
    }

    @NonNull
    private static File copySong(@NonNull Context app, @NonNull File projectDir) throws Exception {
        File dir = new File(projectDir, "assets");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("no assets folder");
        File dest = new File(dir, "demo_song.m4a");
        try (InputStream in = app.getAssets().open(SONG_ASSET); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return dest;
    }

    private static long durationOf(@NonNull File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1 : Long.parseLong(d);
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }
}
