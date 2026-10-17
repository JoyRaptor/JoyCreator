package com.fadcam.vislab.room;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.util.ArrayList;
import java.util.List;

/**
 * VisLab: the room where sound becomes picture. A full-screen live look, the looks along the
 * bottom (tap, or swipe the picture), what to listen to (the demo song, the microphone or a song
 * from the phone), and the editor. Opening the editor shrinks the picture into the space above
 * it, so the part being edited is never hidden behind the drawer.
 */
public class VisLabActivity extends AppCompatActivity implements KnobPanel.Host {

    public static final String EXTRA_LOOK = "vislab_look";
    private static final String PREFS = "vislab";
    private static final String PREF_LAST = "last_look";
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
    private final List<VisScene> looks = new ArrayList<>();
    private int current;
    private boolean drawerOpen;
    private boolean paused;

    private final PcmRing ring = new PcmRing(96000, 48000);
    private final LiveAnalyzer analyzer = new LiveAnalyzer(ring);
    @Nullable private SoundSource source;
    private int sourceKind = SRC_DEMO;

    private ActivityResultLauncher<String> askMic;
    private ActivityResultLauncher<String[]> pickSong;

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
        for (VisScene s : VisLab.get(this).builtInLooks()) looks.add(s.copy());
        buildUi();
        String want = getIntent().getStringExtra(EXTRA_LOOK);
        if (want == null) want = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST, null);
        int start = 0;
        for (int i = 0; i < looks.size(); i++) if (looks.get(i).id().equals(want)) start = i;
        setLook(start);
        useSource(SRC_DEMO, null);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Studio.GROUND);
        stage = new FrameLayout(this);
        view = new VisTextureView(this);
        view.setFeatureSource(dt -> analyzer.step(dt));
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
                    setLook(current + (dx < 0 ? 1 : -1));
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
        // Joybot lives in the upper-right corner of every room.
        ImageView joy = new ImageView(this);
        joy.setImageResource(R.drawable.joybot_neutral);
        joy.setColorFilter(Studio.INK);
        int jp = Ui.dp(this, 5);
        joy.setPadding(jp, jp, jp, jp);
        joy.setBackground(Ui.round(Studio.RAISED, Ui.dp(this, 999)));
        Ui.label(joy, "Joybot: ideas for this look");
        joy.setOnClickListener(v -> {
            Motion.press(v);
            startActivity(new Intent(this, com.fadcam.ui.faditor.ai.ChatAssistantActivity.class)
                    .putExtra(com.fadcam.ui.faditor.ai.ChatAssistantActivity.EXTRA_ROOM, "VISLAB"));
        });
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
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
        strip.setLooks(looks);
        strip.setOnPick(this::setLook);
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

    private void setLook(int i) {
        if (looks.isEmpty()) return;
        current = ((i % looks.size()) + looks.size()) % looks.size();
        VisScene s = looks.get(current);
        view.setScene(s);
        lookName.setText(s.name());
        strip.setSelected(current);
        if (drawerOpen) {
            panel.resetSelection();
            panel.rebuild();
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LAST, s.id()).apply();
    }

    @Nullable
    @Override
    public VisScene scene() {
        return looks.isEmpty() ? null : looks.get(current);
    }

    @Override
    public void edited() {
        VisScene s = scene();
        if (s != null) s.touch();
    }

    @Override
    public void brand(@Nullable String[] stops) {
        view.renderer().setBrandPalette(stops);
    }

    // ------------------------------------------------------------------ sound

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
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (source != null) source.setPaused(true);
        view.setPaused(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!paused) {
            if (source != null) source.setPaused(false);
            view.setPaused(false);
        }
    }

    @Override
    protected void onDestroy() {
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
