package cc.joycreator.avatar.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import cc.joycreator.avatar.scene.EmptyShots;
import cc.joycreator.avatar.scene.FramePlan;
import cc.joycreator.avatar.scene.RoomPrefs;
import cc.joycreator.avatar.scene.SceneGeometry;
import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The frame, the director's chair and the strip, over the stage in Perform (the pilot's {@code al-frame.js} and
 * {@code al-scene-strip.js}). Two quiet grey round buttons sit at the bottom right: the FRAME (its shape in brackets; tap on or off,
 * hold for the shapes and margins) and the director's CHAIR (opens into Scene, Character and Shots). The STRIP shows whichever are on
 * and shares its whole width between them; each part scrolls by itself and a card is chosen by a tap and set up by a long press or a
 * second tap. The frame is only a shape; round it is Video Studio's own canvas texture; the strip pushes it aside the way Studio's
 * transcript panel pushes its picture, and never makes it smaller ({@link FramePlan}).
 *
 * <p>This is the empty scene (the plain stage, one character) in Perform; scenes proper plug into the same parts.</p>
 */
public final class SceneUi extends FrameLayout {

    /** What the room tells the frame and the strip. */
    public interface Host {
        boolean performing();

        String characterName();

        /** A stable key for the character (its file name), so kept shots stay with it. */
        String characterKey();

        Bitmap characterPic();

        /** The character's drawing at rest, in character pixels {x0, y0, x1, y1}, or null. */
        float[] characterBox();

        /** Its head, or null. */
        float[] headBox();

        StageView stage();

        SolverStage gl();

        /** Set the character up: Arrange, with it in hand. */
        void setUpCharacter();

        void toast(String text);
    }

    private final Host host;
    private final float dp;
    private final SharedPreferences sp;
    private RoomPrefs prefs;
    private final Overlay overlay;
    private final Glyph frameBtn, chairBtn;
    private final LinearLayout buttons, menu, strip;
    private Bitmap texture;
    private boolean menuOpen, lastSide, lastPerf;
    private String stripSig = "";
    private final FramePlan.Glide glide = new FramePlan.Glide();
    private FramePlan.Rect frameNow;                  // dp, the frame as the strip leaves it
    // the plain stage's camera: a shot and its animation
    private String shot = "wide";
    private float lastZoom = 1f, lastPanX, lastPanY;
    private boolean animating;
    private float fromZ, fromX, fromY, toZ, toX, toY;
    private double animT, animDur;
    private boolean wasFrame;
    private SceneRoom room;
    private java.util.List<cc.joycreator.avatar.ui.SceneStore.Entry> sceneList = new java.util.ArrayList<>();
    private boolean sceneTex;

    public SceneUi(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        sp = c.getSharedPreferences("avatar_room", Context.MODE_PRIVATE);
        prefs = RoomPrefs.fromJson(sp.getString("scene_prefs", "{}"));
        setClipChildren(false);
        overlay = new Overlay(c);
        addView(overlay, new LayoutParams(-1, -1));
        strip = new LinearLayout(c);
        strip.setTag("scene-strip");
        addView(strip, new LayoutParams(-2, -2));
        buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.END | Gravity.BOTTOM);
        frameBtn = new Glyph(c, true);
        chairBtn = new Glyph(c, false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(px(36), px(36));
        bp.setMargins(px(4), px(4), px(4), px(4));
        buttons.addView(frameBtn, bp);
        buttons.addView(chairBtn, bp);
        addView(buttons, new LayoutParams(-2, -2));
        menu = new LinearLayout(c);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.END);
        menu.setVisibility(GONE);
        addView(menu, new LayoutParams(-2, -2));
        frameBtn.setOnClickListener(v -> {
            prefs.setFrame(!prefs.frame);
            save();
            sync(true);
        });
        frameBtn.setOnLongClickListener(v -> {
            if (inScene()) {
                prefs.emptyShape = room.scene().shape();
                prefs.emptySafe = room.scene().safe();
            }
            SceneSheets.frame(getContext(), prefs, this::changed);
            return true;
        });
        chairBtn.setOnClickListener(v -> {
            menuOpen = !menuOpen;
            sync(true);
        });
        setClickable(false);
        sync(true);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ── settings kept ──
    private void save() {
        sp.edit().putString("scene_prefs", prefs.toJson().toString()).apply();
    }

    private void changed() {
        save();
        if (inScene()) {
            cc.joycreator.avatar.scene.Scene sc = room.scene();
            if (!prefs.emptyShape.equals(sc.shape())) sc.shape(prefs.emptyShape);
            if (!prefs.emptySafe.equals(sc.safe())) sc.margins(prefs.emptySafe);
            room.edited();
        }
        sync(true);
    }

    public boolean frameOn() {
        return prefs.frame && host.performing();
    }

    /** The empty scene's shape, as the frame shows it (and export will cut it). */
    public String shape() {
        return prefs.emptyShape;
    }

