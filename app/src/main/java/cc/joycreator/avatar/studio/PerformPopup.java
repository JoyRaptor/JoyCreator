package cc.joycreator.avatar.studio;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.perform.Inputs;
import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.ui.PerformPanel;
import cc.joycreator.avatar.ui.StageView;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.audio.VoiceoverRecorder;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.io.File;
import java.util.Map;

/**
 * The performing pop-up on a character layer (slice 2b, item 4; also slice 2 item 4's "record a pass in Studio"): a
 * small Perform, as the room's, over Studio's timeline. The character is shown live in its own little stage; the room's
 * own controls (head pad, Hold to talk, Blink, moods, the character's buttons, Breeze, Mic, Face) drive it; red records
 * a pass into the item's take from the playhead while Studio plays, punch-in and punch-out as in the room, and each
 * pass is one undo step (the host's).
 *
 * <p>The Mic uses Studio's own voiceover recorder with its sample tap (X4): one microphone stream gives both the lip
 * sync and the voice, which a pass keeps as an audio clip at the pass's start.</p>
 */
public final class PerformPopup implements PerformPanel.Host {

    public interface Host {
        /** Where the playhead is in the item's take, ms (the item's start is 0, plus its take offset). */
        long playheadInTakeMs(@NonNull SpriteOverlayItem item);

        /** The timeline time of the playhead, ms. */
        long playheadMs();

        void play();

        void pause();

        /**
         * A pass ended: the new take, and the voice heard during it (null when the Mic was off) starting at
         * {@code voiceAtMs} on the timeline. One undo step.
         */
        void passDone(@NonNull SpriteOverlayItem item, @NonNull String takeJson, @Nullable File voice, long voiceAtMs);
    }

    private static final int REQ_MIC = 7311, REQ_CAMERA = 7312;

    private final Activity a;
    private final SpriteOverlayItem item;
    private final Host host;
    private final Inputs inputs = new Inputs();
    private final CharacterDoc doc;
    private Dialog dialog;
    private StageView stage;
    private PerformPanel panel;
    private boolean playing;
    private boolean looping;
    private long lastNs;
    @Nullable private VoiceoverRecorder voice;
    private final VoiceMouth mouth = new VoiceMouth(VoiceoverRecorder.sampleRate());
    private volatile float micOpen;
    private volatile String micVis = "Neutral";
    private long passAtMs;
    @Nullable private com.fadcam.ui.faditor.avatar.face.FaceCapture face;

    private PerformPopup(Activity a, SpriteOverlayItem item, CharacterDoc doc, Host host) {
        this.a = a;
        this.item = item;
        this.doc = doc;
        this.host = host;
    }

