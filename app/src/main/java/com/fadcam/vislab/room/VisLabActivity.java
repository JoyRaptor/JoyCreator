package com.fadcam.vislab.room;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.motion.Motion;
import com.fadcam.vislab.VisLab;
import com.fadcam.vislab.audio.LiveAnalyzer;
import com.fadcam.vislab.audio.MicSource;
import com.fadcam.vislab.audio.PcmRing;
import com.fadcam.vislab.audio.SongPlayer;
import com.fadcam.vislab.audio.SoundSource;
import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.view.VisTextureView;

import java.util.List;

/**
 * VisLab: the room where sound becomes picture. A full-screen live look, the looks along the
 * bottom (tap, or swipe the picture), what to listen to (the demo song, the microphone or a song
 * from the phone), and the editor. Opening the editor shrinks the picture into the space above
 * it, so the part being edited is never hidden behind the drawer.
 */
public class VisLabActivity extends AppCompatActivity implements KnobPanel.Host, VisLabSession.Listener {

    public static final String EXTRA_LOOK = "vislab_look";
    private static final String DEMO = "vislab/demo/vislab_demo.m4a";
    private static final int SRC_DEMO = 0, SRC_MIC = 1, SRC_SONG = 2;

    private VisTextureView view;
    private FrameLayout stage;
    private LinearLayout top;
    private SlantRow sources;
    private LookStrip strip;
    private KnobPanel panel;
    private TextView lookName;
    private TextView pauseBtn;
    private VisLabSession session;
    private ImageView joy;
    private boolean drawerOpen;
    private boolean paused;

    private final PcmRing ring = new PcmRing(96000, 48000);
    private final LiveAnalyzer analyzer = new LiveAnalyzer(ring);
    @Nullable private SoundSource source;
    private int sourceKind = SRC_DEMO;