    public RoomPrefs prefs() {
        return prefs;
    }

    /** The scene in hand (or none): scenes plug into the same frame, chair and strip. */
    public void attachRoom(SceneRoom r) {
        room = r;
        refreshScenes();
    }

    private boolean inScene() {
        return room != null && room.isOpen();
    }

    /** A scene came, went or was made: the plain stage lets go of the frame and the strip is built again. */
    public void roomChanged() {
        StageView st = host.stage();
        st.setFit(null, null);
        st.setView(1f, 0f, 0f);
        host.gl().setBackdrop(null, null);
        overlay.set(null, null, null);
        shot = "wide";
        animating = false;
        lastZoom = 1f;
        lastPanX = lastPanY = 0;
        wasFrame = false;
        sceneTex = false;
        if (inScene()) {
            prefs.emptyShape = room.scene().shape();
            prefs.emptySafe = room.scene().safe();
        }
        refreshScenes();
        sync(true);
    }

    /** The folder's scenes, read off the main thread, then the strip is built again. */
    public void refreshScenes() {
        final android.content.Context cx = getContext();
        com.fadcam.joyfolder.android.JoyFolderHome.io().execute(() -> {
            final java.util.List<cc.joycreator.avatar.ui.SceneStore.Entry> l = cc.joycreator.avatar.ui.SceneStore.list(cx);
            post(() -> {
                sceneList = l;
                sync(true);
            });
        });
    }

    // ── what shows ──
    /** Keep the buttons and the strip true to the room. {@code force}: build the strip again even if nothing it shows changed. */
    private void sync(boolean force) {
        boolean perf = host.performing();
        setVisibility(VISIBLE);
        buttons.setVisibility(perf ? VISIBLE : GONE);
        java.util.List<String> parts = perf ? new java.util.ArrayList<String>(prefs.parts()) : new java.util.ArrayList<String>();
        boolean side = SceneGeometry.aspect(prefs.emptyShape) < 0.999 || (Math.abs(SceneGeometry.aspect(prefs.emptyShape) - 1) < 0.001 && lastSide);
        String sig = perf + "|" + parts + "|" + prefs.glide + "|" + prefs.frame + "|" + prefs.emptyShape + "|" + shot + "|" + host.characterName() + "|" + side + "|" + menuOpen + "|" + prefs.air + "|" + (room == null ? "" : room.signature()) + "|" + sceneList.size();
        if (!force && sig.equals(stripSig)) return;
        stripSig = sig;
        lastSide = side;
        lastPerf = perf;
        frameBtn.set(prefs.emptyShape, prefs.frame);
        TooltipCompat.setTooltipText(frameBtn, getContext().getString(prefs.frame ? R.string.avatar_sc_frame_on : R.string.avatar_sc_frame_off, prefs.emptyShape));
        frameBtn.setContentDescription(getContext().getString(prefs.frame ? R.string.avatar_sc_frame_on : R.string.avatar_sc_frame_off, prefs.emptyShape));
        TooltipCompat.setTooltipText(chairBtn, getContext().getString(R.string.avatar_sc_chair));
        chairBtn.setContentDescription(getContext().getString(R.string.avatar_sc_chair));
        chairBtn.open = menuOpen && perf;
        chairBtn.invalidate();
        buildMenu(perf);
        buildStrip(parts, side);
        place(side, !parts.isEmpty());
    }

