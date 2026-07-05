package com.fadcam.ui.faditor.compositor;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;

import com.fadcam.FLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PLAN_LAYERS_V2 Part 10 probe #1 — measures the REAL simultaneous-hardware-decoder budget on a
 * device before M-COMP-2 commits to the live-second-video (PiP) path.
 *
 * <p>Spins up N independent {@link ExoPlayer}s (default 2), each decoding its own video into its
 * own {@link TextureView} (each TextureView is a SurfaceTexture consumer — the same surface class
 * the compositor would feed), plays them simultaneously for {@code durationMs}, and logs per-player
 * {@link DecoderCounters} once a second plus a final {@code PROBE_RESULT} line per player. Decoder
 * allocation failures surface as {@code onPlayerError} with the full cause chain logged.</p>
 *
 * <p>Drive via adb (needs a temporary {@code exported="true"} flip — NEVER commit that flip):
 * <pre>
 * adb shell am start -n com.fadcam.beta/com.fadcam.ui.faditor.compositor.DecoderBudgetProbeActivity \
 *   --ei players 2 --el durationMs 12000 \
 *   --es paths "/path/a.mp4,/path/b.mp4"
 * adb logcat -d -s DECODER_PROBE:D
 * </pre>
 * Fewer paths than players = paths are reused round-robin. Players loop their item so short clips
 * keep the decoder hot for the whole window. The activity finishes itself after the summary.</p>
 */
public class DecoderBudgetProbeActivity extends Activity {

    private static final String TAG = "DECODER_PROBE";

    private final List<ExoPlayer> players = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> errors = new ArrayList<>();
    private long startedAtMs;
    private long durationMs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int playerCount = getIntent().getIntExtra("players", 2);
        durationMs = getIntent().getLongExtra("durationMs", 12000L);
        String pathsCsv = getIntent().getStringExtra("paths");
        if (pathsCsv == null || pathsCsv.trim().isEmpty()) {
            FLog.e(TAG, "PROBE_ABORT no 'paths' extra supplied");
            finish();
            return;
        }
        String[] paths = pathsCsv.split(",");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        FLog.i(TAG, "PROBE_START players=" + playerCount + " durationMs=" + durationMs);
        for (int i = 0; i < playerCount; i++) {
            String path = paths[i % paths.length].trim();
            File f = new File(path);
            if (!f.exists()) {
                FLog.e(TAG, "PROBE_ABORT missing file: " + path);
                finish();
                return;
            }
            TextureView tv = new TextureView(this);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(tv, lp);

            final int idx = i;
            ExoPlayer p = new ExoPlayer.Builder(this).build();
            p.setVideoTextureView(tv);
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)));
            p.setRepeatMode(Player.REPEAT_MODE_ALL);
            p.setVolume(0f);
            p.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    String chain = describeCauseChain(error);
                    errors.add("player" + idx + ": " + chain);
                    FLog.e(TAG, "PROBE_ERROR player=" + idx + " " + chain, error);
                }

                @Override
                public void onRenderedFirstFrame() {
                    Format fmt = p.getVideoFormat();
                    FLog.i(TAG, "PROBE_FIRST_FRAME player=" + idx + " src=" + f.getName()
                            + " format=" + (fmt == null ? "?" : fmt.width + "x" + fmt.height
                            + " " + fmt.sampleMimeType + " @" + fmt.frameRate + "fps"));
                }
            });
            p.prepare();
            p.play();
            players.add(p);
        }

        startedAtMs = System.currentTimeMillis();
        handler.postDelayed(this::tick, 1000);
    }

    private void tick() {
        long elapsed = System.currentTimeMillis() - startedAtMs;
        boolean done = elapsed >= durationMs;
        for (int i = 0; i < players.size(); i++) {
            ExoPlayer p = players.get(i);
            DecoderCounters c = p.getVideoDecoderCounters();
            String stats;
            if (c == null) {
                stats = "counters=null (renderer not started)";
            } else {
                c.ensureUpdated();
                stats = String.format(Locale.US,
                        "rendered=%d dropped=%d maxConsecDropped=%d skipped=%d droppedToKeyframe=%d",
                        c.renderedOutputBufferCount, c.droppedBufferCount,
                        c.maxConsecutiveDroppedBufferCount, c.skippedOutputBufferCount,
                        c.droppedToKeyframeCount);
            }
            String line = "player=" + i + " t=" + elapsed + "ms state=" + p.getPlaybackState()
                    + " " + stats;
            if (done) {
                FLog.i(TAG, "PROBE_RESULT " + line
                        + (errors.isEmpty() ? " errors=none" : " errors=" + errors));
            } else {
                FLog.d(TAG, "PROBE_TICK " + line);
            }
        }
        if (done) {
            FLog.i(TAG, "PROBE_END players=" + players.size()
                    + " verdict=" + (errors.isEmpty() ? "ALL_DECODED" : "ERRORS:" + errors.size()));
            finish();
        } else {
            handler.postDelayed(this::tick, 1000);
        }
    }

    private static String describeCauseChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            t = t.getCause();
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        for (ExoPlayer p : players) {
            try {
                p.release();
            } catch (RuntimeException ignored) {
            }
        }
        players.clear();
    }
}
