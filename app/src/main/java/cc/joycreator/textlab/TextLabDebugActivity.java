package cc.joycreator.textlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.vislab.gl.ShaderLib;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The TextLab screen (a first, plain one): a look drawn live on the person's words, the looks as picture
 * chips to choose from, and Save, which puts the look and its moving preview in {@code Supplies/Looks/}.
 * The Library opens a saved look here ({@link #EXTRA_LOOK_FILE}).
 *
 * <p>Also the engine's check bench, by adb:</p>
 * <pre>am start -n &lt;package&gt;/cc.joycreator.textlab.TextLabDebugActivity
 *     --es look dark-steel --es text TITAN --ef t 3 --ei w 1280 --ei h 720</pre>
 * With {@code look} given, the still at {@code t} is saved to the app's files folder,
 * {@code textlab/<look>_t<t>.png}, for comparison with the look book. Tap the picture to hold or play.
 */
public final class TextLabDebugActivity extends Activity {

    private static final String TAG = "TextLab";
    /** A look file in the Joy Creator folder to open (its path there, e.g. "Supplies/Looks/Dust.text.json"). */
    public static final String EXTRA_LOOK_FILE = "lookFile";
    private static final int BLACK = 0xFF0B0B0C, CONTROL = 0xFF1F1F26, ZINC = 0xFFD4D4D8, ZINC_DIM = 0xFF71717A;

    private GLSurfaceView view;
    private TextView label;
    private LookChipRow chips;
    private EditText words;
    private volatile boolean live = true;

    private final List<JSONObject> looks = new ArrayList<>();
    @Nullable private volatile JSONObject current;
    private boolean wordsTyped;

    /** What the GL thread should draw next: {look, words, still?} (null = nothing new). */
    private final AtomicReference<Object[]> pending = new AtomicReference<>();
    private volatile String pendingWords;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        final boolean bench = in.hasExtra("look");
        final String lookId = bench ? in.getStringExtra("look") : "dark-steel";
        final String text = in.getStringExtra("text");
        final float t = in.getFloatExtra("t", 3f);
        final int w = in.getIntExtra("w", 1280), h = in.getIntExtra("h", 720);
        final float stage = in.getFloatExtra("stage", Float.NaN);
        final float tx = in.getFloatExtra("tx", 0f), ty = in.getFloatExtra("ty", 0f);
        live = in.getBooleanExtra("live", true);

        // ---- the screen: picture, chips, words + Save
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);
        FrameLayout stageBox = new FrameLayout(this);
        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(3);
        view.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        view.setOnClickListener(v -> live = !live);
        label = new TextView(this);
        label.setTextColor(ZINC);
        label.setBackgroundColor(0x99000000);
        label.setTextSize(11);
        label.setPadding(dp(8), dp(4), dp(8), dp(4));
        stageBox.addView(view);
        stageBox.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        root.addView(stageBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        chips = new LookChipRow(this);
        root.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(8), dp(8));
        words = new EditText(this);
        words.setSingleLine(true);
        words.setTextColor(ZINC);
        words.setHintTextColor(ZINC_DIM);
        words.setHint("Your words");
        words.setTextSize(16);
        words.setPadding(dp(12), dp(8), dp(12), dp(8));
        words.setBackground(pill(CONTROL));
        bar.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextColor(ZINC);
        save.setBackground(pill(CONTROL));
        save.setMinWidth(dp(84));
        save.setPadding(dp(18), 0, dp(18), 0);
        save.setContentDescription("Save this look to Supplies, Looks");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) save.setTooltipText("Save this look to Supplies › Looks, with its moving picture");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        slp.setMarginStart(dp(8));
        bar.addView(save, slp);
        // quick export (owner, 2026-11-12): Studio's own export mark, a type and size dialog
        android.widget.ImageButton export = new android.widget.ImageButton(this);
        export.setImageResource(com.fadcam.R.drawable.ic_export_studio);
        export.setBackground(pill(CONTROL));
        export.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        export.setPadding(dp(9), dp(9), dp(9), dp(9));
        export.setContentDescription("Export this look as a picture, video or moving sheet");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) export.setTooltipText("Export: a picture, a video or a moving sheet, at a size you choose");
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(40), dp(40));
        elp.setMarginStart(dp(8));
        bar.addView(export, elp);
        export.setOnClickListener(v -> askExport());
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        // ---- the built-in looks, as chips
        loadLooks();
        showChips(bench);
        chips.setOnPick(i -> pick(looks.get(i), null));
        if (!bench) {
            // the packs the person installed and their own saved looks join the built-in ones
            JoyFolderHome.io().execute(() -> {
                List<TextLookShelf.Item> more = TextLookShelf.inFolder(this);
                if (more.isEmpty()) return;
                runOnUiThread(() -> {
                    for (TextLookShelf.Item it : more) looks.add(it.look);
                    int keep = chips.chosen();
                    showChips(false);
                    if (keep >= 0) chips.choose(keep);
                });
            });
        }

        words.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!words.hasFocus()) return;   // set by the screen, not typed
                wordsTyped = true;
                pendingWords = s.toString();
            }
        });
        save.setOnClickListener(v -> askSave());

        // ---- what to show first
        String file = in.getStringExtra(EXTRA_LOOK_FILE);
        if (file != null) {
            openFile(file);
        } else {
            int i = indexOf(lookId);
            if (i >= 0) chips.choose(i);
            JSONObject first = i >= 0 ? looks.get(i) : looks.isEmpty() ? null : looks.get(0);
            if (bench && i < 0) first = readAssetLook(lookId);
            if (first != null) pick(first, text != null ? text.replace("\\n", "\n") : null);
        }

        view.setRenderer(new Renderer(bench, lookId, t, w, h, stage, tx, ty));
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // the export's check, by adb: --es export picture|video|sheet --ei ew 1920 --ei eh 1080 --ez eclear true --ef es 3
        String ex = in.getStringExtra("export");
        if (ex != null) view.postDelayed(() -> benchExport(ex, in), 1500);
        if (in.getBooleanExtra("iconcheck", false)) iconCheck();
        // Joybot's TextLab actions without an AI, by adb (the room open underneath):
        // --es action textlab_change --es args '{"changes":["Pushed back = 1.4"]}'
        String action = in.getStringExtra("action");
        if (action != null) {
            String args = in.getStringExtra("args");
            new Thread(() -> {
                String out;
                try {
                    out = com.fadcam.ui.faditor.ai.actions.AppActions.execute(getApplicationContext(), null, action,
                            new JSONObject(args != null ? args : "{}"));
                } catch (Exception e) {
                    out = "bench action failed: " + e;
                }
                Log.i(TAG, "bench action " + action + ": " + out.replace("\n", " | "));
                runOnUiThread(this::finish);
            }).start();
        }
    }

    /** adb --ez iconcheck true: every TextLab icon drawn as the phone draws it, on one sheet in the files folder. */
    private void iconCheck() {
        List<Integer> all = new ArrayList<>();
        int[] row = {com.fadcam.R.drawable.ic_tl_look, com.fadcam.R.drawable.ic_tl_plain, com.fadcam.R.drawable.ic_tl_light,
                com.fadcam.R.drawable.ic_tl_still, com.fadcam.R.drawable.ic_tl_sway, com.fadcam.R.drawable.ic_tl_record_tilt,
                com.fadcam.R.drawable.ic_tl_plays, com.fadcam.R.drawable.ic_tl_by_itself, com.fadcam.R.drawable.ic_tl_pinned};
        for (int r : row) all.add(r);
        try {
            JSONArray slots = TextLabRenderer.Assets.load(getAssets()).parts.getJSONArray("slots");
            for (int i = 0; i < slots.length(); i++) all.add(TlIcons.forTab(slots.getString(i)));
        } catch (Exception e) {
            Log.w(TAG, "icon check: slots not read", e);
        }
        int cell = 96, cols = 10, rows = (all.size() + cols - 1) / cols, bad = 0;
        Bitmap b = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        c.drawColor(BLACK);
        for (int i = 0; i < all.size(); i++) {
            try {
                android.graphics.drawable.Drawable d = getDrawable(all.get(i)).mutate();
                d.setTint(ZINC);
                int x = (i % cols) * cell, y = (i / cols) * cell;
                d.setBounds(x + 12, y + 12, x + cell - 12, y + cell - 12);
                d.draw(c);
            } catch (Exception e) {
                bad++;
                Log.w(TAG, "icon check: icon " + i + " did not draw", e);
            }
        }
        File f = new File(getExternalFilesDir(null), "textlab/icons.png");
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (FileOutputStream os = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, os);
        } catch (Exception e) {
            Log.w(TAG, "icon check not saved", e);
        }
        Log.i(TAG, "icon check: " + all.size() + " icons, " + bad + " failed -> " + f);
    }

    private void benchExport(@NonNull String kind, @NonNull Intent in) {
        JSONObject look = current;
        if (look == null) return;
        LookExport.Kind k = "video".equals(kind) ? LookExport.Kind.VIDEO
                : "sheet".equals(kind) ? LookExport.Kind.SHEET : LookExport.Kind.PICTURE;
        LookExport.Job job = new LookExport.Job(look, words.getText().toString(), k, in.getIntExtra("ew", 1920),
                in.getIntExtra("eh", 1080), in.getBooleanExtra("eclear", false), in.getFloatExtra("es", 3f));
        long t0 = SystemClock.uptimeMillis();
        LookExport.start(this, job, new LookExport.Listener() {
            @Override public void progress(float done) {}

            @Override
            public void done(String where, String problem) {
                Log.i(TAG, "bench export " + kind + " " + job.w + "x" + job.h + ": " + (where != null ? where : problem)
                        + " in " + (SystemClock.uptimeMillis() - t0) + " ms");
            }
        });
    }

    private static GradientDrawable pill(int colour) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(999);
        return g;
    }

    /** The built-in looks, from their packs (a held pack stays out; drafts never appear). */
    private void loadLooks() {
        for (TextLookShelf.Item it : TextLookShelf.builtIn(this)) looks.add(it.look);
    }

    /** The chips for {@link #looks}, and their pictures (made once and kept; not during an adb bench run). */
    private void showChips(boolean bench) {
        List<LookChipRow.Chip> list = new ArrayList<>();
        for (JSONObject l : looks) list.add(new LookChipRow.Chip(l.optString("name", l.optString("id"))));
        chips.setChips(list);
        for (int i = 0; i < looks.size() && !bench; i++) {
            final int at = i;
            JSONObject l = looks.get(i);
            TextLabPreviews.get(this).chip(l, l.optString("sample", "TEXT"), f -> chips.setSheet(at, f));
        }
    }

    @Nullable
    private JSONObject readAssetLook(@NonNull String id) {
        try {
            return TextLabRenderer.Assets.look(getAssets(), id);
        } catch (Exception e) {
            return null;
        }
    }

    private int indexOf(@Nullable String id) {
        for (int i = 0; i < looks.size(); i++) if (looks.get(i).optString("id").equals(id)) return i;
        return -1;
    }

    /** Show a look: with these words, or the person's if they typed some, or the look's own. */
    private void pick(@NonNull JSONObject look, @Nullable String withWords) {
        current = look;
        String ws = withWords != null ? withWords
                : wordsTyped ? words.getText().toString() : look.optString("sample", "TEXT");
        if (!ws.equals(words.getText().toString())) {
            words.clearFocus();
            words.setText(ws);
        }
        pending.set(new Object[]{look, ws});
    }

    /** A look saved in the Joy Creator folder (from the Library). */
    private void openFile(@NonNull String rel) {
        JoyFolderHome.io().execute(() -> {
            JSONObject look = null;
            try {
                JoyFolder jf = JoyFolderHome.open(this);
                if (jf != null) look = new JSONObject(new String(jf.readAll(rel), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "look file not read: " + rel, e);
            }
            JSONObject l = look;
            runOnUiThread(() -> {
                if (l == null) {
                    Toast.makeText(this, "That look could not be opened.", Toast.LENGTH_SHORT).show();
                    if (!looks.isEmpty()) pick(looks.get(0), null);
                    return;
                }
                int i = indexOf(l.optString("from"));
                if (i >= 0) chips.choose(i);
                wordsTyped = true;
                pick(l, l.optString("sample", "TEXT"));
            });
        });
    }

    private void askSave() {
        if (current == null) return;
        String ws = words.getText().toString();
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(ws.replace('\n', ' ').trim());
        name.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Save this look")
                .setMessage("It goes to Supplies › Looks with a moving picture. Its name:")
                .setView(name)
                .setPositiveButton("Save", (d, which) -> {
                    String n = name.getText().toString().trim();
                    TextLabPreviews.get(this).save(current, ws, n.isEmpty() ? ws : n, s -> Toast.makeText(this,
                            s.where != null ? "Saved: " + s.where : "Not saved: " + s.problem, Toast.LENGTH_LONG).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The look on the screen, with its words, out as a picture, video or sheet; the live picture rests meanwhile. */
    private void askExport() {
        JSONObject look = current;
        if (look == null) return;
        String ws = words.getText().toString();
        final boolean[] was = {live};
        LookExportDialog.show(this, look, ws.isEmpty() ? look.optString("sample", "TEXT") : ws,
                () -> { was[0] = live; live = false; },
                () -> live = was[0]);
    }

    // ---- the GL thread ------------------------------------------------------------------------------

    private final class Renderer implements GLSurfaceView.Renderer {
        final boolean bench;
        final String benchId;
        final float t, stage, tx, ty;
        final int w, h;
        TextLabRenderer r;
        String error;
        int sw, sh;
        double liveStart = -1;
        double sumMs, sumCpu;
        int frames;
        long lastLabel;
        String note = "";
        String name = "";
        boolean benchDone;
        /** adb --ez gputime true: each step of the frame timed by the GPU; --ei burst N: N frames, then one wait. */
        final boolean gpuTime = getIntent().getBooleanExtra("gputime", false);
        final int burst = Math.max(1, getIntent().getIntExtra("burst", 1));
        TlGpuTimer timer;

        Renderer(boolean bench, String benchId, float t, int w, int h, float stage, float tx, float ty) {
            this.bench = bench;
            this.benchId = benchId;
            this.t = t;
            this.w = w;
            this.h = h;
            this.stage = stage;
            this.tx = tx;
            this.ty = ty;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            try {
                r = new TextLabRenderer(TextLabRenderer.Assets.load(getAssets()), TextLabDebugActivity.this);
                r.setSize(w, h);
                r.tilt = new double[]{tx, ty};
                if (gpuTime) {
                    timer = TlGpuTimer.start();
                    Log.i(TAG, timer != null ? "gpu step timer on" : "no gpu timer on this phone");
                }
                // a new surface after a pause: draw what was showing again
                if (current != null && pending.get() == null) pending.set(new Object[]{current, words.getText().toString()});
            } catch (Exception | Error e) {
                fail(e);
            }
        }

        void fail(Throwable e) {
            error = e.toString();
            Log.e(TAG, "TextLab failed", e);
            runOnUiThread(() -> label.setText("TextLab: " + error));
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            sw = width;
            sh = height;
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (r == null || error != null) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                GLES30.glClearColor(0, 0, 0, 1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                return;
            }
            try {
                Object[] p = pending.getAndSet(null);
                if (p != null) show((JSONObject) p[0], (String) p[1]);
                String nw = pendingWords;
                if (nw != null) {
                    pendingWords = null;
                    r.setText(nw);
                }
                double ms = -1;
                if (live) {
                    double now = SystemClock.elapsedRealtimeNanos() / 1e9;
                    if (liveStart < 0) liveStart = now;
                    long a = SystemClock.elapsedRealtimeNanos();
                    // the still left the look's clock at 1000 = moment t: play on from there
                    TlGpuTimer.on = timer;
                    for (int i = 0; i < burst; i++) r.frame(1000 + (now - liveStart) + i / 60.0);
                    long cpuEnd = SystemClock.elapsedRealtimeNanos();
                    GLES30.glFinish();
                    if (timer != null) timer.collect();
                    TlGpuTimer.on = null;
                    ms = (SystemClock.elapsedRealtimeNanos() - a) / 1e6 / burst;
                    sumMs += ms;
                    sumCpu += (cpuEnd - a) / 1e6 / burst;   // the phone's processor handing the work over, before the GPU finishes
                    frames++;
                }
            } catch (RuntimeException e) {
                fail(e);
                return;
            }
            blit();
            long nowMs = SystemClock.uptimeMillis();
            if (nowMs - lastLabel > 500) {
                lastLabel = nowMs;
                String s = live && frames > 0
                        ? String.format(Locale.ROOT, "%s  %.1f ms a frame (cpu %.1f)", name, sumMs / frames, sumCpu / frames)
                        : name + "  held (tap to play)";
                if (frames > 0 && live && bench) Log.i(TAG, s + " (avg of " + frames + (burst > 1 ? " bursts of " + burst : "") + ")");
                if (frames > 0 && live && bench && timer != null) Log.i(TAG, name + " " + timer.report());
                sumMs = 0;
                sumCpu = 0;
                frames = 0;
                String text = note.isEmpty() ? s : s + "\n" + note;
                runOnUiThread(() -> label.setText(text));
            }
        }

        /** A new look (or words): draw its moment t, and save that still when this is the adb bench's look. */
        void show(@NonNull JSONObject look, @NonNull String ws) {
            r.setLook(look);
            if (!Float.isNaN(stage)) r.stage = stage;
            r.setText(ws);
            name = look.optString("name", look.optString("id"));
            long t1 = SystemClock.elapsedRealtimeNanos();
            r.still(t);
            liveStart = -1;
            if (bench && !benchDone && benchId.equals(look.optString("id"))) {
                benchDone = true;
                GLES30.glFinish();
                long t2 = SystemClock.elapsedRealtimeNanos();
                try {
                    Bitmap bmp = r.readBitmap();
                    File dir = new File(getExternalFilesDir(null), "textlab");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, benchId + "_t" + fmt(t) + ".png");
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    note = String.format(Locale.ROOT, "still %dx%d at t=%s: first still %.0f ms (compiles shaders)",
                            r.width(), r.height(), fmt(t), (t2 - t1) / 1e6);
                    Log.i(TAG, note + " -> " + f.getAbsolutePath());
                    Log.i(TAG, "layout " + r.layoutReport());
                    if (getIntent().getBooleanExtra("clearcheck", false)) {
                        // the see-through outputs against this picture; then the look as it was
                        Log.i(TAG, ClearCheck.run(r, t, dir, benchId));
                        r.setLook(look);
                        if (!Float.isNaN(stage)) r.stage = stage;
                        r.setText(ws);
                        r.still(t);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "still not saved", e);
                }
            }
        }

        /** The finished picture onto the screen, whole, centred. */
        void blit() {
            int pw = r.width(), ph = r.height();
            double k = Math.min((double) sw / pw, (double) sh / ph);
            int dw = (int) Math.round(pw * k), dh = (int) Math.round(ph * k);
            int x0 = (sw - dw) / 2, y0 = (sh - dh) / 2;
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, sw, sh);
            GLES30.glClearColor(0.043f, 0.043f, 0.047f, 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, r.outputFramebuffer());
            GLES30.glBlitFramebuffer(0, 0, pw, ph, x0, y0, x0 + dw, y0 + dh, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }
    }

    private static String fmt(float t) {
        return t == Math.rint(t) ? String.valueOf((int) t) : String.valueOf(t);
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
    }
}