    private void place(boolean side, boolean up) {
        LayoutParams sp0 = (LayoutParams) strip.getLayoutParams(), bp = (LayoutParams) buttons.getLayoutParams(), mp = (LayoutParams) menu.getLayoutParams();
        strip.setVisibility(up ? VISIBLE : GONE);
        buttons.setOrientation(side ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (side) {
            sp0.gravity = Gravity.END | Gravity.TOP;
            sp0.width = px(92);
            sp0.height = -2;
            sp0.topMargin = px(8);                            // beside the stage's own round tools, not under them: the whole height is its own
            sp0.bottomMargin = px(28);
            sp0.rightMargin = px(52);
            bp.gravity = Gravity.END | Gravity.BOTTOM;
            bp.rightMargin = up ? px(52 + 92 + 6) : px(52);
            bp.bottomMargin = px(32);
            mp.gravity = Gravity.END | Gravity.BOTTOM;
            mp.rightMargin = (up ? px(52 + 92 + 6) : px(52)) + px(44);
            mp.bottomMargin = px(32);
            strip.setOrientation(LinearLayout.VERTICAL);
        } else {
            sp0.gravity = Gravity.BOTTOM;
            sp0.width = -1;
            sp0.height = -2;
            sp0.topMargin = 0;
            sp0.leftMargin = px(6);
            sp0.rightMargin = px(6);
            sp0.bottomMargin = px(28);
            bp.gravity = Gravity.END | Gravity.BOTTOM;
            bp.rightMargin = px(4);
            bp.bottomMargin = up ? px(28) + px(96) : px(32);
            mp.gravity = Gravity.END | Gravity.BOTTOM;
            mp.rightMargin = px(4);
            mp.bottomMargin = (up ? px(28) + px(96) : px(32)) + px(44);
            strip.setOrientation(LinearLayout.HORIZONTAL);
        }
        strip.setLayoutParams(sp0);
        buttons.setLayoutParams(bp);
        menu.setLayoutParams(mp);
    }

    private void buildMenu(boolean perf) {
        menu.removeAllViews();
        menu.setVisibility(menuOpen && perf ? VISIBLE : GONE);
        int nChips = 3;
        String[] keys = {"sc", "ch", "sh"};
        int[] names = {R.string.avatar_sc_part_sc, R.string.avatar_sc_part_ch, R.string.avatar_sc_part_sh};
        int[] tips = {R.string.avatar_sc_tip_sc, R.string.avatar_sc_tip_ch, R.string.avatar_sc_tip_sh};
        for (int i = 0; i < nChips; i++) {
            final String k = keys[i];
            boolean on = i == 0 ? prefs.sc : i == 1 ? prefs.ch : prefs.sh && prefs.frame;
            String name = getContext().getString(names[i]);
            TextView chip = chip(name, on);
            String hover = getContext().getString(on ? R.string.avatar_sc_chip_hide : R.string.avatar_sc_chip_show, name.toLowerCase(), getContext().getString(tips[i]));
            TooltipCompat.setTooltipText(chip, hover);
            chip.setContentDescription(hover);
            chip.setOnClickListener(v -> {
                boolean now = k.equals("sc") ? prefs.sc : k.equals("ch") ? prefs.ch : prefs.sh && prefs.frame;
                prefs.setPart(k, !now);
                changed();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(30));
            lp.topMargin = px(5);
            menu.addView(chip, lp);
        }
    }

    private TextView chip(String text, boolean on) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        t.setPadding(px(12), 0, px(12), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(15));
        g.setColor(on ? Studio.ARMED : 0x8C0D0D10);
        if (!on) g.setStroke(Math.max(1, px(1) / 2), 0x14FFFFFF);
        t.setBackground(g);
        t.setTextColor(on ? Studio.GROUND : Studio.INK);
        return t;
    }

    // ── the strip ──
    private static final int[] PART_COLOR = {0xFF34D399, 0xFFC084FC, 0xFFFBBF24};

    private void buildStrip(java.util.List<String> parts, boolean side) {
        strip.removeAllViews();
        for (String k : parts) {
            int idx = k.equals("sc") ? 0 : k.equals("ch") ? 1 : 2;
            LinearLayout part = new LinearLayout(getContext());
            part.setOrientation(LinearLayout.VERTICAL);
            part.setPadding(px(5), px(3), px(5), px(5));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(14));
            bg.setColor(0x8C0D0D10);
            bg.setStroke(Math.max(1, px(1) / 2), 0x12FFFFFF);
            part.setBackground(bg);
            LinearLayout head = new LinearLayout(getContext());
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setOrientation(side ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            TextView label = new TextView(getContext());
            label.setText(idx == 0 ? R.string.avatar_sc_part_sc : idx == 1 ? R.string.avatar_sc_part_ch : R.string.avatar_sc_part_sh);
            label.setTextSize(8.5f);
            label.setAllCaps(true);
            label.setLetterSpacing(0.1f);
            label.setTypeface(android.graphics.Typeface.MONOSPACE);
            label.setTextColor(PART_COLOR[idx]);
            label.setGravity(side ? Gravity.CENTER : Gravity.START);
            head.addView(label, new LinearLayout.LayoutParams(side ? -1 : 0, px(18), side ? 0 : 1f));
            if (idx == 2) head.addView(cutGlide(), new LinearLayout.LayoutParams(-2, px(20)));
            part.addView(head, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout cards = new LinearLayout(getContext());
            cards.setOrientation(side ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            fillCards(idx, cards, side);
            View scroller;
            if (side) {
                ScrollView sv = new ScrollView(getContext());
                sv.setVerticalScrollBarEnabled(false);
                sv.addView(cards);
                scroller = sv;
            } else {
                HorizontalScrollView hv = new HorizontalScrollView(getContext());
                hv.setHorizontalScrollBarEnabled(false);
                hv.addView(cards);
                scroller = hv;
            }
            part.addView(scroller, new LinearLayout.LayoutParams(-1, side ? 0 : -2, side ? 1f : 0f));
            LinearLayout.LayoutParams lp = side ? new LinearLayout.LayoutParams(-1, 0, 1f) : new LinearLayout.LayoutParams(0, -2, 1f);
            if (parts.indexOf(k) > 0) {
                if (side) lp.topMargin = px(6);
                else lp.leftMargin = px(6);
            }
            strip.addView(part, lp);
        }
    }

    private View cutGlide() {
        LinearLayout seg = new LinearLayout(getContext());
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(10));
        g.setColor(0x59000000);
        seg.setBackground(g);
        seg.setPadding(px(1), px(1), px(1), px(1));
        String name = getContext().getString(R.string.avatar_sc_cutglide);
        TooltipCompat.setTooltipText(seg, name);
        for (int i = 0; i < 2; i++) {
            final boolean gl = i == 1;
            boolean on = prefs.glide == gl;
            TextView t = new TextView(getContext());
            t.setText(gl ? R.string.avatar_sc_glide : R.string.avatar_sc_cut);
            t.setTextSize(10);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(7), 0, px(7), 0);
            t.setTextColor(on ? Studio.GROUND : Studio.INK_DIM);
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(px(9));
            b.setColor(on ? Studio.ARMED : 0);
            t.setBackground(b);
            String hv = getContext().getString(gl ? R.string.avatar_sc_glide_hover : R.string.avatar_sc_cut_hover);
            TooltipCompat.setTooltipText(t, hv);
            t.setContentDescription(hv);
            t.setOnClickListener(v -> {
                prefs.glide = gl;
                changed();
            });
            seg.addView(t, new LinearLayout.LayoutParams(-2, -1));
        }
        return seg;
    }

    private void fillCards(int idx, LinearLayout cards, boolean side) {
        if (idx == 0) {                                      // Scene: the empty scene, then yours, then a new one
            card(cards, null, getContext().getString(R.string.avatar_sc_empty), getContext().getString(R.string.avatar_sc_empty_hover), !inScene(), 0, side,
                    () -> {
                        if (inScene()) room.close(true);
                    }, host::setUpCharacter, true);
            for (cc.joycreator.avatar.ui.SceneStore.Entry e : sceneList) {
                final String path = e.path;
                boolean on = inScene() && path.equals(room.pathNow());
                card(cards, null, e.name, getContext().getString(R.string.avatar_sc_scene_hover, e.name), on, 0, side,
                        () -> room.open(path), () -> room.host().stageChosen(0), true);
            }
            add(cards, getContext().getString(R.string.avatar_sc_new_hover), idx, side, this::newScene);
        } else if (idx == 1 && inScene()) {                  // Character: who is in the scene
            for (org.json.JSONObject it : room.scene().cast()) {
                final String id = it.optString("id");
                card(cards, null, it.optString("name"), getContext().getString(R.string.avatar_sc_item_hover, it.optString("name")), id.equals(room.selected()), 1, side,
                        () -> room.select(id), () -> room.host().stageChosen(1), true);
            }
            add(cards, getContext().getString(R.string.avatar_sc_add_hover), idx, side, room::addCharacterSheet);
            if (room.anyTake() || room.playingAll()) {
                boolean on = room.playingAll();
                addText(cards, on ? "\u25A0" : "\u25B6", getContext().getString(on ? R.string.avatar_sc_stop_all : R.string.avatar_sc_play_all), idx, side, () -> {
                    if (room.playingAll()) room.stopAll();
                    else room.playAll();
                    sync(true);
                });
            }
        } else if (idx == 1) {                               // Character: the one in hand
            card(cards, host.characterPic(), host.characterName(), getContext().getString(R.string.avatar_sc_char_hover, host.characterName()), true, 1, side,
                    () -> {
                    }, host::setUpCharacter, false);
        } else if (inScene()) {                              // Shots of the scene: Wide, Two-shot, a close-up on each one, yours
            for (cc.joycreator.avatar.scene.SceneClock.ShotInfo s : room.shots()) {
                final String key = s.key;
                String name = "close".equals(s.kind) ? getContext().getString(R.string.avatar_sc_close_on, s.name) : "wide".equals(s.kind) ? getContext().getString(R.string.avatar_sc_wide) : s.name;
                card(cards, null, "wide".equals(s.kind) ? getContext().getString(R.string.avatar_sc_wide) : s.name, getContext().getString(R.string.avatar_sc_shot_hover, name), key.equals(room.shotKey()), 2, side,
                        () -> room.goShot(key, prefs.glide ? 0.8 : 0), () -> {
                        }, true);
            }
        } else {                                             // Shots
            String[][] shots = shotList();
            for (String[] s : shots) {
                final String key = s[0];
                String name = s[2].equals("close") ? getContext().getString(R.string.avatar_sc_close_on, s[1]) : s[0].equals("wide") ? getContext().getString(R.string.avatar_sc_wide) : s[1];
                String label = s[2].equals("wide") ? getContext().getString(R.string.avatar_sc_wide) : s[1];
                card(cards, s[2].equals("close") ? host.characterPic() : null, label, getContext().getString(R.string.avatar_sc_shot_hover, name), key.equals(shot), 2, side,
                        () -> goShot(key), () -> {
                            if (!key.equals(shot)) goShot(key);
                            SceneSheets.shot(getContext(), prefs, host.characterKey(), s, this::changedShots, this::keepNow, () -> reframeNow(key), name);
                        }, false);
            }
            add(cards, getContext().getString(R.string.avatar_sc_keep_shot), idx, side, this::keepNow);
        }
    }

    /** A new scene: which set it stands on, then it opens, named for its number, and is kept at once. */
    private void newScene() {
        final String[] names = new String[SETS + 1];
        for (int i = 0; i < SETS; i++) names[i] = cc.joycreator.avatar.scene.Scene.SET_NAMES[i];
        names[SETS] = getContext().getString(R.string.avatar_sc_set_none);
        new android.app.AlertDialog.Builder(getContext()).setTitle(R.string.avatar_sc_new_title).setItems(names, (d, i) -> {
            String key = i < SETS ? cc.joycreator.avatar.scene.Scene.SET_KEYS[i] : "plain";
            room.create(key, getContext().getString(R.string.avatar_sc_new) + " " + (sceneList.size() + 1));
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static final int SETS = 4;

    private void card(LinearLayout row, Bitmap pic, String label, String hover, boolean on, int idx, boolean side, Runnable choose, Runnable setUp, boolean plain) {
        FrameLayout c = new FrameLayout(getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(10));
        bg.setColor(plain ? 0xFF0D0D10 : Studio.RAISED);
        if (on) bg.setStroke(px(2), Studio.ARMED);
        c.setBackground(bg);
        c.setClipToOutline(true);
        if (pic != null) {
            ImageView iv = new ImageView(getContext());
            iv.setImageBitmap(pic);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(0, px(3), 0, px(14));
            c.addView(iv, new LayoutParams(-1, -1));
        }
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextSize(9);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(on ? Studio.GROUND : Studio.INK);
        t.setBackgroundColor(on ? Studio.ARMED : 0xB809090B);
        FrameLayout.LayoutParams tp = new LayoutParams(-1, px(14), Gravity.BOTTOM);
        c.addView(t, tp);
        TooltipCompat.setTooltipText(c, hover);
        c.setContentDescription(hover);
        c.setOnClickListener(v -> {
            if (on) setUp.run();
            else choose.run();
        });
        c.setOnLongClickListener(v -> {
            setUp.run();
            return true;
        });
        LinearLayout.LayoutParams lp = side ? new LinearLayout.LayoutParams(-1, px(52)) : new LinearLayout.LayoutParams(px(58), px(52));
        if (side) lp.bottomMargin = px(5);
        else lp.rightMargin = px(5);
        row.addView(c, lp);
    }

    private void add(LinearLayout row, String hover, int idx, boolean side, Runnable fn) {
        addText(row, "+", hover, idx, side, fn);
    }

    private void addText(LinearLayout row, String text, String hover, int idx, boolean side, Runnable fn) {
        TextView a = new TextView(getContext());
        a.setText(text);
        a.setGravity(Gravity.CENTER);
        a.setTextSize(18);
        a.setTextColor(PART_COLOR[idx]);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(9));
        g.setStroke(px(1) + 1, PART_COLOR[idx]);
        a.setBackground(g);
        a.setAlpha(0.85f);
        TooltipCompat.setTooltipText(a, hover);
        a.setContentDescription(hover);
        a.setOnClickListener(v -> fn.run());
        row.addView(a, side ? new LinearLayout.LayoutParams(-1, px(26)) : new LinearLayout.LayoutParams(px(28), px(52)));
    }

    // ── shots on the plain stage ──
    private String[][] shotList() {
        JSONArray mine = null;
        try {
            mine = prefs.shotsOf(host.characterKey());
        } catch (JSONException ignored) {
        }
        return EmptyShots.list(host.characterName(), host.headBox() != null, mine);
    }

    private double[] frameArr() {
        FramePlan.Rect f = frameNow;
        return f == null ? null : new double[]{f.x * dp, f.y * dp, f.w * dp, f.h * dp};
    }

    private double[] boxArr() {
        float[] b = host.characterBox();
        return b == null ? null : new double[]{b[0], b[1], b[2], b[3]};
    }

    private double[] wideView() {
        double[] f = frameArr(), b = boxArr();
        return f == null || b == null ? null : EmptyShots.wide(f, b, 4 * dp);
    }

    private double[] viewFor(String key) {
        double[] f = frameArr(), b = boxArr(), w = wideView();
        if (f == null || b == null || w == null) return null;
        if (key.equals("close")) {
            float[] hd = host.headBox();
            return hd == null ? w : EmptyShots.close(f, w, new double[]{hd[0], hd[1], hd[2], hd[3]});
        }
        try {
            JSONArray mine = prefs.shotsOf(host.characterKey());
            for (int i = 0; i < mine.length(); i++) if (key.equals(mine.optJSONObject(i).optString("id"))) return EmptyShots.kept(f, w, mine.optJSONObject(i));
        } catch (JSONException ignored) {
        }
        return w;
    }

    /** Go to a shot: Wide, Close on the character, or one of yours. Shots need the frame, so it is turned on. */
    public void goShot(String key) {
        if (!prefs.frame) {
            prefs.setFrame(true);
            save();
        }
        shot = key;
        double[] v = viewFor(key);
        float[] bv = host.stage().baseView();
        if (v != null && bv[0] > 0) {
            fromZ = host.stage().zoomNow();
            fromX = host.stage().panXNow();
            fromY = host.stage().panYNow();
            toZ = (float) (v[0] / bv[0]);
            toX = (float) (v[1] - bv[1]);
            toY = (float) (v[2] - bv[2]);
            animDur = prefs.glide ? 0.9 : 0;
            animT = 0;
            animating = animDur > 0;
            if (!animating) setStageView(toZ, toX, toY);
        }
        sync(true);
    }

    private void setStageView(float z, float x, float y) {
        host.stage().setView(z, x, y);
        lastZoom = z;
        lastPanX = x;
        lastPanY = y;
    }

    private void keepNow() {
        double[] f = frameArr(), w = wideView();
        float[] bv = host.stage().baseView();
        StageView st = host.stage();
        if (f == null || w == null) return;
        double[] view = {bv[0] * st.zoomNow(), bv[1] + st.panXNow(), bv[2] + st.panYNow()};
        try {
            JSONArray mine = prefs.shotsOf(host.characterKey());
            JSONObject s = EmptyShots.keep("h" + Long.toString(System.currentTimeMillis(), 36), "Shot " + (mine.length() + 1), f, w, view);
            mine.put(s);
            shot = s.optString("id");
            save();
            host.toast(getContext().getString(R.string.avatar_sc_kept, s.optString("name")));
        } catch (JSONException ignored) {
        }
        sync(true);
    }

    /** One of your shots becomes the view the camera has now. */
    private void reframeNow(String id) {
        double[] f = frameArr(), w = wideView();
        float[] bv = host.stage().baseView();
        StageView st = host.stage();
        if (f == null || w == null) return;
        double[] view = {bv[0] * st.zoomNow(), bv[1] + st.panXNow(), bv[2] + st.panYNow()};
        try {
            JSONArray mine = prefs.shotsOf(host.characterKey());
            for (int i = 0; i < mine.length(); i++) {
                JSONObject s = mine.optJSONObject(i);
                if (s != null && id.equals(s.optString("id"))) {
                    JSONObject n = EmptyShots.keep(id, s.optString("name"), f, w, view);
                    s.put("cx", n.get("cx")).put("cy", n.get("cy")).put("k", n.get("k"));
                }
            }
            shot = id;
            save();
        } catch (JSONException ignored) {
        }
        sync(true);
    }

    private void changedShots() {
        save();
        sync(true);
    }

    // ── once a frame ──
    /** Called from the room's frame loop: the frame's place, the picture fitted into it, the shot's camera. */
    public void tick(double dt) {
        if (inScene()) {
            tickScene(dt);
            return;
        }
        boolean perf = host.performing();
        if (perf != lastPerf) sync(true);
        float w = getWidth() / dp, h = getHeight() / dp;
        StageView st = host.stage();
        boolean on = prefs.frame && perf && w > 0 && h > 0;
        if (!on) {
            if (wasFrame) {
                st.setFit(null, null);
                st.setView(1f, 0f, 0f);
                host.gl().setBackdrop(null, null);
                overlay.set(null, null, null);
                shot = "wide";
                animating = false;
                lastZoom = 1f;
                lastPanX = lastPanY = 0;
                wasFrame = false;
                sync(true);
            }
            return;
        }
        wasFrame = true;
        double ar = SceneGeometry.aspect(prefs.emptyShape);
        FramePlan.Rect area = FramePlan.area(w, h, 8, 8, 52, 28);
        boolean side = FramePlan.side(ar, area, prefs.air);
        if (side != lastSide && Math.abs(ar - 1) < 0.001) sync(true);
        double from = Double.NaN;
        if (strip.getVisibility() == VISIBLE && strip.getWidth() > 0) from = side ? strip.getLeft() / dp : strip.getTop() / dp;
        FramePlan.Rect fit = FramePlan.fit(area, ar, prefs.air);
        double[] d = FramePlan.push(fit, area, w, h, side, from, buttons.getVisibility() == VISIBLE);
        double[] g = glide.to(d[0], d[1], dt);
        frameNow = new FramePlan.Rect(fit.x + g[0], fit.y + g[1], fit.w, fit.h);
        RectF rpx = new RectF((float) (frameNow.x * dp), (float) (frameNow.y * dp), (float) (frameNow.right() * dp), (float) (frameNow.bottom() * dp));
        float[] box = host.characterBox();
        st.setFit(rpx, box == null ? null : new RectF(box[0], box[1], box[2], box[3]));
        if (texture == null) texture = BitmapFactory.decodeResource(getResources(), R.drawable.studio_backdrop_graffiti, rgb565());
        host.gl().setBackdrop(texture, new float[]{rpx.left, rpx.top, rpx.right, rpx.bottom});
        overlay.set(rpx, prefs.emptySafe, prefs.emptyShape + "  " + shotName().toUpperCase());
        // the camera: a glide in progress, a close-up keeping up, or a view the fingers moved ("By hand")
        if (animating) {
            animT += dt;
            double u = Math.min(1, animT / animDur), s = u * u * (3 - 2 * u);
            setStageView((float) (fromZ + (toZ - fromZ) * s), (float) (fromX + (toX - fromX) * s), (float) (fromY + (toY - fromY) * s));
            if (u >= 1) animating = false;
        } else if (!shot.equals("free")) {
            if (Math.abs(st.zoomNow() - lastZoom) > 1e-3 || Math.abs(st.panXNow() - lastPanX) > 0.6 || Math.abs(st.panYNow() - lastPanY) > 0.6) {
                shot = "free";
                sync(true);
            } else {
                double[] v = viewFor(shot);
                float[] bv = st.baseView();
                if (v != null && bv[0] > 0) setStageView((float) (v[0] / bv[0]), (float) (v[1] - bv[1]), (float) (v[2] - bv[2]));
            }
        }
    }


    /** The same frame, push and glide for a scene in hand: its own shape, and the scene's stage draws into it. */
    private void tickScene(double dt) {
        cc.joycreator.avatar.scene.Scene sc = room.scene();
        boolean perf = host.performing();
        if (perf != lastPerf) sync(true);
        sync(false);
        float w = getWidth() / dp, h = getHeight() / dp;
        if (w <= 0 || h <= 0) return;
        if (!sceneTex) {
            if (texture == null) texture = BitmapFactory.decodeResource(getResources(), R.drawable.studio_backdrop_graffiti, rgb565());
            room.setTexture(texture);
            sceneTex = true;
        }
        FramePlan.Rect area = FramePlan.area(w, h, 8, 8, 52, 28);
        float[] ap = {(float) (area.x * dp), (float) (area.y * dp), (float) (area.right() * dp), (float) (area.bottom() * dp)};
        if (!(prefs.frame && perf)) {
            overlay.set(null, null, null);
            room.tick(dt, getWidth(), getHeight(), null, ap, null);
            return;
        }
        double ar = SceneGeometry.aspect(sc.shape());
        boolean side = FramePlan.side(ar, area, prefs.air);
        if (side != lastSide && Math.abs(ar - 1) < 0.001) sync(true);
        double from = Double.NaN;
        if (strip.getVisibility() == VISIBLE && strip.getWidth() > 0) from = side ? strip.getLeft() / dp : strip.getTop() / dp;
        FramePlan.Rect fit = FramePlan.fit(area, ar, prefs.air);
        double[] d = FramePlan.push(fit, area, w, h, side, from, buttons.getVisibility() == VISIBLE);
        double[] g = glide.to(d[0], d[1], dt);
        frameNow = new FramePlan.Rect(fit.x + g[0], fit.y + g[1], fit.w, fit.h);
        float[] rp = {(float) (frameNow.x * dp), (float) (frameNow.y * dp), (float) (frameNow.right() * dp), (float) (frameNow.bottom() * dp)};
        overlay.set(new RectF(rp[0], rp[1], rp[2], rp[3]), sc.safe(), sc.shape() + "  " + getContext().getString(R.string.avatar_sc_wide).toUpperCase());
        room.tick(dt, getWidth(), getHeight(), rp, ap, rp);
    }

    private String shotName() {
        if (shot.equals("free")) return "By hand";
        for (String[] s : shotList()) if (s[0].equals(shot)) return s[2].equals("close") ? getContext().getString(R.string.avatar_sc_close_on, s[1]) : s[1];
        return getContext().getString(R.string.avatar_sc_wide);
    }

    private static BitmapFactory.Options rgb565() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return o;
    }

    // ── the frame on the glass ──
    private final class Overlay extends View {
        private RectF rect;
        private String safe, label;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Overlay(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void set(RectF r, String s, String l) {
            rect = r;
            safe = s;
            label = l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (rect == null) return;
            RectF f = rect;
            int w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x3D09090B);                                   // a little darker outside: out of the shot
            c.drawRect(0, 0, w, f.top, p);
            c.drawRect(0, f.bottom, w, h, p);
            c.drawRect(0, f.top, f.left, f.bottom, p);
            c.drawRect(f.right, f.top, w, f.bottom, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, dp * 0.5f));
            p.setColor(0x99A1A1AA);                                   // a zinc hairline
            c.drawRect(f.left + 0.5f, f.top + 0.5f, f.right - 0.5f, f.bottom - 0.5f, p);
            if ("title".equals(safe)) {
                p.setPathEffect(new android.graphics.DashPathEffect(new float[]{5 * dp, 4 * dp}, 0));
                p.setColor(0xB322D3EE);
                c.drawRect(f.left + f.width() * 0.05f, f.top + f.height() * 0.05f, f.right - f.width() * 0.05f, f.bottom - f.height() * 0.05f, p);
                p.setColor(0xBFE879F9);
                c.drawRect(f.left + f.width() * 0.1f, f.top + f.height() * 0.1f, f.right - f.width() * 0.1f, f.bottom - f.height() * 0.1f, p);
                p.setPathEffect(null);
            } else if ("social".equals(safe)) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x24E879F9);
                c.drawRect(f.left, f.top, f.right, f.top + f.height() * 0.12f, p);
                c.drawRect(f.left, f.top + f.height() * 0.78f, f.right, f.bottom, p);
                c.drawRect(f.left + f.width() * 0.86f, f.top + f.height() * 0.12f, f.right, f.top + f.height() * 0.78f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setPathEffect(new android.graphics.DashPathEffect(new float[]{5 * dp, 4 * dp}, 0));
                p.setColor(0xBFE879F9);
                c.drawRect(f.left, f.top + f.height() * 0.12f, f.left + f.width() * 0.86f, f.top + f.height() * 0.78f, p);
                p.setPathEffect(null);
            }
            if (label != null) {
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(9.5f * dp);
                p.setTypeface(android.graphics.Typeface.MONOSPACE);
                boolean above = f.top > 18 * dp;
                float y = above ? f.top - 3 * dp : f.top + 13 * dp;
                if (!above) {
                    p.setColor(0x7309090B);
                    c.drawRect(f.left + 1, f.top + 1, f.left + 10 * dp + p.measureText(label), f.top + 16 * dp, p);
                    p.setColor(0xD9D4D4D8);
                } else p.setColor(0xC7A1A1AA);
                c.drawText(label, f.left + 5 * dp, y, p);
            }
        }
    }

