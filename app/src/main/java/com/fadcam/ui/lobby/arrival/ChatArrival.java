package com.fadcam.ui.lobby.arrival;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.fadcam.ui.lobby.JoybotActs;
import com.fadcam.ui.lobby.JoybotArrival;
import com.fadcam.ui.lobby.JoybotView;
import com.fadcam.ui.lobby.arrival.stage.StageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens the Joybot chat the way the owner picked (15 Nov 2026) and brings Joybot in.
 *
 * <p>The drawer opens out of the button (phone) or drops like a curtain (tablet), or opens in his
 * wake; its JOYBOT title grows out of the button, comes with the drawer, or lands letter by letter
 * after him; Joybot flies one of the owner's seven paths from the button to his seat at the start of
 * the bottom row, comes down by rocket, plop or skim, and lands with a double bounce. The chat's own
 * Joybot then takes over on the seat. The first open of the day is always the whole show; later
 * opens follow the "later opens" setting. On a tablet (or when chosen on a phone) the chat is a card
 * that can be moved and sized.</p>
 *
 * <p>Everything runs on the clock (Choreographer), never on system animators: the owner's Note 9 has
 * "remove animations" on, and he ruled that Joybot always animates (11-14).</p>
 */
public final class ChatArrival {

    private static final String TAG = "ChatArrival";
    /** The chat's Joybot is 64 dp (activity_chat_assistant.xml chat_actor). */
    private static final float ACTOR_DP = 64f;
    /**
     * His cone floats this far above his seat (owner's pick, 11-15 evening: "Hover D (Alive), 12 dp above the seat"). He
     * lands on it 4 dp up, with the impact, then lifts to here.
     */
    static final float HOVER_DP = 12f;
    /** His body inside a cell, until acts.json is read (the same numbers). */
    private static final RectF DEFAULT_FOCUS = new RectF(.2667f, .1625f, .6833f, .9167f);
    private static final float CELL = 240f;
    /** The longest the open waits for his pictures before going without the flight. */
    private static final long SPRITE_WAIT_MS = 260L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Activity a;
    private final float d;
    private final FrameLayout root, reveal, stage;
    private final LinearLayout panel, title;
    private final View header, titleBlock, seat, row, grip, chips;
    @Nullable private final View closeBtn;
    private final JoybotView actor;
    private final Random dice = new Random();
    private final CardFrame frame;
    /** Frost where Android's window blur is not offered (the owner's Samsungs): a blurred picture of the screen. */
    private final FrostView frost;
    private final List<TextView> letters = new ArrayList<>();
    private final float[] tmp = new float[4];

    private boolean card;
    /**
     * The card stays inside the full chat window and the screen beside it can't be touched: the room under it is paused
     * and skips its saves, so edits made beside a floating card were lost (bug hunt A10, 11-15). A tap beside it closes
     * the chat. Only the lobby and the Library keep the free-floating card.
     */
    private boolean modal;
    private boolean placed, started, running, handedOff, opened, stageOver, closing;
    private long t0, handT, waitUntil;
    @Nullable private Flight flight;
    @Nullable private Reveal rev;
    @Nullable private StageView gl;
    @Nullable private Bitmap idle, spin, homeSharp;
    private int idleCols = 8, idleRows = 2, spinCols = 8, spinRows = 2, spinCount = 10;
    @NonNull private RectF focus = DEFAULT_FOCUS;
    private volatile boolean spritesDone;
    private float titleX, titleY;
    private int lastBlur = -1;
    // the opening circle, px in chat_reveal's own coordinates
    private float cx, cy, cr = -1f;

