package com.fadcam.ui.faditor.avatar.face;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.avatar.AvatarParamTrack;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FACE-1: the face bench. A small proving screen for the face engine on a real phone, reached from
 * the old Avatar Studio's "Face test" button, until the new Avatar room plugs the same engine in
 * through {@link FaceCapture}. Everything on it is the engine's own API in use:
 * <ul>
 *   <li>a test face showing every signal, and the small private preview (tap it: picture or
 *       outline only);</li>
 *   <li>Neutral (one tap), Directions (five short prompts that settle which way is which on this
 *       phone), Mirror, the graphics chip;</li>
 *   <li>Record in passes by lane (Head, Mouth, Mood, Blink, Lean), Play, Undo; hold a lane to edit it
 *       afterwards (clear, stronger, softer, earlier, later);</li>
 *   <li>a status line with what the owner asked to be measured: faces a second, the delay from the
 *       camera to the character, the model's time, and the phone's temperature. It also goes to the
 *       log under the tag {@code FaceBench} once a second, for a bench run over adb.</li>
 * </ul>
 */
public final class FaceBenchActivity extends AppCompatActivity {

    private static final String TAG = "FaceBench";
    private static final int RC_CAMERA = 4122;
    private static final long MAX_TAKE_MS = 60_000;
    private static final String[][] PROMPTS = {
            {FaceCalibration.DirectionCheck.YAW, "Turn your head to your right"},
            {FaceCalibration.DirectionCheck.PITCH, "Look down at your chin"},
            {FaceCalibration.DirectionCheck.ROLL, "Tilt your head toward your right shoulder"},
            {FaceCalibration.DirectionCheck.LEAN, "Lean your whole head to your right"},
            {FaceCalibration.DirectionCheck.EYE, "Close your right eye"},
    };

    private FaceCapture face;
    private FaceBenchFace view;
    private FacePreviewView preview;
    private TextView status, hint;
    private TextView mirrorChip, gpuChip, pictureChip, recordChip, playChip, undoChip;
    private final Map<String, TextView> laneChips = new HashMap<>();
    private final Set<String> lanes = new LinkedHashSet<>();
    private float density;