    // ── the two round buttons' pictures ──
    private final class Glyph extends View {
        private final boolean isFrame;
        private String shape = "16:9";
        private boolean on, open;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(Context c, boolean isFrame) {
            super(c);
            this.isFrame = isFrame;
        }

        void set(String shape, boolean on) {
            this.shape = shape;
            this.on = on;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2;
            boolean lit = isFrame && on;
            p.setStyle(Paint.Style.FILL);
            p.setColor(lit ? Studio.ARMED : open ? Studio.PRESSED : 0x8C0D0D10);
            c.drawCircle(w / 2, h / 2, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, dp * 0.5f));
            p.setColor(lit ? 0 : 0x14FFFFFF);
            c.drawCircle(w / 2, h / 2, r - 0.5f, p);
            int ink = lit ? Studio.GROUND : open ? Studio.INK : Studio.INK_DIM;
            p.setColor(ink);
            p.setStrokeWidth(1.7f * dp * 0.9f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            float u = dp * 22f / 24f;
            c.save();
            c.translate(w / 2 - 12 * u, h / 2 - 12 * u);
            c.scale(u, u);
            Path path = new Path();
            if (isFrame) {
                path.moveTo(2.5f, 9); path.lineTo(2.5f, 5.5f); path.lineTo(6, 5.5f);
                path.moveTo(18, 5.5f); path.lineTo(21.5f, 5.5f); path.lineTo(21.5f, 9);
                path.moveTo(21.5f, 15); path.lineTo(21.5f, 18.5f); path.lineTo(18, 18.5f);
                path.moveTo(6, 18.5f); path.lineTo(2.5f, 18.5f); path.lineTo(2.5f, 15);
                p.setStrokeWidth(1.7f);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(6.3f);
                p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(shape, 12, 14.4f, p);
            } else {                                               // the director's chair: a backrest band on two posts, the seat, the crossed legs
                path.moveTo(6.5f, 3.5f); path.lineTo(6.5f, 12.5f);
                path.moveTo(17.5f, 3.5f); path.lineTo(17.5f, 12.5f);
                path.moveTo(6.5f, 4.5f); path.lineTo(17.5f, 4.5f); path.lineTo(17.5f, 8.1f); path.lineTo(6.5f, 8.1f);
                path.moveTo(4.5f, 12.5f); path.lineTo(19.5f, 12.5f);
                path.moveTo(7, 12.5f); path.lineTo(17, 20.5f);
                path.moveTo(17, 12.5f); path.lineTo(7, 20.5f);
                path.moveTo(5.5f, 20.5f); path.lineTo(8.5f, 20.5f);
                path.moveTo(15.5f, 20.5f); path.lineTo(18.5f, 20.5f);
                p.setStrokeWidth(1.7f);
                c.drawPath(path, p);
            }
            c.restore();
        }
    }
}