    public ChatArrival(@NonNull Activity a, @NonNull JoybotView actor) {
        this.a = a;
        this.actor = actor;
        this.d = a.getResources().getDisplayMetrics().density;
        root = a.findViewById(R.id.chat_root);
        reveal = a.findViewById(R.id.chat_reveal);
        stage = a.findViewById(R.id.chat_stage);
        panel = a.findViewById(R.id.chat_panel);
        title = a.findViewById(R.id.chat_title);
        header = a.findViewById(R.id.chat_header);
        titleBlock = a.findViewById(R.id.chat_title_block);
        seat = a.findViewById(R.id.chat_seat);
        row = a.findViewById(R.id.chat_row);
        grip = a.findViewById(R.id.chat_grip);
        chips = a.findViewById(R.id.chat_chips);
        closeBtn = a.findViewById(R.id.chat_back);
        frame = new CardFrame(a, root, reveal);
        frost = new FrostView(a);
        frame.setOnMoved(() -> { frost.invalidate(); frost.postOnAnimation(frost::invalidate); frost.postDelayed(frost::invalidate, 60L); });
    }

    // ── set up ──────────────────────────────────────────────────────────────

    /** Opened over a room where work is edited (not the lobby or the Library): the card is modal there. Before install. */
    public void setModal(boolean modal) { this.modal = modal; }

