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
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SHELL-38: a small Studio project a tester can press play on, made from what the app ships.
 *
 * <p>Five title cards ({@code assets/demo/card1..5.mp4}, 132 KB in all: Studio's own built-in slide
 * designs, drawn once and kept at 960x540 so the phone need not spend minutes drawing them), joined
 * by transitions, over VisLab's demo song (our own procedural synth recording, already in the APK)
 * with a music visualizer reacting to it. Nothing in it is anyone else's.</p>
 *
 * <p>The cards and the song are copied into the project's own {@code assets} folder, as Studio's
 * Add copies a file, and put on the timeline the way "New project from clips" does; the transitions
 * and the visualizer go through the same Studio tools Joybot uses. No save code is special to it.
 * Made once; later taps open the same project while it exists, and make a fresh one if it was
 * deleted.</p>
 */
public final class DemoProject {

    private static final String TAG = "DemoProject";
    private static final String SONG_ASSET = "vislab/demo/vislab_demo.m4a";
    private static final String CARD_ASSET = "demo/card%d.mp4";
    private static final int CARD_COUNT = 5;
    private static final String PREFS = "demo_project";
    private static final String KEY_ID = "project_id_v2";

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

    /** Builds the whole project; its id, or an exception (a half-made demo is deleted). */
    @NonNull
    private static String make(@NonNull Context app, @NonNull ProjectStorage storage) throws Exception {
        FaditorProject p = new FaditorProject(storage.uniqueName(app.getString(R.string.demo_project_name)));
        p.setCanvasPreset(Shape.of(16, 9).studioCanvas());
        if (!storage.save(p)) throw new IllegalStateException("project not saved");
        String id = p.getId();
        try {
            File assets = new File(storage.projectDir(id), "assets");
            if (!assets.exists() && !assets.mkdirs()) throw new IllegalStateException("no assets folder");

            Timeline tl = p.getTimeline();
            long total = 0;
            for (int i = 1; i <= CARD_COUNT; i++) {
                File card = copy(app, String.format(java.util.Locale.ROOT, CARD_ASSET, i),
                        new File(assets, "demo_card" + i + ".mp4"));
                long ms = durationOf(card);
                if (ms <= 0) throw new IllegalStateException("card " + i + " unreadable");
                Clip clip = new Clip(Uri.fromFile(card), ms);
                clip.setDisplayName(app.getString(R.string.demo_project_card, i));
                clip.setAudioMuted(true);
                tl.addClip(clip);
                total += ms;
            }

            File song = copy(app, SONG_ASSET, new File(assets, "demo_song.m4a"));
            long songMs = durationOf(song);
            if (songMs <= 0) throw new IllegalStateException("song unreadable");
            AudioClip music = new AudioClip(Uri.fromFile(song), songMs);
            music.setLabel(app.getString(R.string.demo_project_song));
            music.setOutPointMs(Math.min(songMs, total));
            music.setFadeInMs(400);
            music.setFadeOutMs(1500);
            tl.addAudioClip(music);
            if (!storage.save(p)) throw new IllegalStateException("clips not saved");

            AIToolExecutor tools = new AIToolExecutor(app, id);
            for (int i = 0; i < INTO.length; i++) {
                String out = tools.executeTool("add_transition", new JSONObject()
                        .put("type", INTO[i]).put("clipIndex", i + 1).put("durationMs", 600));
                if (failed(out)) FLog.w(TAG, "transition " + i + " left out: " + out);
            }
            String viz = tools.executeTool("add_visualizer", new JSONObject().put("audioClipId", music.getId()));
            if (failed(viz)) FLog.w(TAG, "visualizer left out: " + viz);
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
    private static File copy(@NonNull Context app, @NonNull String asset, @NonNull File dest) throws Exception {
        try (InputStream in = app.getAssets().open(asset); OutputStream out = new FileOutputStream(dest)) {
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