    /** Opens it for a character item; says one plain sentence when the character cannot be found. */
    public static void show(@NonNull Activity a, @NonNull SpriteOverlayItem item, @NonNull Host host) {
        CharacterLoader.Loaded l = CharacterLoader.load(a, item.getCharacterRef());
        if (l == null) {
            Toast.makeText(a, R.string.ava_studio_character_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        new PerformPopup(a, item, l.doc, host).open();
    }

    private void open() {
        int pad = SheetKit.dp(a, 12);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Studio.GROUND);
        bg.setCornerRadius(SheetKit.dp(a, 14));
        col.setBackground(bg);
        col.setPadding(pad, pad, pad, 0);

        stage = new StageView(a);
        stage.setCharacter(doc);
        col.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SheetKit.dp(a, 170)));

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, pad / 2, 0, pad / 2);
        TextView hint = new TextView(a);
        hint.setText(R.string.ava_perform_hint);
        hint.setTextColor(Studio.INK_FAINT);
        hint.setTextSize(11);
        hint.setPadding(pad, 0, 0, 0);
        bar.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = SheetKit.chip(a, a.getString(R.string.ava_perform_close));
        SheetKit.label(close, a.getString(R.string.ava_perform_close_hover));
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(close);
        col.addView(bar);

        Take t = StudioCharacter.takeOf(item);
        inputs.recorder.setTake(t);
        java.util.List<String> ids = new java.util.ArrayList<>();
        org.json.JSONArray tr = doc.json.optJSONArray("triggers");
        if (tr != null) for (int i = 0; i < tr.length(); i++) {
            org.json.JSONObject o = tr.optJSONObject(i);
            if (o != null && !o.optString("id").isEmpty()) ids.add(o.optString("id"));
        }
        inputs.setButtons(ids);
        panel = new PerformPanel(a, this);
        panel.bind(doc, true);   // the room's own take bar: red records a pass, play, take back
        col.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SheetKit.dp(a, 300)));

        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(col);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);   // Studio's picture stays in view above it
        }
        dialog.setOnDismissListener(d -> close());
        dialog.show();
        looping = true;
        lastNs = 0;
        Choreographer.getInstance().postFrameCallback(loop);
    }

    private final Choreographer.FrameCallback loop = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long ns) {
            if (!looping) return;
            double dt = lastNs == 0 ? 1 / 60.0 : Math.min(0.1, (ns - lastNs) / 1e9);
            lastNs = ns;
            frame(dt);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void frame(double dt) {
        if (inputs.micOn()) inputs.micHeard(micOpen, micVis);
        if (inputs.faceOn() && face != null) inputs.faceSaw(face.sample(System.nanoTime()));
        if (!inputs.recorder.recording()) inputs.recorder.scrubTo(host.playheadInTakeMs(item) / 1000.0);
        Map<String, Object> sig = inputs.frame(dt, System.currentTimeMillis());
        stage.setSignals(sig);
        Object tx = sig.get("turnX"), ty = sig.get("turnY");
        if (tx instanceof Number && ty instanceof Number) panel.showHead(((Number) tx).doubleValue(), ((Number) ty).doubleValue());
        panel.tick();
    }

    // ── PerformPanel.Host ─────────────────────────────────────────────────────────────────────────

    @Override public Inputs inputs() { return inputs; }

    @Override
    public void toggleRecord() {
        if (inputs.recorder.recording()) {
            endPass();
            return;
        }
        inputs.record();
        inputs.recorder.scrubTo(host.playheadInTakeMs(item) / 1000.0);
        passAtMs = host.playheadMs();
        if (inputs.micOn()) restartVoice();   // the voice kept with the pass starts with it
        host.play();
        panel.tick();
    }

    private void endPass() {
        inputs.recorder.stop();
        host.pause();
        File heard = null;
        if (inputs.micOn() && voice != null) {
            heard = voice.stop();
            voice = null;
            startVoice();   // the Mic stays on for the next pass
        }
        Take t = inputs.recorder.take();
        if (t != null) host.passDone(item, StudioCharacter.json(t), heard, passAtMs);
        panel.tick();
    }

    /** Play: Studio's own playback (the character follows the playhead). */
    @Override
    public void togglePlay() {
        if (inputs.recorder.recording()) return;
        if (playing) host.pause();
        else host.play();
        playing = !playing;
    }

    /** Take back: Studio's Undo takes the last pass back (each pass is one step there). */
    @Override
    public void takeBack() {
        Toast.makeText(a, R.string.ava_perform_take_back, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void clearTake() {
        Toast.makeText(a, R.string.ava_perform_take_back, Toast.LENGTH_SHORT).show();
    }

    @Override public CharacterDoc.Node inHandNode() { return null; }

    @Override public cc.joycreator.avatar.ui.PoseRenderer renderer() { return stage.renderer(); }

    /** Only the room's Motion stage edits how parts move; Studio's pop-up shows Perform only. */
    @Override public cc.joycreator.avatar.ui.MotionSections.Host motion() { return null; }

    @Override
    public void toggleMic() {
        if (inputs.micOn()) {
            stopVoice();
            inputs.mic(false);
            panel.tick();
            return;
        }
        if (ContextCompat.checkSelfPermission(a, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            Toast.makeText(a, R.string.ava_perform_allow_then_tap, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!startVoice()) {
            Toast.makeText(a, R.string.ava_perform_mic_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        inputs.mic(true);
        panel.tick();
    }

    private boolean startVoice() {
        voice = new VoiceoverRecorder();
        mouth.reset();
        final short[][] ring = {new short[mouth.windowSize()]};
        final int[] filled = {0};
        voice.setPcmListener((s, n, rate) -> {
            // keep the last window of sound, and read the mouth from it
            short[] r = ring[0];
            if (n >= r.length) System.arraycopy(s, n - r.length, r, 0, r.length);
            else {
                System.arraycopy(r, n, r, 0, r.length - n);
                System.arraycopy(s, 0, r, r.length - n, n);
            }
            filled[0] = Math.min(r.length, filled[0] + n);
            VoiceMouth.Mouth m = mouth.at(new VoiceMouth.Samples() {
                @Override public int count() { return r.length; }
                @Override public float at(int i) { return r[i] / 32768f; }
            }, r.length);
            micOpen = m.open < 0.015f ? 0f : m.open;
            micVis = m.vis;
        });
        boolean ok = voice.start(a, host.playheadMs());
        if (!ok) voice = null;
        return ok;
    }

    private void restartVoice() {
        if (voice != null) {
            File old = voice.stop();
            if (old != null) //noinspection ResultOfMethodCallIgnored
                old.delete();   // heard before the pass: not kept
        }
        startVoice();
    }

    private void stopVoice() {
        if (voice == null) return;
        File f = voice.stop();
        if (f != null) //noinspection ResultOfMethodCallIgnored
            f.delete();
        voice = null;
    }

    @Override
    public void toggleFace() {
        if (inputs.faceOn()) {
            if (face != null) face.stop();
            inputs.face(false);
            panel.tick();
            return;
        }
        if (ContextCompat.checkSelfPermission(a, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            Toast.makeText(a, R.string.ava_perform_allow_then_tap, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (face == null) face = new com.fadcam.ui.faditor.avatar.face.FaceCapture(a);
            face.start();
            inputs.face(true);
        } catch (RuntimeException e) {
            Toast.makeText(a, R.string.avatar_face_failed, Toast.LENGTH_LONG).show();
        }
        panel.tick();
    }

    @Override
    public void setSetting(String key, boolean on, String stepName) {
        // a character's own settings are the room's to change; Studio only performs it
        Toast.makeText(a, R.string.ava_perform_settings_in_room, Toast.LENGTH_SHORT).show();
    }

    /** Leaving: the pass ends, the camera and the microphone let go at once. */
    private void close() {
        if (inputs.recorder.recording()) endPass();
        looping = false;
        stopVoice();
        inputs.mic(false);
        if (face != null) face.stop();
        inputs.face(false);
    }
}