    @Nullable private AvatarParamTrack take;
    private final ArrayDeque<AvatarParamTrack> undo = new ArrayDeque<>();
    @Nullable private FacePass pass;
    private boolean playing;
    private long clockStart;
    private int prompt = -1, answeredCount = 0;
    private long promptSince;
    private long lastLog;
    private float batteryC = Float.NaN;

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        face = new FaceCapture(this);
        Collections.addAll(lanes, FaceSignals.LANES);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Studio.GROUND);

        LinearLayout top = row();
        TextView back = chip("←", "Back to Avatar Studio");
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = new TextView(this);
        title.setText("Face test");
        title.setTextColor(Studio.INK);
        title.setTextSize(17);
        title.setPadding(dp(10), 0, 0, 0);
        top.addView(title);
        root.addView(top);

        FrameLayout stage = new FrameLayout(this);
        view = new FaceBenchFace(this);
        stage.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        preview = new FacePreviewView(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(96), dp(128), Gravity.TOP | Gravity.END);
        plp.setMargins(0, dp(10), dp(12), 0);
        stage.addView(preview, plp);
        preview.setOnClickListener(v -> togglePicture());
        tip(preview, "What the camera sees (only here, never saved). Tap: picture or outline only");
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = line(Studio.INK_DIM, 12);
        hint = line(Studio.INK, 14);
        root.addView(status);
        root.addView(hint);

        LinearLayout r1 = row();
        TextView neutral = chip("Neutral", "Make this face your neutral: relax and look at the phone");
        neutral.setOnClickListener(v -> { face.setNeutral(); hint.setText("Relax and look at the phone…"); });
        r1.addView(neutral);
        TextView dirs = chip("Directions", "Five short moves that settle which way is which on this phone");
        dirs.setOnClickListener(v -> startPrompts());
        r1.addView(dirs);
        mirrorChip = chip("Mirror", "Off: moves like your reflection. On: like someone facing you");
        mirrorChip.setOnClickListener(v -> { FaceTuning t = face.tuning(); t.mirror = !t.mirror; face.setTuning(t); paintChips(); });
        r1.addView(mirrorChip);
        gpuChip = chip("Graphics chip", "Read the face on the graphics chip instead of the processor (restarts the camera)");
        gpuChip.setOnClickListener(v -> { face.setUseGpu(!face.usesGpu()); restartCamera(); paintChips(); });
        r1.addView(gpuChip);
        pictureChip = chip("Picture", "Show the camera picture in the preview (off: outline only)");
        pictureChip.setOnClickListener(v -> togglePicture());
        r1.addView(pictureChip);
        root.addView(scroll(r1));

        LinearLayout r2 = row();
        recordChip = chip("● Record", "Record a pass: only the lanes that are on are written");
        recordChip.setOnClickListener(v -> toggleRecord());
        r2.addView(recordChip);
        playChip = chip("▶ Play", "Play the take");
        playChip.setOnClickListener(v -> togglePlay());
        r2.addView(playChip);
        undoChip = chip("Undo", "Take back the last pass or edit");
        undoChip.setOnClickListener(v -> undoOnce());
        r2.addView(undoChip);
        String[][] names = {{FaceSignals.LANE_HEAD, "Head"}, {FaceSignals.LANE_MOUTH, "Mouth"},
                {FaceSignals.LANE_MOOD, "Mood"}, {FaceSignals.LANE_BLINK, "Blink"}, {FaceSignals.LANE_LEAN, "Lean"}};
        for (String[] n : names) {
            TextView lc = chip(n[1], "Record this lane in the next pass. Hold to edit it afterwards");
            lc.setOnClickListener(v -> { if (!lanes.remove(n[0])) lanes.add(n[0]); paintChips(); });
            lc.setOnLongClickListener(v -> { editLane(n[0], n[1]); return true; });
            laneChips.put(n[0], lc);
            r2.addView(lc);
        }
        root.addView(scroll(r2));

        setContentView(root);
        face.setPreview(preview, true);
        paintChips();
    }

    // ── camera ──

    @Override
    protected void onResume() {
        super.onResume();
        if (face.hasCameraPermission()) {
            startCamera();
        } else {
            hint.setText("Allow the camera to see your face");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, RC_CAMERA);
        }
        view.postOnAnimation(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.removeCallbacks(tick);
        if (pass != null) toggleRecord();
        face.stop();                 // the camera is let go whenever this screen is not shown
        preview.setCameraOn(false);
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == RC_CAMERA && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else if (rc == RC_CAMERA) hint.setText("Without the camera the face can't follow you");
    }

    private void startCamera() {
        face.start();
        preview.setCameraOn(face.isRunning());
    }

    private void restartCamera() {
        face.stop();
        startCamera();
    }

    private void togglePicture() {
        face.setPreviewPicture(!face.previewPicture());
        paintChips();
    }

    // ── the frame loop ──

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.nanoTime();
            Map<String, Float> live = face.sample(now);
            Map<String, Float> shown = live;
            long t = (now - clockStart) / 1_000_000L;
            if (pass != null) {
                if (t > MAX_TAKE_MS) { toggleRecord(); }
                else {
                    pass.add(t, live);
                    shown = overlay(take != null ? take.sampleAt(t) : null, live);
                }
            } else if (playing && take != null && !take.isEmpty()) {
                long d = Math.max(1, take.durationMs());
                shown = take.sampleAt(t % (d + 400));
            }
            view.setSignals(shown);
            FaceCapture.Status st = face.status();
            view.setDim(pass == null && !playing && st.state != FaceCapture.State.TRACKING && st.presence < 0.05f);
            showStatus(st, now);
            runPrompts(now);
            view.postOnAnimation(this);
        }
    };

    /** While a pass records: the recorded lanes live, the others playing back from the take. */
    @NonNull
    private Map<String, Float> overlay(@Nullable Map<String, Float> old, @NonNull Map<String, Float> live) {
        Map<String, Float> out = old != null ? new HashMap<>(old) : new HashMap<>();
        for (Map.Entry<String, Float> e : live.entrySet()) {
            String lane = FaceSignals.laneOf(e.getKey());
            if (old == null || lane == null || lanes.contains(lane)) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private void showStatus(@NonNull FaceCapture.Status st, long now) {
        if (now - lastLog > 1_000_000_000L) {
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) batteryC = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        }
        String state;
        switch (st.state) {
            case TRACKING: state = "Face found"; break;
            case LOOKING: state = "Looking for your face"; break;
            case STARTING: state = "Starting the camera"; break;
            case NEEDS_PERMISSION: state = "Camera not allowed"; break;
            default: state = "Camera off";
        }
        String line = String.format(Locale.US, "%s · %.0f a second · %s behind you · model %s · phone %.1f °C · %s",
                state, st.rate, Float.isNaN(st.delayMs) ? "–" : String.format(Locale.US, "%.0f ms", st.delayMs),
                Float.isNaN(st.modelMs) ? "–" : String.format(Locale.US, "%.0f ms", st.modelMs), batteryC,
                st.onGraphicsChip ? "graphics chip" : "processor");
        status.setText(line);
        if (prompt < 0 && pass == null) {
            if (st.capturingNeutral && st.state == FaceCapture.State.TRACKING) {
                hint.setText(String.format(Locale.US, "Hold still: taking your neutral face (%d of %d)",
                        st.neutralProgress, st.neutralNeeded));
            } else if (st.state == FaceCapture.State.TRACKING && hint.getText().toString().startsWith("Hold still")) {
                hint.setText("Neutral set");
            }
        }
        if (now - lastLog > 1_000_000_000L) {
            lastLog = now;
            Log.i(TAG, String.format(Locale.US, "state=%s presence=%.2f rate=%.1f delayMs=%.0f modelMs=%.0f batteryC=%.1f gpu=%b neutral=%b",
                    st.state, st.presence, st.rate, st.delayMs, st.modelMs, batteryC, st.onGraphicsChip,
                    face.calibration().isNeutralSet()));
        }
    }

    // ── directions ──

    private void startPrompts() {
        if (!face.isRunning()) return;
        prompt = 0;
        answeredCount = 0;
        askPrompt(System.nanoTime());
    }

    private void askPrompt(long now) {
        promptSince = now;
        face.checkDirection(PROMPTS[prompt][0]);
        hint.setText((prompt + 1) + " of " + PROMPTS.length + ": " + PROMPTS[prompt][1]);
    }

    private void runPrompts(long now) {
        if (prompt < 0) return;
        FaceCalibration.DirectionCheck done = face.lastDirectionCheck();
        boolean answered = done != null && done.what().equals(PROMPTS[prompt][0]) && done.answer() != 0;
        boolean timedOut = now - promptSince > 8_000_000_000L;
        if (!answered && !timedOut) return;
        if (answered) answeredCount++;
        prompt++;
        if (prompt >= PROMPTS.length) {
            prompt = -1;
            hint.setText(answeredCount == 0 ? "No face seen, so nothing changed"
                    : answeredCount == PROMPTS.length ? "Directions set for this phone"
                    : "Directions set for " + answeredCount + " of " + PROMPTS.length + " (the rest are as they were)");
            return;
        }
        askPrompt(now);
    }

    // ── takes ──

    private void toggleRecord() {
        if (pass == null) {
            if (lanes.isEmpty()) { hint.setText("Turn on at least one lane to record"); return; }
            playing = false;
            pass = new FacePass(take, lanes);
            clockStart = System.nanoTime();
            hint.setText(take == null ? "Recording" : "Recording a pass over the take");
        } else {
            AvatarParamTrack next = pass.finish();
            boolean empty = pass.isEmpty();
            pass = null;
            if (!empty) {
                undo.push(take == null ? new AvatarParamTrack() : take);
                take = next;
                hint.setText(String.format(Locale.US, "Take: %.1f s. Play it, or record another pass", take.durationMs() / 1000f));
            }
        }
        paintChips();
    }

    private void togglePlay() {
        if (pass != null || take == null) return;
        playing = !playing;
        clockStart = System.nanoTime();
        paintChips();
    }

    /** One press, one step: the last pass or lane edit comes off (an empty take means no take). */
    private void undoOnce() {
        if (undo.isEmpty() || pass != null) return;
        AvatarParamTrack prev = undo.pop();
        take = prev.isEmpty() ? null : prev;
        playing = false;
        hint.setText(take == null ? "No take" : "Undone");
        paintChips();
    }

    private void editLane(@NonNull String lane, @NonNull String label) {
        if (take == null || pass != null) { hint.setText("Record a take first, then hold a lane to edit it"); return; }
        String[] items = {"Clear " + label, label + " stronger", label + " softer", label + " earlier", label + " later"};
        com.fadcam.ui.faditor.StudioDialog.builder(this)
                .setTitle(label + " lane")
                .setItems(items, (d, which) -> {
                    AvatarParamTrack t = take;
                    AvatarParamTrack edited;
                    switch (which) {
                        case 0: edited = FacePass.clearLane(t, lane); break;
                        case 1: edited = FacePass.scaleLane(t, lane, 1.3f); break;
                        case 2: edited = FacePass.scaleLane(t, lane, 0.75f); break;
                        case 3: edited = FacePass.shiftLane(t, lane, -80); break;
                        default: edited = FacePass.shiftLane(t, lane, 80); break;
                    }
                    undo.push(t);
                    take = edited;
                    hint.setText(items[which]);
                    paintChips();
                })
                .show();
    }

    // ── looks ──

    private void paintChips() {
        FaceTuning t = face.tuning();
        on(mirrorChip, t.mirror);
        on(gpuChip, face.usesGpu());
        on(pictureChip, face.previewPicture());
        on(recordChip, pass != null);
        on(playChip, playing);
        undoChip.setAlpha(undo.isEmpty() ? 0.4f : 1f);
        for (Map.Entry<String, TextView> e : laneChips.entrySet()) on(e.getValue(), lanes.contains(e.getKey()));
    }

    /** On = solid cyan with dark ink (the house's knock-out look), off = a raised grey pill. */
    private void on(@NonNull TextView v, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(on ? Studio.ARMED : Studio.RAISED);
        v.setBackground(bg);
        v.setTextColor(on ? Studio.GROUND : Studio.INK);
    }

    @NonNull
    private TextView chip(@NonNull String text, @NonNull String tip) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        on(t, false);
        tip(t, tip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        t.setLayoutParams(lp);
        return t;
    }

    private static void tip(@NonNull View v, @NonNull String s) {
        v.setContentDescription(s);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.setTooltipText(s);
    }

    @NonNull
    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(4), dp(8), dp(4));
        return r;
    }

    @NonNull
    private View scroll(@NonNull LinearLayout r) {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.addView(r);
        return s;
    }

    @NonNull
    private TextView line(int colour, int sp) {
        TextView t = new TextView(this);
        t.setTextColor(colour);
        t.setTextSize(sp);
        t.setPadding(dp(14), dp(2), dp(14), dp(2));
        t.setBackgroundColor(Color.TRANSPARENT);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