    /** Call once, after the chat's views are set up. The show starts by itself once laid out. */
    public void install() {
        Window w = a.getWindow();
        if (w != null) w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        card = JoybotArrival.isCard(a);
        buildTitle();
        seat.setBackground(new SeatDrawable(d));
        style();
        reveal.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                if (cr < 0f) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), card ? 24f * d : 0f); return; }
                // never an empty outline: some phones clip nothing for one instead of everything
                int r = Math.max(1, Math.round(cr));
                o.setOval(Math.round(cx) - r, Math.round(cy) - r, Math.round(cx) + r, Math.round(cy) + r);
            }
        });
        reveal.addView(frost, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        reveal.setVisibility(View.INVISIBLE);
        chips.setAlpha(0f);
        actor.setChatStage(HOVER_DP);
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeActor());
        frame.attach(titleBlock, grip);
        frame.watchKeyboard(root);
        // A modal card: a tap beside it closes the chat; touches on the card that nothing on it takes stay on the card.
        reveal.setOnTouchListener((v, e) -> true);
        root.setOnClickListener(v -> { if (card && modal && opened) a.finish(); });
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        loadSprites();
        waitUntil = SystemClock.uptimeMillis() + SPRITE_WAIT_MS;
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (root.getWidth() == 0) return;
                if (card && !placed) {
                    placed = true;
                    frame.place(frame.bounds(a.getIntent().getSourceBounds()));
                    return;                                   // wait for the card's own layout
                }
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                placeActor();
                root.post(ChatArrival.this::tryStart);
            }
        });
    }

    /** The drawer's fill and frame: see-through always (owner: drawers never opaque; Frost only adds blur). */
    private void style() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x8C09090C);
        if (card) {
            bg.setCornerRadius(24f * d);
            bg.setStroke(Math.max(1, Math.round(d)), 0x1AFFFFFF);
        }
        panel.setBackground(bg);
        panel.setClipToOutline(card);
        grip.setVisibility(card ? View.VISIBLE : View.GONE);
        frame.setEnabled(card && opened);          // not while he is flying to the seat
        if (!card) reveal.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // No hover label on the grip: it lies over the whole card, so one would follow the pointer everywhere.
        if (card) androidx.appcompat.widget.TooltipCompat.setTooltipText(titleBlock, "Drag to move the chat");
    }

    /** JOYBOT, one view a letter, the way a Studio room is titled. */
    private void buildTitle() {
        title.removeAllViews();
        letters.clear();
        if (titleBlock instanceof ViewGroup) ((ViewGroup) titleBlock).setClipChildren(false);
        String word = a.getString(R.string.assistant_title).toUpperCase(Locale.ROOT);
        Typeface tf = null;
        try { tf = ResourcesCompat.getFont(a, R.font.archivo_variable); } catch (RuntimeException ignored) { /* system bold */ }
        float sp = 38f;
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, a.getResources().getDisplayMetrics());
        for (int i = 0; i < word.length(); i++) {
            TextView t = new TextView(a);
            t.setText(String.valueOf(word.charAt(i)));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(0xFFEFE9FF);
            t.setIncludeFontPadding(false);
            if (tf != null) t.setTypeface(tf, Typeface.BOLD);
            else t.setTypeface(Typeface.DEFAULT_BOLD);
            if (Build.VERSION.SDK_INT >= 26 && tf != null) t.setFontVariationSettings("'wght' 900");
            t.setShadowLayer(9f * d, 0f, 0f, 0x73CC27FF);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < word.length() - 1) lp.setMarginEnd(Math.round(-.05f * px));
            title.addView(t, lp);
            letters.add(t);
        }
    }

    /** His pictures for the flight: his home pose and his tumble. Off the main thread. */
    private void loadSprites() {
        final android.content.Context app = a.getApplicationContext();
        IO.execute(() -> {
            JoybotActs acts = JoybotActs.get(app);
            if (acts == null) { spritesDone = true; return; }
            JoybotActs.Act ia = acts.named("idle"), sa = acts.named("spin");
            // His home pose at 480 px for the flight: he zooms up to about 3x toward us (Flight.DEPTH), where the 240 px
            // cell would be stretched. Only this one picture is big (owner: keep big frames to the moments he is shown large).
            Bitmap sharp = null;
            try (java.io.InputStream in = app.getAssets().open("joybot/hi/home.webp")) {
                sharp = android.graphics.BitmapFactory.decodeStream(in);
            } catch (java.io.IOException | RuntimeException | OutOfMemoryError ignored) { /* the 240 px home will do */ }
            final Bitmap hi = sharp;
            actor.post(() -> {
                homeSharp = hi;
                if (ia == null) { spritesDone = true; return; }
                RectF f = acts.focus();
                if (f != null) focus = f;
                idleCols = ia.cols();
                idleRows = ia.rows();
                if (sa != null) { spinCols = sa.cols(); spinRows = sa.rows(); spinCount = sa.frames(); }
                Bitmap got = acts.opened(ia);
                if (got != null) { idle = got; openSpin(acts, sa); return; }
                acts.open(ia, actor, b -> { idle = b; openSpin(acts, sa); });
            });
        });
    }

    private void openSpin(@NonNull JoybotActs acts, @Nullable JoybotActs.Act sa) {
        if (sa == null) { spritesDone = true; return; }
        Bitmap got = acts.opened(sa);
        if (got != null) { spin = got; spritesDone = true; return; }
        acts.open(sa, actor, b -> { spin = b; spritesDone = true; });
    }

    /** His seat: body centre over the seat, his hover peg 4 dp above the row's floor. */
    private void placeActor() {
        if (seat.getWidth() == 0 || stage.getWidth() == 0) return;
        int[] s = seatInStage();
        float peg = Show.pegUp(CELL, CELL, focus, ACTOR_DP) * d;
        int aw = Math.round(ACTOR_DP * d);
        int left = s[0] - aw / 2, top = Math.round(s[1] - HOVER_DP * d - peg - aw / 2f);
        ViewGroup.LayoutParams p = actor.getLayoutParams();
        if (!(p instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p;
        if (lp.leftMargin == left && lp.topMargin == top) return;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = top;
        actor.setLayoutParams(lp);
    }

    /** The seat's centre x and its floor y, in chat_stage pixels. */
    @NonNull
    private int[] seatInStage() {
        int[] a1 = new int[2], a2 = new int[2];
        seat.getLocationInWindow(a1);
        stage.getLocationInWindow(a2);
        // the curtain may be holding the drawer up: positions are taken without its slide
        return new int[]{a1[0] - a2[0] + seat.getWidth() / 2, a1[1] - a2[1] + seat.getHeight()};
    }

    // ── the show ────────────────────────────────────────────────────────────

    private void tryStart() {
        if (started || closing || a.isFinishing()) return;
        if (!spritesDone && SystemClock.uptimeMillis() < waitUntil) { root.postDelayed(this::tryStart, 16L); return; }
        begin();
    }

    private void begin() {
        started = true;
        int[] ro = new int[2];
        root.getLocationOnScreen(ro);
        float W = root.getWidth() / d;
        Rect sb = a.getIntent().getSourceBounds();
        float bx = sb != null ? (sb.exactCenterX() - ro[0]) / d : W - 30f;
        float by = sb != null ? (sb.exactCenterY() - ro[1]) / d : 30f;
        RectF pr = new RectF(reveal.getLeft() / d, reveal.getTop() / d, reveal.getRight() / d, reveal.getBottom() / d);
        int[] s = seatInStage();
        float pegUp = Show.pegUp(CELL, CELL, focus, ACTOR_DP);
        float ex = pr.left + s[0] / d, ey = pr.top + s[1] / d - 4f - pegUp;

        boolean full = JoybotArrival.fullShowNow(a);
        JoybotArrival.Later later = JoybotArrival.later(a);
        List<float[]> paths = FlightPaths.get(a);
        boolean hop = paths.isEmpty() || (!full && later == JoybotArrival.Later.HOP);
        boolean shorter = !full && later == JoybotArrival.Later.SHORT;
        float[] uv = hop ? null : paths.get(JoybotArrival.nextPath(a, paths.size(), dice));
        Flight.Approach ap = Flight.Approach.values()[JoybotArrival.nextApproach(a, 3, dice)];
        Flight f = new Flight(bx, by, ex, ey, pegUp, uv, ap, Flight.Landing.DOUBLE, shorter).withRise(HOVER_DP - 4f);
        Log.i(TAG, "arrival: " + (hop ? "hop" : shorter ? "shorter show" : "whole show") + ", " + ap
                + ", " + JoybotArrival.reveal(a) + " / " + JoybotArrival.title(a) + (card ? ", card" : ", sheet")
                + ", flight " + f.dur + " ms, frost " + frostOn());
        Reveal r = new Reveal(JoybotArrival.reveal(a), JoybotArrival.title(a), hop, bx, by, pr, f);
        flight = f;
        rev = r;
        int[] tb = new int[2];
        titleBlock.getLocationOnScreen(tb);
        titleX = (tb[0] - ro[0]) / d;
        titleY = (tb[1] - ro[1]) / d;
        t0 = System.nanoTime();
        // the picture of the screen it opened over: taken every time, so it never lingers in memory
        Rect bs = new Rect();
        Bitmap back = JoybotOpen.takeBackdrop(bs);
        // a modal card is never its own window, so the window's blur would frost the whole screen: blur the card's own picture
        if (back != null && frostOn() && (!blurOffered(a) || (card && modal))) frost.setPicture(back, bs);
        if (idle != null) startStage(r, f, ro, back, bs);
        reveal.setVisibility(View.VISIBLE);
        running = true;
        apply(0L);
        Choreographer.getInstance().postFrameCallback(tick);
    }

    private void startStage(@NonNull Reveal r, @NonNull Flight f, @NonNull int[] ro, @Nullable Bitmap back, @NonNull Rect bs) {
        try {
            Bitmap home = homeSharp != null ? homeSharp : idle;
            int hc = homeSharp != null ? 1 : idleCols, hr = homeSharp != null ? 1 : idleRows;
            Show show = new Show(f, r, d, t0, home, hc, hr, spin, spinCols, spinRows, spinCount, focus, ACTOR_DP);
            if (back != null) {
                show.back = back;
                show.backRect.set((bs.left - ro[0]) / d, (bs.top - ro[1]) / d, (bs.right - ro[0]) / d, (bs.bottom - ro[1]) / d);
                show.panelPic = snapshot();
                show.frostBias = frostOn() ? 3f : 0f;
            }
            StageView v = new StageView(a);
            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            gl = v;
            v.play(show, this::stageDone);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "no flight this time", e);
            gl = null;
        }
    }

    /** The drawer fully open, drawn small, for the heat haze to bend. */
    @Nullable
    private Bitmap snapshot() {
        try {
            int w = Math.max(1, reveal.getWidth() / 2), h = Math.max(1, reveal.getHeight() / 2);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(.5f, .5f);
            reveal.draw(c);
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            if (!running) return;
            long t = Math.max(0L, (nanos - t0) / 1_000_000L);
            apply(t);
            if (running) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void apply(long t) {
        Reveal r = rev;
        Flight f = flight;
        if (r == null || f == null) return;
        // the drawer
        float rad = r.radius(t);
        if (rad >= 0f) {
            cx = r.bx * d - reveal.getLeft();
            cy = r.by * d - reveal.getTop();
            cr = rad * d;
            if (!reveal.getClipToOutline()) reveal.setClipToOutline(true);
            reveal.invalidateOutline();
        } else if (cr >= 0f) {
            cr = -1f;
            reveal.setClipToOutline(card);
            reveal.invalidateOutline();
        }
        reveal.setTranslationY(r.shift(t) * d);
        if (frost.hasPicture()) frost.invalidate();          // the blurred screen stays put while the drawer moves
        // the title
        if (r.title == JoybotArrival.Title.LETTERS) {
            for (int j = 0; j < letters.size(); j++) {
                r.letter(t, j, tmp);
                letters.get(j).setAlpha(tmp[0]);
                letters.get(j).setTranslationY(tmp[1] * d);
            }
        } else if (r.title == JoybotArrival.Title.BUTTON) {
            r.titleOut(t, titleX, titleY, tmp);
            titleBlock.setPivotX(0f);
            titleBlock.setPivotY(titleBlock.getHeight() / 2f);
            titleBlock.setAlpha(tmp[0]);
            titleBlock.setTranslationX(tmp[1] * d);
            titleBlock.setTranslationY(tmp[2] * d);
            titleBlock.setScaleX(tmp[3]);
            titleBlock.setScaleY(tmp[3]);
        }
        // frost follows the opening on a full sheet (a card gets its blur when the window becomes the card)
        if (!card && frostOn()) blurTo(Math.round(14f * d * r.openness(t)));
        // Joybot takes his seat
        long settle = gl != null ? f.tSettle() : r.openedBy();
        if (!handedOff && t >= settle) handOff(t, gl != null);
        if (handedOff) chips.setAlpha(Flight.clamp((t - handT) / 220f, 0f, 1f));
        if (!opened && handedOff && t >= r.openedBy() && t >= handT + 240L) {
            opened = true;
            running = false;
            rest();
            frame.setEnabled(card);
            maybeWindow();
        }
    }

    private void handOff(long t, boolean flew) {
        handedOff = true;
        handT = t;
        // He has just made his entrance in the flight: no second arrival spin from his director.
        if (flew) actor.markArrived();
        actor.setCalmThruster(true);
        actor.setVisibility(View.VISIBLE);
        // On his seat he greets you: a salute or a bow (owner, 11-15). Both start from his home pose, the pose the
        // flight ends on, so the hand-over stays seamless.
        if (flew) com.fadcam.ui.lobby.JoybotDirector.moment(actor, "greet");
    }

    /** Everything at rest: no clip, no slide, the title in place. */
    private void rest() {
        cr = -1f;
        reveal.setClipToOutline(card);                       // a card's frost keeps the card's round corners
        reveal.invalidateOutline();
        reveal.setTranslationY(0f);
        reveal.setAlpha(1f);
        reveal.setScaleX(1f);
        reveal.setScaleY(1f);
        titleBlock.setAlpha(1f);
        titleBlock.setTranslationX(0f);
        titleBlock.setTranslationY(0f);
        titleBlock.setScaleX(1f);
        titleBlock.setScaleY(1f);
        for (TextView l : letters) { l.setAlpha(1f); l.setTranslationY(0f); }
        chips.setAlpha(1f);
        if (!card && frostOn()) blurTo(Math.round(14f * d));
        placeClose();
    }

    private void stageDone(boolean drew) {
        StageView v = gl;
        gl = null;
        if (v != null) root.removeView(v);
        stageOver = true;
        if (!handedOff) {
            long t = started ? (System.nanoTime() - t0) / 1_000_000L : 0L;
            handOff(t, false);
        }
        maybeWindow();
    }

    /** A card becomes its own window once he has arrived and the stage has gone. */
    private void maybeWindow() {
        if (!card || modal || closing || !opened || (gl != null && !stageOver)) return;
        frame.enterWindow(frostOn());
    }

    // ── closing ─────────────────────────────────────────────────────────────

    /**
     * Close: he flies home into the button he came out of (owner, 11-15 midday: "not just out") while the drawer folds
     * away the way it opened, then {@code done} runs (the activity's real finish). Returns false when there is nothing
     * to animate.
     */
    public boolean close(@NonNull Runnable done) {
        if (closing) return true;
        closing = true;
        running = false;
        StageView v = gl;
        if (v != null) v.stop();
        if (!started || reveal.getWidth() == 0) return false;
        HomeFlight home = flyHome(done);
        if (home == null) fold(0L, done);
        else fold(home.flightMs() * 2L / 5L, null);
        return true;
    }

    /** He flies home from wherever he is, over everything; null when he can't (not on his seat yet, no pictures). */
    @Nullable
    private HomeFlight flyHome(@NonNull Runnable done) {
        if (!handedOff || idle == null || actor.getVisibility() != View.VISIBLE || actor.getWidth() == 0) return null;
        int[] s = new int[2];
        stage.getLocationOnScreen(s);
        float fx = s[0] + actor.getLeft() + actor.getTranslationX() + actor.getWidth() / 2f;
        float fy = s[1] + actor.getTop() + actor.getTranslationY() + actor.getHeight() / 2f;
        Rect sb = a.getIntent().getSourceBounds();
        float tx, ty;
        if (sb != null) {
            tx = sb.exactCenterX();
            ty = sb.exactCenterY();
        } else {
            tx = a.getResources().getDisplayMetrics().widthPixels - 30f * d;
            ty = 30f * d;
        }
        // A card is its own small window: it goes back to a full one (the card staying where it is) so he can leave it.
        frame.leaveWindowInPlace();
        HomeFlight home;
        try {
            home = new HomeFlight(a, idle, idleCols, idleRows, spin, spinCols, spinRows, spinCount, focus, ACTOR_DP);
        } catch (RuntimeException e) {
            return null;
        }
        root.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        actor.setVisibility(View.INVISIBLE);
        home.go(fx, fy, tx, ty, done);
        Log.i(TAG, "home: " + home.flightMs() + " ms" + (card ? ", card" : ""));
        return home;
    }

    /** The drawer closes the way it opened, quickly, after {@code delay} ms; then {@code then}, if any. */
    private void fold(long delay, @Nullable Runnable then) {
        final JoybotArrival.Reveal kind = rev != null ? rev.kind : JoybotArrival.reveal(a);
        final float bxp = rev != null ? rev.bx * d - reveal.getLeft() : reveal.getWidth();
        final float byp = rev != null ? rev.by * d - reveal.getTop() : 0f;
        final float maxR = (float) Math.hypot(Math.max(bxp, reveal.getWidth() - bxp), Math.max(byp, reveal.getHeight() - byp)) + 20f * d;
        final long start = System.nanoTime() + delay * 1_000_000L;
        final long dur = 220L;
        final boolean fade = card;
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long nanos) {
                if (nanos < start) { Choreographer.getInstance().postFrameCallback(this); return; }
                float k = Flight.clamp((nanos - start) / 1e6f / dur, 0f, 1f);
                float e = k * k;
                if (fade) {
                    reveal.setAlpha(1f - e);
                    reveal.setScaleX(1f - .04f * e);
                    reveal.setScaleY(1f - .04f * e);
                } else if (kind == JoybotArrival.Reveal.CURTAIN) {
                    reveal.setTranslationY(-e * (reveal.getHeight() + reveal.getTop()));
                } else {
                    cx = bxp;
                    cy = byp;
                    cr = Math.max(0f, maxR * (1f - e));
                    if (!reveal.getClipToOutline()) reveal.setClipToOutline(true);
                    reveal.invalidateOutline();
                }
                if (!card && frostOn()) blurTo(Math.round(14f * d * (1f - e)));
                if (k < 1f) Choreographer.getInstance().postFrameCallback(this);
                else if (then != null) then.run();
            }
        });
    }

    /**
     * On a full sheet the X sits where he came out (owner, 11-15 midday: "upper right, under the thumb that opened
     * him"), so the same thumb sends him home.
     */
    private void placeClose() {
        if (closeBtn == null) return;
        closeBtn.setTranslationX(0f);
        closeBtn.setTranslationY(0f);
        Rect sb = a.getIntent().getSourceBounds();
        if (card || sb == null || closeBtn.getWidth() == 0 || !(closeBtn.getParent() instanceof View)) return;
        int[] p = new int[2];
        closeBtn.getLocationOnScreen(p);
        float dx = sb.exactCenterX() - (p[0] + closeBtn.getWidth() / 2f);
        float dy = sb.exactCenterY() - (p[1] + closeBtn.getHeight() / 2f);
        if (Math.abs(dx) > 96f * d || Math.abs(dy) > 72f * d) return;      // the button is not up in this corner
        View parent = (View) closeBtn.getParent();
        dx = Math.min(dx, parent.getWidth() - closeBtn.getRight());          // inside the header, so it can be tapped
        dy = Math.max(dy, -closeBtn.getTop());
        closeBtn.setTranslationX(dx);
        closeBtn.setTranslationY(dy);
    }

    /** The window turned or changed size: end any show, and lay the chat out for the new window. */
    public void onConfigurationChanged() {
        StageView v = gl;
        if (v != null) v.stop();
        if (started && !handedOff) handOff(0L, false);
        running = false;
        opened = true;
        rest();
        frame.leaveWindow();
        card = JoybotArrival.isCard(a);
        style();
        root.post(() -> {
            if (card) frame.place(frame.bounds(null));
            root.post(() -> { placeActor(); placeClose(); maybeWindow(); });
        });
    }

    public void onDestroy() {
        running = false;
        StageView v = gl;
        if (v != null) v.stop();
    }

    // ── frost ───────────────────────────────────────────────────────────────

    private boolean frostOn() { return JoybotArrival.frost(a); }

    private void blurTo(int px) {
        if (px == lastBlur) return;
        lastBlur = px;
        blur(a, px);
    }

    /** True when the phone offers Android's own window blur (Android 12 and up, and switched on). */
    static boolean blurOffered(@NonNull Activity a) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            WindowManager wm = a.getSystemService(WindowManager.class);
            return wm != null && wm.isCrossWindowBlurEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Window background blur (Android 12 and up, when the phone allows it); a no-op otherwise. */
    static void blur(@NonNull Activity a, int px) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            WindowManager wm = a.getSystemService(WindowManager.class);
            if (wm == null || !wm.isCrossWindowBlurEnabled()) return;
            Window w = a.getWindow();
            if (w != null) w.setBackgroundBlurRadius(Math.max(0, px));
        } catch (RuntimeException ignored) {
            // Blur is a nicety; never an error.
        }
    }
}
