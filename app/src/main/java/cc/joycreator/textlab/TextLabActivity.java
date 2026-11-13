package cc.joycreator.textlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.vislab.gl.ShaderLib;
import com.fadcam.vislab.room.Ui;

import org.json.JSONObject;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The TextLab room (TEXT-1): a look drawn live on the person's words, every slider of it in tabs, the pieces it is
 * built from, its light (Live with the phone, Still, Sway), its progress (burn, blow away, grow, shatter), a
 * background and a font; Save (the user's copy wins), rename, delete, back to the original; undo; Export; Joybot.
 *
 * <p>Opened from the lobby, the Library (a saved look, {@link #EXTRA_LOOK_FILE}) or a Studio title
 * ({@link #EXTRA_TITLE_LOOK}: the room then hands the edited look back with "Use on the title").</p>
 */
public final class TextLabActivity extends Activity implements TlKnobPanel.Host {

    private static final String TAG = "TextLab";
    /** A look file in the Joy Creator folder to open ("Supplies/Looks/Dust.text.json"). */
    public static final String EXTRA_LOOK_FILE = "lookFile";
    /** A look's shelf key, or a built-in look's id, to open. */
    public static final String EXTRA_LOOK_ID = "lookId";
    /** A Studio title's look (JSON) to edit; the room returns {@link #EXTRA_RESULT_LOOK}. */
    public static final String EXTRA_TITLE_LOOK = "titleLook";
    public static final String EXTRA_WORDS = "words";
    public static final String EXTRA_RESULT_LOOK = "resultLook";

    static final int BLACK = 0xFF0B0B0C, PANEL = 0xFF141417, CONTROL = 0xFF1F1F26, INK = 0xFFD4D4D8,
            DIM = 0xFF8A8A96, CYAN = 0xFF22D3EE;
    private static final int PICK_PICTURE = 41, PICK_FONT = 42;
    private static final String[][] STAGES = {{"", "The look's own"}, {"10", "Clear"}, {"0", "Black"},
            {"1", "Dark stage"}, {"7", "Smoke"}, {"2", "Brick wall"}, {"5", "Far lights"}, {"4", "Dusk grid"},
            {"3", "Sea and sky"}, {"8", "Night window"}, {"6", "Paper"}, {"9", "Cloth"}};

    /** The room that is open now (for Joybot), or null. */
    private static WeakReference<TextLabActivity> open = new WeakReference<>(null);

    private TlParts parts;
    private ImageView joy;
    private TlStage stage;
    private TlKnobPanel panel;
    private LookChipRow chips;
    private EditText wordsBox;
    private TextView nameView, undoBtn, redoBtn, lightLive, lightStill, lightSway, progPlay;
    private TlLightPad pad;
    private LinearLayout progRow;
    private SeekBar progBar;
    private TextView progLabel;
    private final TlUndo undo = new TlUndo();
    private TlTilt tilt;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<TextLookShelf.Item> shelf = new ArrayList<>();
    @Nullable private TextLookShelf.Item editing;
    private JSONObject look;
    private String words = "TEXT";
    private boolean dirty, titleMode;
    /** The look and words as last opened or saved: a change back to them is not a change. */
    private String savedLook = "", savedWords = "";
    @Nullable private String photoNote;
    private String light = TitleLook.SWAY;
    private float stillX, stillY;
    private long t0 = android.os.SystemClock.uptimeMillis();

    @Nullable
    public static TextLabActivity current() {
        return open.get();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        try {
            parts = new TlParts(new JSONObject(ShaderLib.readAll(getAssets().open("textlab/parts.json"))));
        } catch (Exception e) {
            Log.e(TAG, "parts.json not read", e);
            Toast.makeText(this, "TextLab could not open.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        tilt = new TlTilt(this);
        buildScreen();

        Intent in = getIntent();
        titleMode = in.hasExtra(EXTRA_TITLE_LOOK);
        if (in.getStringExtra(EXTRA_WORDS) != null) words = in.getStringExtra(EXTRA_WORDS);
        shelf.addAll(TextLookShelf.builtIn(this));
        showChips();
        String title = in.getStringExtra(EXTRA_TITLE_LOOK);
        if (title != null) {
            try {
                editing = null;
                choose(new JSONObject(title), null, true);
            } catch (Exception e) {
                Log.w(TAG, "title look not read", e);
            }
        }
        String id = in.getStringExtra(EXTRA_LOOK_ID);
        if (look == null && !shelf.isEmpty() && in.getStringExtra(EXTRA_LOOK_FILE) == null) {
            int i = indexOf(id);
            chooseItem(i >= 0 ? i : 0, in.getStringExtra(EXTRA_WORDS) == null);
        }
        loadFolder(in.getStringExtra(EXTRA_LOOK_FILE));
        setLight(TitleLook.SWAY);
        main.post(ticker);
    }

    // ---- the screen ---------------------------------------------------------------------------------

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(6), dp(8), dp(4));
        top.addView(iconButton("arrow_back", "Back", v -> onBackPressed()));
        nameView = new TextView(this);
        nameView.setTextColor(INK);
        nameView.setTextSize(16);
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameView.setPadding(dp(6), 0, dp(6), 0);
        label(nameView, "This look's name: tap to rename your own look");
        nameView.setOnClickListener(v -> askRename());
        top.addView(nameView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        undoBtn = iconButton("undo", "Undo", v -> doUndo());
        redoBtn = iconButton("redo", "Redo", v -> doRedo());
        top.addView(undoBtn);
        top.addView(redoBtn);
        top.addView(iconButton("save", "Save this look", v -> save(null)));
        ImageView export = new ImageView(this);
        export.setImageResource(R.drawable.ic_export_studio);
        export.setPadding(dp(8), dp(8), dp(8), dp(8));
        label(export, "Export: a picture, a video or a moving sheet");
        export.setOnClickListener(v -> askExport());
        top.addView(export, new LinearLayout.LayoutParams(dp(40), dp(40)));
        top.addView(iconButton("more_vert", "More: background, font, save as, rename, delete", v -> more()));
        joy = new ImageView(this);
        joy.setImageResource(R.drawable.joybot_neutral);
        joy.setBackgroundResource(R.drawable.joybot_orb);
        joy.setImageTintList(android.content.res.ColorStateList.valueOf(INK));
        int jp = dp(7);
        joy.setPadding(jp, jp, jp, jp);
        label(joy, "Joybot, your helper: ask him to change this look");
        joy.setOnClickListener(v -> {
            startActivity(new Intent(this, com.fadcam.ui.faditor.ai.ChatAssistantActivity.class)
                    .putExtra(com.fadcam.ui.faditor.ai.ChatAssistantActivity.EXTRA_ROOM, "TEXTLAB"));
            com.fadcam.ui.faditor.ai.joybot.JoybotPulse.breathe(v, false);
        });
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        jlp.setMarginStart(dp(6));
        top.addView(joy, jlp);
        root.addView(top);

        FrameLayout stageBox = new FrameLayout(this);
        stage = new TlStage(this);
        stage.setTrouble(why -> Toast.makeText(this, why, Toast.LENGTH_LONG).show());
        stage.setRestore(() -> {
            if (look == null) return;
            stage.show(look, words.isEmpty() ? " " : words);
            if (photo != null && look.optInt("stage", 1) == 11) stage.run(r -> r.setPhoto(photo));
        });
        stage.setOnClickListener(v -> {
            stage.setHeld(!stage.held());
            Toast.makeText(this, stage.held() ? "Held: tap the picture to play" : "Playing", Toast.LENGTH_SHORT).show();
        });
        label(stage, "The look: tap to hold or play");
        stageBox.addView(stage);
        int w = getResources().getDisplayMetrics().widthPixels;
        root.addView(stageBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(w * 9 / 16, dp(260))));

        chips = new LookChipRow(this);
        chips.setPadding(dp(8), dp(6), dp(8), dp(2));
        chips.setOnPick(this::askThenChoose);
        root.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout wordsRow = row();
        wordsBox = new EditText(this);
        wordsBox.setSingleLine(true);
        wordsBox.setTextColor(INK);
        wordsBox.setHintTextColor(DIM);
        wordsBox.setHint("Your words");
        wordsBox.setTextSize(15);
        wordsBox.setPadding(dp(12), dp(6), dp(12), dp(6));
        wordsBox.setBackground(round(CONTROL));
        wordsBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!wordsBox.hasFocus()) return;
                words = s.toString();
                changed();
                final String ws = words.isEmpty() ? " " : words;
                stage.run(r -> r.setText(ws));
            }
        });
        wordsRow.addView(wordsBox, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView replay = iconButton("replay", "Play the arrival again", v -> {
            stage.setHeld(false);
            stage.replay();
        });
        wordsRow.addView(replay);
        root.addView(wordsRow);

        LinearLayout lightRow = row();
        lightRow.addView(small("Light", R.drawable.ic_tl_light));
        lightLive = pill("Live", "The light follows the phone as you tilt it", R.drawable.ic_tl_record_tilt);
        lightStill = pill("Still", "The light stays where you put it (drag the little pad)", R.drawable.ic_tl_still);
        lightSway = pill("Sway", "The light drifts gently round where you put it", R.drawable.ic_tl_sway);
        lightLive.setOnClickListener(v -> setLight("live"));
        lightStill.setOnClickListener(v -> setLight(TitleLook.STILL));
        lightSway.setOnClickListener(v -> setLight(TitleLook.SWAY));
        lightRow.addView(lightLive);
        lightRow.addView(lightStill);
        lightRow.addView(lightSway);
        pad = new TlLightPad(this);
        pad.onMoved = (x, y, done) -> {
            stillX = x;
            stillY = y;
            if ("live".equals(light)) setLight(TitleLook.STILL);
        };
        lightRow.addView(pad, new LinearLayout.LayoutParams(dp(56), dp(34)));
        root.addView(lightRow);

        progRow = row();
        progLabel = small("Progress", R.drawable.ic_tl_plays);
        progRow.addView(progLabel);
        progPlay = pill("Plays", "It plays by itself (burns, blows away, grows or breaks from the start)", R.drawable.ic_tl_by_itself);
        progPlay.setOnClickListener(v -> playProgress());
        progRow.addView(progPlay);
        progBar = new SeekBar(this);
        progBar.setMax(1000);
        progBar.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
        progBar.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN));
        label(progBar, "Drag to choose the moment (the look holds there)");
        progBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) holdProgress(v / 1000.0);
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}

            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        progRow.addView(progBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(progRow);

        panel = new TlKnobPanel(this, parts, this);
        panel.setBackgroundColor(PANEL);
        root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(3), dp(8), dp(3));
        return r;
    }

    private static GradientDrawable round(int c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(999);
        return g;
    }

    private void label(View v, String s) {
        v.setContentDescription(s);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.setTooltipText(s);
    }

    private TextView iconButton(String ligature, String tip, View.OnClickListener click) {
        TextView t = Ui.icon(this, ligature, 22, INK);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        label(t, tip);
        t.setOnClickListener(click);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return t;
    }

    private TextView small(String s, int icon) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(12);
        t.setPadding(0, 0, dp(8), 0);
        tint(t, icon, DIM);
        return t;
    }

    private TextView pill(String text, String tip, int icon) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(12), 0);
        t.setTag(icon);
        label(t, tip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.setMarginEnd(dp(6));
        t.setLayoutParams(lp);
        paint(t, false);
        return t;
    }

    /** ON = solid cyan, the words and icon cut out; off = a zinc pill. */
    private void paint(TextView t, boolean on) {
        t.setBackground(round(on ? CYAN : CONTROL));
        t.setTextColor(on ? BLACK : INK);
        if (t.getTag() instanceof Integer) tint(t, (Integer) t.getTag(), on ? BLACK : INK);
        t.setSelected(on);
    }

    private void tint(TextView t, int icon, int colour) {
        android.graphics.drawable.Drawable d = getDrawable(icon);
        if (d == null) return;
        d = d.mutate();
        d.setTint(colour);
        d.setBounds(0, 0, dp(16), dp(16));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(dp(5));
    }

    // ---- the shelf ----------------------------------------------------------------------------------

    private void loadFolder(@Nullable String openRel) {
        loadFolder(openRel, true);
    }

    /**
     * Read the shelf again. {@code showTheirs}: when the look on screen turns out to have a copy of the person's own
     * (and nothing is changed yet), show their copy; after a save it is already what is on screen.
     */
    private void loadFolder(@Nullable String openRel, boolean showTheirs) {
        JoyFolderHome.io().execute(() -> {
            List<TextLookShelf.Item> more = TextLookShelf.inFolder(this);
            List<TextLookShelf.Item> builtIn = TextLookShelf.builtIn(this);
            List<TextLookShelf.Item> all = TlLookFiles.merged(builtIn, more);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                String keep = editing != null ? editing.key : null;
                shelf.clear();
                shelf.addAll(all);
                showChips();
                if (openRel != null) {
                    int i = indexOf("folder:" + openRel);
                    if (i >= 0) chooseItem(i, true);
                    else Toast.makeText(this, "That look could not be opened.", Toast.LENGTH_SHORT).show();
                } else if (keep != null) {
                    // the built-in being shown may have a copy of the person's own: show theirs
                    int i = indexOf(keep);
                    if (i < 0 && editing != null && editing.from == TextLookShelf.From.BUILT_IN) i = indexOfReplacing(editing.look.optString("id"));
                    if (i >= 0) {
                        if (showTheirs && !dirty && !shelf.get(i).key.equals(keep)) {
                            chooseItem(i, false);   // the person's own copy of the look that was showing
                        } else {
                            // the same look, now the person's own copy: keep editing it (and its undo steps)
                            editing = shelf.get(i);
                            chips.choose(i);
                            showName();
                        }
                    }
                }
            });
        });
    }

    private void showChips() {
        List<LookChipRow.Chip> list = new ArrayList<>();
        for (TextLookShelf.Item it : shelf) list.add(new LookChipRow.Chip(it.name));
        chips.setChips(list);
        for (int i = 0; i < shelf.size(); i++) {
            final int at = i;
            JSONObject l = shelf.get(i).look;
            TextLabPreviews.get(this).chip(l, l.optString("sample", "TEXT"), f -> chips.setSheet(at, f));
        }
        int i = editing != null ? indexOf(editing.key) : -1;
        if (i >= 0) chips.choose(i);
    }

    private int indexOf(@Nullable String keyOrId) {
        if (keyOrId == null) return -1;
        for (int i = 0; i < shelf.size(); i++) {
            TextLookShelf.Item it = shelf.get(i);
            if (it.key.equals(keyOrId) || it.look.optString("id").equals(keyOrId) || it.name.equalsIgnoreCase(keyOrId)) return i;
        }
        return -1;
    }

    private int indexOfReplacing(@NonNull String id) {
        for (int i = 0; i < shelf.size(); i++) {
            if (id.equals(shelf.get(i).look.optString(TlLookFiles.REPLACES))) return i;
        }
        return -1;
    }

    /** A chip was tapped: with unsaved changes, ask first. */
    private void askThenChoose(int i) {
        if (!dirty || titleMode) {
            chooseItem(i, !wordsTyped());
            return;
        }
        TlSheet.ask(this, "Save your changes?", "You changed " + nameOf() + ". Save it before opening another look?",
                "Save", () -> save(() -> chooseItem(i, !wordsTyped())), "Don't save", () -> chooseItem(i, !wordsTyped()));
        if (editing != null) chips.choose(indexOf(editing.key));
    }

    private boolean wordsTyped() {
        return getIntent().getStringExtra(EXTRA_WORDS) != null || wordsBox.length() > 0 && !words.equals(look != null ? look.optString("sample") : "");
    }

    private void chooseItem(int i, boolean theirWords) {
        if (i < 0 || i >= shelf.size()) return;
        editing = shelf.get(i);
        chips.choose(i);
        choose(editing.look, theirWords ? editing.look.optString("sample", "TEXT") : null, true);
    }

    /** Show a look (a copy: the shelf's stays as it was). */
    private void choose(@NonNull JSONObject l, @Nullable String newWords, boolean fresh) {
        try {
            look = new JSONObject(l.toString());
        } catch (Exception e) {
            return;
        }
        if (newWords != null) words = newWords;
        wordsBox.clearFocus();
        if (!wordsBox.getText().toString().equals(words)) wordsBox.setText(words);
        if (fresh) {
            undo.clear();
            savedLook = look.toString();
            savedWords = words;
        }
        changed();
        stage.show(look, words.isEmpty() ? " " : words);
        if (photo != null && look.optInt("stage", 1) == 11) stage.run(r -> r.setPhoto(photo));
        panel.refresh();
        showProgress();
        showName();
        showUndo();
    }

    private final Runnable heroSnap = () -> {
        if (isFinishing() || titleMode || look == null) return;
        final String name = nameOf(), key = editing != null ? editing.key : "";
        stage.still(b -> {
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() / 2), Math.max(1, b.getHeight() / 2), true);
            if (small != b) b.recycle();
            TlLobbyHero.save(this, small, name, key);
        });
    };

    private void showName() {
        nameView.setText(nameOf() + (dirty ? " •" : ""));
        // the lobby's hero: a still of the look as it is now, once it has settled (any change re-arms it)
        main.removeCallbacks(heroSnap);
        main.postDelayed(heroSnap, 2000);
    }

    @NonNull
    private String nameOf() {
        if (titleMode) return "Title look: " + look.optString("name", "look");
        return editing != null ? editing.name : look != null ? look.optString("name", "New look") : "New look";
    }

    // ---- changes and undo ---------------------------------------------------------------------------

    @NonNull
    @Override
    public JSONObject look() {
        return look;
    }

    /** Joybot's request in progress: its changes share one undo step (TextLabRoom). */
    private boolean jbGrouped;

    void joybotStep(boolean remember, boolean grouped) {
        jbGrouped = grouped;
        if (remember && look != null) undo.before(look.toString(), words, "Joybot's change");
    }

    @Override
    public void beginChange(@NonNull String what) {
        if (!jbGrouped) undo.before(look.toString(), words, what);
        dirty = true;
        showName();
        showUndo();
    }

    @Override
    public void settled() {
        changed();
    }

    /** Whether there is anything to save: the look or words differ from how they were opened or saved. */
    private void changed() {
        dirty = look != null && !(look.toString().equals(savedLook) && words.equals(savedWords));
        showName();
        showUndo();
    }

    @Override
    public void knob(@NonNull String name, @NonNull Object value) {
        try {
            JSONObject p = look.optJSONObject("p");
            if (p == null) look.put("p", p = new JSONObject());
            p.put(name, value);
        } catch (Exception e) {
            return;
        }
        stage.run(r -> r.setKnob(name, value));
    }

    @Override
    public void pieces() {
        TlPiecesDialog.show(this, parts, TlParts.features(look), (feature, on) -> {
            beginChange(on ? "Add a piece" : "Take a piece away");
            try {
                JSONObject next = TlParts.withFeature(look, feature, on);
                look = next;
                stage.show(look, words.isEmpty() ? " " : words);
                panel.refresh();
                showProgress();
            } catch (Exception e) {
                Log.w(TAG, "piece not changed", e);
            }
        });
    }

    private void doUndo() {
        TlUndo.Step s = undo.undo(look.toString(), words);
        if (s != null) restore(s);
    }

    private void doRedo() {
        TlUndo.Step s = undo.redo(look.toString(), words);
        if (s != null) restore(s);
    }

    private void restore(TlUndo.Step s) {
        try {
            choose(new JSONObject(s.look), s.words, false);
        } catch (Exception e) {
            Log.w(TAG, "undo step not read", e);
        }
    }

    private void showUndo() {
        undoBtn.setAlpha(undo.canUndo() ? 1f : 0.35f);
        redoBtn.setAlpha(undo.canRedo() ? 1f : 0.35f);
        label(undoBtn, undo.canUndo() ? "Undo: " + undo.undoWhat() : "Nothing to undo");
        label(redoBtn, undo.canRedo() ? "Redo: " + undo.redoWhat() : "Nothing to redo");
    }

    // ---- light and progress -------------------------------------------------------------------------

    private void setLight(String mode) {
        light = mode;
        paint(lightLive, "live".equals(mode));
        paint(lightStill, TitleLook.STILL.equals(mode));
        paint(lightSway, TitleLook.SWAY.equals(mode));
        if ("live".equals(mode)) {
            if (!tilt.available()) {
                Toast.makeText(this, "This phone has no tilt sensor: Still and Sway only.", Toast.LENGTH_SHORT).show();
                setLight(TitleLook.STILL);
                return;
            }
            tilt.start((x, y) -> {
                stage.setTilt(x, y);
                pad.set(x, y);
            });
        } else {
            tilt.stop();
            pad.set(stillX, stillY);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (TitleLook.SWAY.equals(light)) {
                double s = (android.os.SystemClock.uptimeMillis() - t0) / 1000.0;
                stage.setTilt(clamp(stillX + 0.4 * Math.sin(1.1 * s)), clamp(stillY + 0.28 * Math.sin(0.8 * s + 1)));
            } else if (TitleLook.STILL.equals(light)) {
                stage.setTilt(stillX, stillY);
            }
            main.postDelayed(this, 33);
        }
    };

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    private void showProgress() {
        String[] p = TlParts.progress(look);
        progRow.setVisibility(p == null ? View.GONE : View.VISIBLE);
        if (p == null) return;
        progLabel.setText(p[2]);
        progBar.setProgress(0);
        paint(progPlay, true);
    }

    /** Hold the look at a moment of its burn / break (shown only: the look's own values are unchanged). */
    private void holdProgress(double v) {
        String[] p = TlParts.progress(look);
        if (p == null) return;
        double gone = TlParts.progressValue(p[1], v);
        stage.run(r -> {
            r.setKnob(p[0], 0.0);
            r.setKnob(p[1], gone);
        });
        paint(progPlay, false);
    }

    private void playProgress() {
        String[] p = TlParts.progress(look);
        if (p == null) return;
        JSONObject k = look.optJSONObject("p");
        double auto = k != null && k.has(p[0]) ? k.optDouble(p[0], 1) : 1, gone = k != null ? k.optDouble(p[1], 0) : 0;
        stage.run(r -> {
            r.setKnob(p[0], auto);
            r.setKnob(p[1], gone);
            r.still(0);
        });
        progBar.setProgress(0);
        paint(progPlay, true);
    }

    // ---- saving -------------------------------------------------------------------------------------

    /** Save over the look being edited (or as a new one); {@code then} runs after a good save. */
    private void save(@Nullable Runnable then) {
        if (look == null) return;
        if (titleMode) {
            useOnTitle();
            return;
        }
        if (editing == null || editing.from == TextLookShelf.From.PACK) {
            saveAs(then);
            return;
        }
        final TextLookShelf.Item it = editing;
        final JSONObject l = look;
        final String ws = words;
        JoyFolderHome.io().execute(() -> {
            TlLookFiles.Done d = TlLookFiles.save(this, it, l, ws);
            runOnUiThread(() -> afterSave(d, then));
        });
    }

    private void saveAs(@Nullable Runnable then) {
        TlSheet.name(this, "Save as a new look", (editing != null ? editing.name : "My look") + " 2", "Save", n ->
                TextLabPreviews.get(this).save(look, words, n, s -> afterSave(new TlLookFiles.Done(
                        s.where != null && s.where.startsWith("Supplies") ? s.where : null,
                        s.where == null ? s.problem : null), then)));
    }

    private void afterSave(TlLookFiles.Done d, @Nullable Runnable then) {
        if (!d.ok()) {
            Toast.makeText(this, d.problem, Toast.LENGTH_LONG).show();
            return;
        }
        savedLook = look.toString();
        savedWords = words;
        changed();
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        if (d.rel != null) loadFolder(null, false);
        if (d.rel != null && then == null) {
            final String rel = d.rel;
            main.postDelayed(() -> {
                int i = indexOf("folder:" + rel);
                if (i >= 0) {
                    editing = shelf.get(i);
                    chips.choose(i);
                    showName();
                }
            }, 600);
        }
        if (then != null) then.run();
    }

    /** Studio round trip: hand the edited look back to the title. */
    private void useOnTitle() {
        try {
            JSONObject out = new JSONObject(look.toString());
            out.put("sample", words);
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_LOOK, out.toString()));
            if (!TlTitleEdit.deliver(out.toString())) {
                Toast.makeText(this, "Studio was closed, so the look couldn't go back to the title. Save it as a look instead.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception e) {
            return;
        }
        dirty = false;
        finish();
    }

    private void askRename() {
        if (titleMode) return;
        if (editing == null || editing.from != TextLookShelf.From.MINE) {
            Toast.makeText(this, "Save it as your own look first; then it can be renamed.", Toast.LENGTH_SHORT).show();
            return;
        }
        final TextLookShelf.Item it = editing;
        TlSheet.name(this, "Rename this look", it.name, "Rename", n -> JoyFolderHome.io().execute(() -> {
            TlLookFiles.Done d = TlLookFiles.rename(this, it, n);
            runOnUiThread(() -> {
                if (!d.ok()) Toast.makeText(this, d.problem, Toast.LENGTH_LONG).show();
                else {
                    editing = null;
                    loadFolder(d.rel);
                }
            });
        }));
    }

    private void askDelete() {
        if (editing == null || editing.from != TextLookShelf.From.MINE) return;
        final TextLookShelf.Item it = editing;
        TlSheet.ask(this, "Delete " + it.name + "?", "It goes to the Joy Creator folder's Trash for 30 days.",
                "Delete", () -> JoyFolderHome.io().execute(() -> {
                    TlLookFiles.Done d = TlLookFiles.delete(this, it);
                    runOnUiThread(() -> {
                        if (!d.ok()) Toast.makeText(this, d.problem, Toast.LENGTH_LONG).show();
                        else afterRemoved(it);
                    });
                }), null, null);
    }

    private void askRevert() {
        if (editing == null || !TlLookFiles.isCopyOfBuiltIn(editing)) return;
        final TextLookShelf.Item it = editing;
        TlSheet.ask(this, "Back to the original?", "Your changes to " + it.name + " go to the Trash (30 days) and the "
                + "original look comes back.", "Go back", () -> JoyFolderHome.io().execute(() -> {
                    TlLookFiles.Done d = TlLookFiles.revert(this, it);
                    runOnUiThread(() -> {
                        if (!d.ok()) Toast.makeText(this, d.problem, Toast.LENGTH_LONG).show();
                        else afterRemoved(it);
                    });
                }), null, null);
    }

    private void afterRemoved(TextLookShelf.Item it) {
        dirty = false;
        String id = it.look.optString(TlLookFiles.REPLACES);
        editing = null;
        JoyFolderHome.io().execute(() -> {
            List<TextLookShelf.Item> all = TlLookFiles.merged(TextLookShelf.builtIn(this), TextLookShelf.inFolder(this));
            runOnUiThread(() -> {
                shelf.clear();
                shelf.addAll(all);
                showChips();
                int i = indexOf(id.isEmpty() ? null : id);
                chooseItem(i >= 0 ? i : 0, false);
            });
        });
    }

    // ---- more ---------------------------------------------------------------------------------------

    private void more() {
        List<TlSheet.Row> rows = TlSheet.rows();
        rows.add(new TlSheet.Row("Background…", "What is behind the words", false, this::pickStage));
        rows.add(new TlSheet.Row("Font…", "Which typeface the words are set in", false, this::pickFont));
        rows.add(new TlSheet.Row("Your picture behind…", "Put one of your pictures behind the words (not saved in the look)",
                false, () -> startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE), PICK_PICTURE)));
        if (!titleMode) {
            rows.add(new TlSheet.Row("Save as a new look…", "Keep this as a new look of your own", false, () -> saveAs(null)));
            if (editing != null && editing.from == TextLookShelf.From.MINE) {
                rows.add(new TlSheet.Row("Rename…", "Give your look a new name", false, this::askRename));
                if (TlLookFiles.isCopyOfBuiltIn(editing)) {
                    rows.add(new TlSheet.Row("Back to the original", "Forget your changes to this built-in look", false, this::askRevert));
                }
                rows.add(new TlSheet.Row("Delete", "Your look to the Trash", false, this::askDelete));
            }
        } else {
            rows.add(new TlSheet.Row("Use on the title", "Back to Studio with this look on the title", true, this::useOnTitle));
        }
        TlSheet.menu(this, nameOf(), rows);
    }

    private void pickStage() {
        List<TlSheet.Row> rows = TlSheet.rows();
        int cur = look.optInt("stage", 1);
        for (String[] s : STAGES) {
            boolean on = !s[0].isEmpty() && Integer.parseInt(s[0]) == cur;
            rows.add(new TlSheet.Row(s[1], "Behind the words: " + s[1], on, () -> setStage(s[0])));
        }
        if (photo != null) rows.add(new TlSheet.Row("Your picture", "Your picture behind the words", cur == 11, () -> setStage("11")));
        TlSheet.menu(this, "Background", rows);
    }

    private void setStage(String v) {
        beginChange("Background");
        try {
            if (v.isEmpty()) {
                JSONObject orig = editing != null ? editing.look : look;
                look.put("stage", orig.optInt("stage", 1));
            } else {
                look.put("stage", Integer.parseInt(v));
            }
        } catch (Exception e) {
            return;
        }
        final double s = look.optDouble("stage", 1);
        stage.run(r -> r.stage = s);
    }

    private void pickFont() {
        JSONObject f = look.optJSONObject("font");
        String fam = f != null ? f.optString("family", "archivo") : "archivo";
        if ("tube".equals(fam)) {
            Toast.makeText(this, "This look's letters are drawn as glass tubes: it has no font to change.", Toast.LENGTH_LONG).show();
            return;
        }
        List<TlSheet.Row> rows = TlSheet.rows();
        for (String[] c : TlFonts.CHOICES) {
            rows.add(new TlSheet.Row(c[1], "Set the words in " + c[1], c[0].equals(fam), () -> setFont(c[0])));
        }
        JoyFolderHome.io().execute(() -> {
            List<String> own = folderFonts();
            runOnUiThread(() -> {
                for (String rel : own) {
                    String n = FileKind.baseOf(Placement.nameOf(rel));
                    rows.add(new TlSheet.Row(n, "Your font " + n, (TlFonts.FILE + rel).equals(fam), () -> setFont(TlFonts.FILE + rel)));
                }
                rows.add(new TlSheet.Row("Add a font file…", "A .ttf or .otf font of yours: it goes to Supplies › Fonts", false,
                        () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE), PICK_FONT)));
                TlSheet.menu(this, "Font", rows);
            });
        });
    }

    @NonNull
    private List<String> folderFonts() {
        List<String> out = new ArrayList<>();
        try {
            JoyFolder jf = JoyFolderHome.open(this);
            String dir = "Supplies/" + FileKind.FONT.suppliesFolder;
            if (jf != null && jf.fs.stat(dir) != null) {
                for (JoyFs.Entry e : jf.fs.list(dir)) {
                    String n = e.name.toLowerCase(Locale.ROOT);
                    if (!e.dir && (n.endsWith(".ttf") || n.endsWith(".otf"))) out.add(Placement.join(dir, e.name));
                }
            }
            // fonts that came in Joy packs (their "font" socket)
            if (jf != null) {
                for (com.fadcam.joyfolder.PackShelf.Found f : com.fadcam.joyfolder.PackShelf.installed(jf.fs, "font")) {
                    out.add(f.rel);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fonts not listed", e);
        }
        return out;
    }

    private void setFont(String family) {
        beginChange("Font");
        try {
            JSONObject f = look.optJSONObject("font");
            if (f == null) look.put("font", f = new JSONObject());
            f.put("family", family);
        } catch (Exception e) {
            return;
        }
        stage.show(look, words.isEmpty() ? " " : words);
    }

    @Nullable private Bitmap photo;

    @Override
    protected void onActivityResult(int request, int result, @Nullable Intent data) {
        super.onActivityResult(request, result, data);
        Uri uri = data != null ? data.getData() : null;
        if (result != RESULT_OK || uri == null) return;
        if (request == PICK_PICTURE) {
            JoyFolderHome.io().execute(() -> {
                Bitmap b = decode(uri);
                runOnUiThread(() -> {
                    if (b == null) {
                        Toast.makeText(this, "That picture could not be read.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    photo = b;
                    stage.run(r -> r.setPhoto(b));
                    setStage("11");
                });
            });
        } else if (request == PICK_FONT) {
            JoyFolderHome.io().execute(() -> {
                String rel = null, why = null;
                try {
                    String name = displayName(uri);
                    String low = name.toLowerCase(Locale.ROOT);
                    if (!low.endsWith(".ttf") && !low.endsWith(".otf")) throw new IllegalArgumentException("not a font");
                    JoyFolder jf = JoyFolderHome.open(this);
                    if (jf == null) throw new IllegalStateException("the Joy Creator folder could not be reached");
                    String dir = jf.prepareDir(Placement.What.SUPPLY, null, null, FileKind.FONT);
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IllegalStateException("not readable");
                        rel = jf.addFile(dir, name, in, "import");
                    }
                    jf.saveIndex();
                } catch (IllegalArgumentException e) {
                    why = "That isn't a font file (a .ttf or .otf).";
                } catch (Exception e) {
                    Log.w(TAG, "font not added", e);
                    why = "That font could not be added.";
                }
                String r = rel, w = why;
                runOnUiThread(() -> {
                    if (r != null) setFont(TlFonts.FILE + r);
                    else Toast.makeText(this, w, Toast.LENGTH_LONG).show();
                });
            });
        }
    }

    @NonNull
    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        String p = uri.getLastPathSegment();
        return p != null ? p : "font.ttf";
    }

    @Nullable
    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > 2048) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---- export, leaving ----------------------------------------------------------------------------

    private void askExport() {
        if (look == null) return;
        final boolean[] was = {false};
        LookExportDialog.show(this, look, words.isEmpty() ? look.optString("sample", "TEXT") : words,
                () -> {
                    was[0] = stage.held();
                    stage.setHeld(true);
                },
                () -> stage.setHeld(was[0]));
    }

    @Override
    public void onBackPressed() {
        if (!dirty) {
            super.onBackPressed();
            return;
        }
        if (titleMode) {
            TlSheet.ask(this, "Use your changes on the title?", "You changed this title's look here.", "Use them",
                    this::useOnTitle, "Don't", () -> {
                        dirty = false;
                        finish();
                    });
            return;
        }
        TlSheet.ask(this, "Save your changes?", "You changed " + nameOf() + ".", "Save", () -> save(this::finish),
                "Don't save", () -> {
                    dirty = false;
                    finish();
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        open = new WeakReference<>(this);
        stage.onResume();
        com.fadcam.ui.faditor.ai.joybot.JoybotPulse.lightButton(joy, null, com.fadcam.ui.faditor.ai.joybot.IdeaEngine.Room.TEXTLAB);
        if ("live".equals(light)) setLight("live");
    }

    @Override
    protected void onPause() {
        super.onPause();
        stage.onPause();
        tilt.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (titleMode && isFinishing()) TlTitleEdit.cancel();
        main.removeCallbacksAndMessages(null);
        if (open.get() == this) open = new WeakReference<>(null);
    }

    // ---- for Joybot (TextLabRoom) -------------------------------------------------------------------

    /** The looks on the shelf, by name. */
    @NonNull
    List<String> lookNames() {
        List<String> out = new ArrayList<>();
        for (TextLookShelf.Item it : shelf) out.add(it.name);
        return out;
    }

    @NonNull
    String lookName() {
        return nameOf();
    }

    @NonNull
    String words() {
        return words;
    }

    boolean chooseByName(@NonNull String name) {
        int i = indexOf(name);
        if (i < 0) {
            for (int k = 0; k < shelf.size(); k++) {
                if (shelf.get(k).name.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) { i = k; break; }
            }
        }
        if (i < 0) return false;
        chooseItem(i, false);
        return true;
    }

    void setWordsFromJoybot(@NonNull String w) {
        beginChange("Words");
        words = w;
        wordsBox.setText(w);
        stage.run(r -> r.setText(w.isEmpty() ? " " : w));
    }

    /** Joybot turns a slider by its name or its label; returns its label, or null. */
    @Nullable
    String setKnobFromJoybot(@NonNull String knob, @NonNull Object value) {
        TlParts.Knob k = parts.knobs.get(knob);
        if (k == null) {
            for (TlParts.Knob c : parts.knobs.values()) if (c.label.equalsIgnoreCase(knob)) { k = c; break; }
        }
        if (k == null) return null;
        Object v = value;
        if (!k.colour) {
            double d;
            try {
                d = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException e) {
                return null;
            }
            v = Math.max(k.min, Math.min(k.max, d));
        }
        beginChange(k.label);
        knob(k.name, v);
        panel.refresh();
        return k.label;
    }

    /** Joybot adds or takes away a piece by its name ("Fire", "Rust", "Burn"). */
    @Nullable
    String setPieceFromJoybot(@NonNull String piece, boolean on) {
        for (TlParts.Piece p : parts.pieces) {
            if (p.slot.equalsIgnoreCase(piece) || p.feature.equalsIgnoreCase(piece)) {
                beginChange(on ? "Add a piece" : "Take a piece away");
                try {
                    look = TlParts.withFeature(look, p.feature, on);
                } catch (Exception e) {
                    return null;
                }
                stage.show(look, words.isEmpty() ? " " : words);
                panel.refresh();
                showProgress();
                return p.slot;
            }
        }
        return null;
    }

    /** The sliders of one group, "label = value" (for Joybot to read). */
    @NonNull
    String describe() {
        StringBuilder b = new StringBuilder();
        b.append("Look: ").append(nameOf()).append("; words: ").append(words).append("; pieces: ");
        b.append(TlParts.features(look)).append("; slider groups: ").append(parts.slotsFor(look)).append('.');
        JSONObject p = look.optJSONObject("p");
        if (p != null) {
            b.append(" Changed sliders: ");
            int n = 0;
            for (java.util.Iterator<String> it = p.keys(); it.hasNext() && n < 40; n++) {
                String k = it.next();
                TlParts.Knob kn = parts.knobs.get(k);
                b.append(kn != null ? kn.label + " (" + k + ")" : k).append(" = ").append(p.opt(k)).append("; ");
            }
        }
        return b.toString();
    }

    @NonNull
    TlParts parts() {
        return parts;
    }

    @NonNull
    String saveFromJoybot(@Nullable String asNew) {
        if (titleMode) {
            useOnTitle();
            return "Put the look on the title and went back to Studio.";
        }
        if (asNew != null && !asNew.trim().isEmpty()) {
            TextLabPreviews.get(this).save(look, words, asNew.trim(), sv -> afterSave(new TlLookFiles.Done(
                    sv.where != null && sv.where.startsWith("Supplies") ? sv.where : null, sv.where == null ? sv.problem : null), null));
            return "Saving it as a new look, “" + asNew.trim() + "”, in Supplies › Looks.";
        }
        if (editing == null || editing.from == TextLookShelf.From.PACK) {
            String n = (editing != null ? editing.name : look.optString("name", "My look")) + " (mine)";
            return saveFromJoybot(n);
        }
        save(null);
        return editing.from == TextLookShelf.From.BUILT_IN
                ? "Saved as your own copy of " + editing.name + " (it takes the built-in's place; “Back to the original” undoes that)."
                : "Saved " + editing.name + ".";
    }

    /** Joybot picks a font by name ("Bebas", "Cinzel"…). */
    @NonNull
    String setFontFromJoybot(@NonNull String name) {
        for (String[] c : TlFonts.CHOICES) {
            if (c[0].equalsIgnoreCase(name.trim()) || c[1].toLowerCase(Locale.ROOT).startsWith(name.trim().toLowerCase(Locale.ROOT))) {
                JSONObject f = look.optJSONObject("font");
                if (f != null && "tube".equals(f.optString("family"))) return "This look's letters are glass tubes: it has no font.";
                setFont(c[0]);
                return "Font: " + c[1];
            }
        }
        StringBuilder all = new StringBuilder();
        for (String[] c : TlFonts.CHOICES) all.append(c[1]).append(", ");
        return "No font called " + name + ". Fonts: " + all.substring(0, all.length() - 2) + ".";
    }

    /** Joybot picks what is behind the words by name ("smoke", "brick wall", "clear"…). */
    @NonNull
    String setStageFromJoybot(@NonNull String name) {
        for (String[] st : STAGES) {
            if (!st[0].isEmpty() && st[1].toLowerCase(Locale.ROOT).contains(name.trim().toLowerCase(Locale.ROOT))) {
                setStage(st[0]);
                return "Background: " + st[1];
            }
        }
        return "No background called " + name + ". Backgrounds: clear, black, dark stage, smoke, brick wall, far lights, dusk grid, sea and sky, night window, paper, cloth.";
    }
}