    private ActivityResultLauncher<String> askMic;
    private ActivityResultLauncher<String[]> pickSong;
    private ActivityResultLauncher<String[]> pickPicture;
    @Nullable private com.google.gson.JsonObject pictureFor;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        askMic = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            if (ok) useSource(SRC_MIC, null);
            else {
                Toast.makeText(this, "VisLab needs the microphone to listen to the room.", Toast.LENGTH_LONG).show();
                sources.setSelected(sourceKind);
            }
        });
        pickSong = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) useSource(SRC_SONG, uri);
            else sources.setSelected(sourceKind);
        });
        pickPicture = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            com.google.gson.JsonObject L = pictureFor;
            pictureFor = null;
            if (uri == null || L == null) return;
            try {
                // Keep the right to read it after a restart, so a saved look still shows it.
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            beginEdit("Picture");
            L.addProperty("image", uri.toString());
            edited();
            panel.rebuild();
        });
        session = VisLabSession.get(this);
        buildUi();
        String want = getIntent().getStringExtra(EXTRA_LOOK);
        if (want != null) {
            List<VisScene> looks = session.looks();
            for (int i = 0; i < looks.size(); i++) if (looks.get(i).id().equals(want)) session.roomPicked(i);
        }
        session.bind(this);
        useSource(SRC_DEMO, null);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Studio.GROUND);
        stage = new FrameLayout(this);
        view = new VisTextureView(this);
        view.setFeatureSource(dt -> {
            view.renderer().setWordsTime(demoSeconds());   // the demo lyrics follow the demo song
            return analyzer.step(dt);
        });
        view.renderer().setImageSource(new Pictures(this));
        stage.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GestureDetector swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(@Nullable MotionEvent a, @NonNull MotionEvent b, float vx, float vy) {
                if (a == null || drawerOpen) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) > Ui.dp(VisLabActivity.this, 60) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    setLook(session.current() + (dx < 0 ? 1 : -1));
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (drawerOpen) setDrawer(false);
                else toggleChrome();
                return true;
            }
        });
        stage.setOnTouchListener((v, e) -> swipe.onTouchEvent(e));

        // Top bar over a soft scrim so it reads on any look.
        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xA6000000, 0x00000000}));
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int g = Ui.dp(this, 14);
        top.setPadding(g, Ui.dp(this, 8), g, Ui.dp(this, 6));
        TextView back = Ui.icon(this, "arrow_back", 22, Studio.INK);
        Ui.label(back, "Back to the lobby");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        TextView mark = Ui.text(this, "VisLab", 17, Studio.INK, true, 800);
        mark.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) ->
                mark.getPaint().setShader(new LinearGradient(0, 0, Math.max(1, v.getWidth()), 0, Ui.VIZ_A, Ui.VIZ_B, Shader.TileMode.CLAMP)));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMarginStart(Ui.dp(this, 4));
        top.addView(mark, mlp);
        lookName = Ui.text(this, "", 13, Studio.LABEL, false, 600);
        lookName.setSingleLine(true);
        lookName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.setMarginStart(Ui.dp(this, 10));
        top.addView(lookName, nlp);
        pauseBtn = Ui.icon(this, "pause", 22, Studio.INK);
        Ui.label(pauseBtn, "Pause or play");
        pauseBtn.setOnClickListener(v -> {
            Motion.press(v);
            setPaused(!paused);
        });
        top.addView(pauseBtn, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        TextView edit = Ui.text(this, "Edit", 12.5f, Studio.GROUND, true, 700);
        edit.setGravity(Gravity.CENTER);
        edit.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        android.graphics.drawable.GradientDrawable editBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.VIZ_A, Ui.VIZ_B});
        editBg.setCornerRadius(Ui.dp(this, 999));
        edit.setBackground(editBg);
        Ui.label(edit, "Open the editor");
        edit.setOnClickListener(v -> {
            Motion.press(v);
            setDrawer(!drawerOpen);
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 32));
        elp.setMarginStart(Ui.dp(this, 6));
        top.addView(edit, elp);
        // Joybot lives in the upper-right corner of every room: the Studio editor's own button.
        joy = new ImageView(this);
        joy.setImageResource(R.drawable.joybot_neutral);
        joy.setBackgroundResource(R.drawable.joybot_orb);
        joy.setImageTintList(android.content.res.ColorStateList.valueOf(Studio.INK));
        int jp = Ui.dp(this, 7);
        joy.setPadding(jp, jp, jp, jp);
        joy.setContentDescription("Joybot");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(joy,
                "Joybot, your helper — he glows green when he has an idea, amber when something needs your attention");
        joy.setOnClickListener(v -> {
            startActivity(new Intent(this, com.fadcam.ui.faditor.ai.ChatAssistantActivity.class)
                    .putExtra(com.fadcam.ui.faditor.ai.ChatAssistantActivity.EXTRA_ROOM, "VISLAB"));
            com.fadcam.ui.faditor.ai.joybot.JoybotPulse.breathe(v, false);
        });
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        jlp.setMarginStart(Ui.dp(this, 8));
        top.addView(joy, jlp);
        chrome.addView(top);

        sources = new SlantRow(this, Ui.VIZ_A, Ui.VIZ_B);
        sources.add("music_note", "Demo song", "Play the built-in demo song");
        sources.add("mic", "Microphone", "Listen to the room through the microphone");
        sources.add("library_music", "A song", "Pick a song from this phone");
        sources.setSelected(SRC_DEMO);
        sources.setOnPick(i -> {
            if (i == SRC_MIC) {
                if (MicSource.hasPermission(this)) useSource(SRC_MIC, null);
                else askMic.launch(Manifest.permission.RECORD_AUDIO);
            } else if (i == SRC_SONG) {
                pickSong.launch(new String[]{"audio/*"});
            } else {
                useSource(SRC_DEMO, null);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(g, Ui.dp(this, 2), g, Ui.dp(this, 14));
        chrome.addView(sources, slp);
        root.addView(chrome, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        strip = new LookStrip(this);
        strip.setPadding(g, Ui.dp(this, 10), g, Ui.dp(this, 12));
        strip.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xBF000000, 0x00000000}));
        strip.setLooks(session.looks());
        strip.setOnPick(this::setLook);
        strip.setOnNew(() -> {
            session.newLook("New look", null);
            panel.showLayers();
            if (!drawerOpen) setDrawer(true);
            else panel.rebuild();
        });
        root.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        panel = new KnobPanel(this, VisLab.get(this).types(), this);
        panel.setVisibility(View.INVISIBLE);
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            chrome.setPadding(bars.left, bars.top, bars.right, 0);
            strip.setPadding(g + bars.left, Ui.dp(this, 10), g + bars.right, Ui.dp(this, 12) + bars.bottom);
            panel.setPadding(Ui.dp(this, 14) + bars.left, Ui.dp(this, 10), Ui.dp(this, 14) + bars.right, bars.bottom);
            return insets;
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            if (b - t != ob - ot || r - l != or2 - ol) {
                int h = b - t;
                // Portrait: the drawer takes the lower 55%; landscape: most of the height, but the
                // picture then still shows beside it, shrunk into the space above.
                boolean portrait = h > r - l;
                ViewGroup.LayoutParams lp = panel.getLayoutParams();
                lp.height = Math.round(h * (portrait ? 0.55f : 0.62f));
                panel.setLayoutParams(lp);
                if (!drawerOpen) panel.post(() -> panel.setTranslationY(panel.getHeight()));
                else fitStage(true);
            }
        });
        setContentView(root);
    }

    private boolean chromeShown = true;

    /** A tap on the picture hides everything but the look (for watching, or a screen recording). */
    private void toggleChrome() {
        chromeShown = !chromeShown;
        View chrome = (View) top.getParent();
        float a = chromeShown ? 1f : 0f;
        chrome.animate().alpha(a).setDuration(180).start();
        strip.animate().alpha(a).setDuration(180).start();
        chrome.setVisibility(View.VISIBLE);
        strip.setVisibility(View.VISIBLE);
        if (!chromeShown) {
            chrome.postDelayed(() -> { if (!chromeShown) { chrome.setVisibility(View.INVISIBLE); strip.setVisibility(View.INVISIBLE); } }, 200);
        }
    }

    private void setDrawer(boolean open) {
        drawerOpen = open;
        if (open) {
            panel.resetSelection();
            panel.rebuild();
            panel.setVisibility(View.VISIBLE);
            panel.setTranslationY(panel.getHeight());
        }
        panel.animate().translationY(open ? 0 : panel.getHeight()).setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (!drawerOpen) panel.setVisibility(View.INVISIBLE);
                }).start();
        strip.animate().alpha(open ? 0f : 1f).setDuration(160).start();
        sources.animate().alpha(open ? 0f : 1f).setDuration(160).start();
        sources.setEnabled(!open);
        fitStage(open);
    }

    /**
     * Shrink the picture into the space left above the open drawer (and under the top bar), so
     * the whole look stays in view while it is edited. Scaling the view, not resizing it, keeps
     * the look's composition identical and costs the GPU nothing.
     */
    private void fitStage(boolean open) {
        float target = 1f, ty = 0f;
        if (open) {
            int h = stage.getHeight();
            int avail = h - panel.getHeight() - top.getBottom();
            target = Math.max(0.25f, Math.min(1f, avail / (float) Math.max(1, h)));
            ty = top.getBottom();
        }
        stage.setPivotX(stage.getWidth() / 2f);
        stage.setPivotY(0f);
        final float fs = stage.getScaleX(), ft = stage.getTranslationY();
        final float ts = target, tt = ty;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(220);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> {
            float k = (float) an.getAnimatedValue();
            float s = fs + (ts - fs) * k;
            stage.setScaleX(s);
            stage.setScaleY(s);
            stage.setTranslationY(ft + (tt - ft) * k);
        });
        a.start();
    }

    // ------------------------------------------------------------------ looks

    /** Browsing (strip, swipe) is not an undo step; it just changes what is on screen. */
    private void setLook(int i) {
        session.roomPicked(i);
        List<VisScene> looks = session.looks();
        if (!looks.isEmpty()) onLook(looks.get(session.current()), session.current());
    }

    // VisLabSession.Listener: the look on screen changed (here, by Joybot, or by undo).
    @Override
    public void onLook(@NonNull VisScene look, int index) {
        view.setScene(look);
        lookName.setText(look.name());
        if (strip.childCount() != session.looks().size()) strip.setLooks(session.looks());
        strip.setSelected(index);
        if (drawerOpen) {
            panel.resetSelection();
            panel.rebuild();
        }
    }

    // VisLabSession.Listener: looks were added, removed, renamed, or read from the folder.
    @Override
    public void onLooks() {
        strip.setLooks(session.looks());
        strip.setSelected(session.current());
        if (drawerOpen) panel.rebuild();
    }

    @Override
    public void onKept() {
        if (drawerOpen) panel.refreshFooter();
    }

    @Override
    public void onBrand(@Nullable String[] stops) {
        view.renderer().setBrandPalette(stops);
    }

    // KnobPanel.Host
    @Nullable
    @Override
    public VisScene scene() {
        List<VisScene> looks = session.looks();
        return looks.isEmpty() ? null : looks.get(session.current());
    }

    @Override
    public void beginEdit(@NonNull String what) {
        session.roomWillEdit(what);
    }

    @Override
    public void edited() {
        VisScene s = scene();
        if (s != null) s.touch();
        session.roomEdited();
        stage.removeCallbacks(refreshFooter);
        stage.postDelayed(refreshFooter, 300);
    }

    private final Runnable refreshFooter = () -> {
        if (drawerOpen) panel.refreshFooter();
    };

    @Override
    public boolean undo() {
        return session.undo();
    }

    @Override
    public boolean redo() {
        return session.redo();
    }

    @Override
    public void brand(@Nullable String[] stops) {
        session.roomBrand(stops);
        view.renderer().setBrandPalette(stops);
    }

    @Nullable
    @Override
    public String lookFile() {
        VisScene s = scene();
        return s == null ? null : session.lookFile(s.id());
    }

    @Override
    public boolean lookIsBuiltIn() {
        VisScene s = scene();
        return s != null && session.isBuiltIn(s.id());
    }

    @Override
    public boolean lookIsCustomised() {
        VisScene s = scene();
        return s != null && session.isCustomised(s.id());
    }

    @NonNull
    @Override
    public java.util.List<VisScene> allLooks() {
        return session.looks();
    }

    @NonNull
    @Override
    public java.util.List<com.fadcam.vislab.model.VisPart> myParts() {
        return session.parts();
    }

    @Override
    public void saveLayerAsPart(@NonNull com.google.gson.JsonObject layer) {
        VisScene s = scene();
        if (s == null) return;
        com.google.gson.JsonElement n = layer.get("name");
        String base = n != null && n.isJsonPrimitive() ? n.getAsString() : "My part";
        LookSheets.askName(this, "Save as a part", base + " (" + s.name() + ")", "Save", name -> {
            session.savePart(layer, name, s);
            android.widget.Toast.makeText(this, "Saved with your looks as " + name + ".vispart.json", android.widget.Toast.LENGTH_SHORT).show();
            panel.rebuild();
        });
    }

    @Override
    public void duplicateLook() {
        VisScene s = scene();
        if (s == null) return;
        LookSheets.askName(this, "Duplicate " + s.name(), s.name() + " copy", "Duplicate",
                name -> session.duplicate(name, "Duplicate", null));
    }

    @Override
    public void renameLook() {
        VisScene s = scene();
        if (s == null) return;
        LookSheets.askName(this, "Rename " + s.name(), s.name(), "Rename",
                name -> session.rename(s.id(), name, "Rename", null));
    }

    @Override
    public void revertLook() {
        VisScene s = scene();
        if (s == null) return;
        LookSheets.confirm(this, "Revert " + s.name() + " to default?",
                "This will override any customisations you have made to this look. Undo can still bring them back.",
                "Revert", () -> session.revertToDefault(s.id(), "Revert to default", null));
    }

    @Override
    public void deleteLook() {
        VisScene s = scene();
        if (s == null) return;
        LookSheets.confirm(this, "Delete " + s.name() + "?",
                "Its file goes to the Trash in your Joy Creator folder. Undo brings it back.",
                "Delete", () -> session.deleteLook(s.id(), "Delete look", null));
    }

    @Override
    public void pickPicture(@NonNull com.google.gson.JsonObject layer) {
        pictureFor = layer;
        pickPicture.launch(new String[]{"image/*"});
    }

    // ------------------------------------------------------------------ sound

    /** Where the demo song is, seconds (its sample lyrics are timed to it); NaN for other sound. */
    private double demoSeconds() {
        SoundSource s = source;
        if (sourceKind != SRC_DEMO || s == null) return Double.NaN;
        long f = s.playheadFrame();
        return f < 0 ? Double.NaN : f / (double) Math.max(1, ring.sampleRate());
    }

    private void useSource(int kind, @Nullable Uri song) {
        if (source != null) source.stop();
        source = null;
        SoundSource s;
        if (kind == SRC_MIC) s = new MicSource(ring);
        else if (kind == SRC_SONG && song != null) s = SongPlayer.ofUri(ring, song);
        else s = SongPlayer.ofAsset(ring, DEMO);
        if (!s.start(this)) {
            Toast.makeText(this, kind == SRC_MIC ? "The microphone is busy or not allowed." : "That song could not be opened.",
                    Toast.LENGTH_LONG).show();
            sources.setSelected(sourceKind);
            return;
        }
        source = s;
        sourceKind = kind;
        analyzer.setSource(s);
        sources.setSelected(kind);
        setPaused(false);
    }

    private void setPaused(boolean p) {
        paused = p;
        view.setPaused(p);
        if (source != null) source.setPaused(p);
        pauseBtn.setText(p ? "play_arrow" : "pause");
        keepAwake(!p);
    }

    /** While the visualiser plays, the screen stays on (like a video); paused, it may sleep. */
    private void keepAwake(boolean on) {
        if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (source != null) source.setPaused(true);
        view.setPaused(true);
        session.flush();   // the app may be closed from here: keep every change now
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.fadcam.ui.faditor.ai.joybot.JoybotPulse.lightButton(joy, null, com.fadcam.ui.faditor.ai.joybot.IdeaEngine.Room.VISLAB);
        if (!paused) {
            if (source != null) source.setPaused(false);
            view.setPaused(false);
        }
    }

    @Override
    protected void onDestroy() {
        session.bind(null);
        if (source != null) source.stop();
        source = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            setDrawer(false);
            return;
        }
        super.onBackPressed();
    }
}
