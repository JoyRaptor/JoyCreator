package com.fadcam.ui.joybot;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.motion.Motion;

import java.util.Random;

/**
 * JOY CREATOR'S ON/OFF SWITCH — Joybot is the thumb.
 *
 * <p>Replaces FadCam's {@code AvatarToggleView} (the eye with the floating zZ), which the owner
 * called "clever and fun but part of FadCam's branding". Same public API, so a call site moves
 * over by changing the class name and nothing else.
 *
 * <h3>The look</h3>
 * A flat pill, 48:28. The thumb is a PORTHOLE — a black disc cut out of the pill — and Joybot
 * sits in it. OFF: zinc-grey pill, porthole on the left, Joybot neutral in dim ink: the lights
 * are off. ON: the pill floods solid cyan, the porthole slides right, Joybot lights up in the
 * same cyan and smiles, sinking 5% into the seat as he does (his own note: "his body moves down
 * about 5% when smiling"). That is the app's ON rule — solid cyan with the glyph cut out —
 * with Joybot as the glyph. His violet orb is deliberately NOT used: it is "the only gradient
 * allowed in the chrome, he is a character, not a control", and a settings page of violet orbs
 * would be the noise the grey palette exists to remove.
 *
 * <h3>Size</h3>
 * Takes its size from layout_width/height like AvatarToggleView: the pill is fitted, centred,
 * into whatever box it is given, so the legacy 52x52 boxes give a 52x30 pill with a 52dp target.
 * wrap_content is a 48x28 pill in a 48x48 touch target.
 *
 * <h3>Motion</h3>
 * One interruptible 220ms ease-out drives travel, colour, face and dip together; a second tap
 * mid-flight retargets instead of restarting. Press: 140ms, scale .97. When ON he stirs every
 * 9–16s (a small rise and lean, compositor properties only), and only one switch on screen may
 * stir at a time so a page of them reads as one character, not a field of twitching. Torn down
 * off-screen and when detached. Reduced motion: no travel, scale or stirring; colour and face
 * still cross-fade.
 */
public class JoybotSwitch extends ViewGroup {

    /** Pill proportions, from the design direction (48 x 28dp). */
    private static final float PILL_W = 48f, PILL_H = 28f;
    /** Gap between the pill's edge and the porthole, as a share of the pill height. */
    private static final float INSET = 2.5f / 28f;
    /** Smile dip, as a fraction of his body height (JoybotView.DIP_FRACTION). */
    private static final float DIP = 0.05f;
    /** Idle stir: how far he rises back from the dip (fraction of body), and how far he leans. */
    private static final float STIR_RISE = 0.035f;
    private static final float STIR_LEAN_DEG = -5f;
    private static final long STIR_MIN_MS = 9000L, STIR_SPREAD_MS = 7000L;
    /** No two switches stir within this of each other. */
    private static final long STIR_GAP_MS = 2500L;
    private static long lastStirAt = 0L;

    private static final ArgbEvaluator ARGB = new ArgbEvaluator();
    private static final Random RANDOM = new Random();

    private final JoybotSwitchFace face;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private float inset, knob, travel;
    private final float density;

    private boolean checked = false;
    /** 0 = off, 1 = on. Drives colour and face; also position unless {@link #snapTravel}. */
    private float t = 0f;
    private boolean snapTravel = false;
    @Nullable private ValueAnimator anim;
    @Nullable private CompoundButton.OnCheckedChangeListener listener;
    private boolean pressDim = false;
    @Nullable private CharSequence autoTip;

    private final Runnable stir = this::stir;

    public JoybotSwitch(Context c) { this(c, null); }
    public JoybotSwitch(Context c, @Nullable AttributeSet a) { this(c, a, 0); }

    public JoybotSwitch(Context c, @Nullable AttributeSet a, int def) {
        super(c, a, def);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        // A switch is clickable and focusable unless the layout says otherwise
        // (fragment_torch_tool.xml sets both false and turns them on in code).
        String ns = "http://schemas.android.com/apk/res/android";
        setClickable(a == null || a.getAttributeBooleanValue(ns, "clickable", true));
        setFocusable(a == null || a.getAttributeBooleanValue(ns, "focusable", true));
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * density);
        face = new JoybotSwitchFace(c);
        addView(face);
        refreshState();
    }

    // ── the API AvatarToggleView callers use ─────────────────────────────────

    public boolean isChecked() { return checked; }

    /** Programmatic: instant, listener not fired. No-op when already in that state. */
    public void setChecked(boolean on) { setChecked(on, false); }

    /** Programmatic, optionally animated; listener not fired. No-op when already in that state. */
    public void setChecked(boolean on, boolean animate) {
        if (on == checked) return;
        checked = on;
        if (animate && isAttachedToWindow()) animateTo(on ? 1f : 0f); else jumpTo(on ? 1f : 0f);
        refreshState();
    }

    /** Called only for user taps (and {@link #performClick()}). The button argument is null. */
    public void setOnCheckedChangeListener(@Nullable CompoundButton.OnCheckedChangeListener l) {
        listener = l;
    }

    /** A user tap: flips with animation, fires the listener, then any click listener. */
    @Override
    public boolean performClick() {
        // Unlike AvatarToggleView, a disabled switch does not flip when its row is tapped.
        if (!isEnabled()) return false;
        checked = !checked;
        animateTo(checked ? 1f : 0f);
        refreshState();
        if (listener != null) listener.onCheckedChanged(null, checked);
        return super.performClick();
    }

    @Override
    public void setContentDescription(CharSequence d) {
        super.setContentDescription(d);
        refreshState();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        applyLook();
    }

    // ── state ────────────────────────────────────────────────────────────────

    private void jumpTo(float target) {
        if (anim != null) anim.cancel();
        anim = null;
        t = target;
        applyLook();
        scheduleStir();
    }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        unStir();
        boolean reduced = Motion.reduced(getContext());
        snapTravel = reduced;
        float dist = Math.abs(target - t);
        if (dist == 0f) { jumpTo(target); return; }
        long ms = reduced ? Motion.SWAP : Math.max(60L, Math.round(Motion.MENU * dist));
        ValueAnimator a = ValueAnimator.ofFloat(t, target);
        a.setDuration(ms);
        a.setInterpolator(Motion.EASE_OUT);
        a.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); applyLook(); });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                if (anim != x) return;
                anim = null;
                snapTravel = false;
                applyLook();
                scheduleStir();
            }
        });
        anim = a;
        a.start();
    }

    /** Pushes t into the drawing: track colour, porthole and Joybot position, face, dip. */
    private void applyLook() {
        if (face == null) return;   // a super-constructor callback, before the face exists
        float pos = snapTravel ? (checked ? 1f : 0f) : t;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        face.setTranslationX(travel * (rtl ? -pos : pos));
        float dipT = clamp((pos - 0.5f) / 0.5f);
        face.setTranslationY(Math.round(face.bodyHeight(Math.round(knob)) * DIP * dipT));
        int ink = (int) ARGB.evaluate(t, Studio.INK_DIM, Studio.ARMED);
        float mix = clamp((t - 0.35f) / 0.4f);
        face.setLook(ink, mix, lookFade());
        invalidate();
    }

    private float lookFade() { return !isEnabled() ? 0.38f : pressDim ? 0.72f : 1f; }

    private void refreshState() {
        ViewCompat.setStateDescription(this, getContext().getString(
                checked ? R.string.joybotswitch_state_on : R.string.joybotswitch_state_off));
        // Hover label (stylus / mouse). Only ours to manage if the caller has not set one.
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence current = getTooltipText();
            if (current == null || TextUtils.equals(current, autoTip)) {
                CharSequence name = getContentDescription();
                autoTip = TextUtils.isEmpty(name)
                        ? getContext().getString(checked ? R.string.joybotswitch_tip_on
                                                         : R.string.joybotswitch_tip_off)
                        : getContext().getString(checked ? R.string.joybotswitch_tip_on_named
                                                         : R.string.joybotswitch_tip_off_named, name);
                setTooltipText(autoTip);
            }
        }
    }

    // ── idle stir ────────────────────────────────────────────────────────────

    private void scheduleStir() {
        removeCallbacks(stir);
        if (!checked || !isAttachedToWindow() || !isShown() || Motion.reduced(getContext())) return;
        postDelayed(stir, STIR_MIN_MS + RANDOM.nextInt((int) STIR_SPREAD_MS));
    }

    private void stir() {
        if (!checked || anim != null || !isShown()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastStirAt < STIR_GAP_MS) { postDelayed(stir, STIR_GAP_MS); return; }
        lastStirAt = now;
        float rest = face.getTranslationY();
        float rise = Math.round(face.bodyHeight(Math.round(knob)) * STIR_RISE);
        face.setPivotX(face.getWidth() / 2f);
        face.setPivotY(face.getHeight());
        face.animate().translationY(rest - rise).rotation(STIR_LEAN_DEG)
                .setDuration(Motion.HERO).setInterpolator(Motion.EASE_OUT)
                .withEndAction(() -> face.animate().translationY(rest).rotation(0f)
                        .setDuration(420L).setInterpolator(Motion.EASE_IN_OUT)
                        .withEndAction(this::scheduleStir).start())
                .start();
    }

    private void unStir() {
        removeCallbacks(stir);
        face.animate().cancel();
        face.setRotation(0f);
    }

    // ── press ────────────────────────────────────────────────────────────────

    @Override
    public void setPressed(boolean pressed) {
        boolean was = isPressed();
        super.setPressed(pressed);
        if (was == pressed) return;
        if (Motion.reduced(getContext())) {
            // Reduced motion: no scale; the press is said with opacity, drawn inside the
            // switch so it never fights an alpha the caller has set on the view.
            pressDim = pressed;
            applyLook();
            return;
        }
        float s = pressed ? Motion.PRESS_SCALE : 1f;
        animate().scaleX(s).scaleY(s).setDuration(Motion.PRESS)
                .setInterpolator(Motion.EASE_OUT).start();
    }

    // ── size, layout, drawing ────────────────────────────────────────────────

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // wrap_content: a 48x28 pill in a 48x48 touch target.
        int touch = Math.round(48f * density);
        int w = resolveSize(Math.round(PILL_W * density) + getPaddingLeft() + getPaddingRight(), wSpec);
        int h = resolveSize(Math.max(touch, Math.round(PILL_H * density))
                + getPaddingTop() + getPaddingBottom(), hSpec);
        setMeasuredDimension(Math.max(w, getSuggestedMinimumWidth()),
                Math.max(h, getSuggestedMinimumHeight()));
    }

    @Override
    protected void onLayout(boolean changed, int l, int top, int r, int b) {
        float cw = getWidth() - getPaddingLeft() - getPaddingRight();
        float ch = getHeight() - getPaddingTop() - getPaddingBottom();
        float pw = Math.min(cw, ch * PILL_W / PILL_H);
        float ph = pw * PILL_H / PILL_W;
        float x0 = getPaddingLeft() + (cw - pw) / 2f;
        float y0 = getPaddingTop() + (ch - ph) / 2f;
        pill.set(x0, y0, x0 + pw, y0 + ph);
        inset = ph * INSET;
        knob = ph - 2f * inset;
        travel = pw - 2f * inset - knob;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int k = Math.round(knob);
        int left = Math.round(rtl ? pill.right - inset - knob : pill.left + inset);
        int topK = Math.round(pill.top + inset);
        if (face.getWidth() != k) face.setDiameter(k);
        face.layout(left, topK, left + k, topK + k);
        applyLook();
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        if (pill.isEmpty()) return;
        float fade = lookFade();
        float r = pill.height() / 2f;
        fill.setColor((int) ARGB.evaluate(t, Studio.OFF, Studio.ARMED));
        fill.setAlpha(Math.round(255 * fade));
        c.drawRoundRect(pill, r, r, fill);
        // The porthole: a disc cut out of the pill.
        float pos = snapTravel ? (checked ? 1f : 0f) : t;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float cx = rtl ? pill.right - inset - knob / 2f - travel * pos
                       : pill.left + inset + knob / 2f + travel * pos;
        fill.setColor(Studio.GROUND);
        fill.setAlpha(Math.round(255 * fade));
        c.drawCircle(cx, pill.centerY(), knob / 2f, fill);
        // Keyboard / stylus focus. Cyan on the grey pill; ink on the cyan one, where cyan vanishes.
        if (isFocused() && !isInTouchMode()) {
            ring.setColor(checked ? Studio.INK : Studio.ARMED);
            float o = ring.getStrokeWidth() / 2f;
            c.drawRoundRect(pill.left + o, pill.top + o, pill.right - o, pill.bottom - o,
                    r - o, r - o, ring);
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleStir();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) { anim.cancel(); anim = null; }
        t = checked ? 1f : 0f;
        snapTravel = false;
        unStir();
        animate().cancel();
        setScaleX(1f);
        setScaleY(1f);
        applyLook();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible) scheduleStir(); else unStir();
    }

    // ── accessibility: a real switch to TalkBack ─────────────────────────────

    @Override
    public CharSequence getAccessibilityClassName() { return android.widget.Switch.class.getName(); }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(checked);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent e) {
        super.onInitializeAccessibilityEvent(e);
        e.setChecked(checked);
    }

    // ── migration helper ─────────────────────────────────────────────────────

    /**
     * Swaps a view already in a layout (an inflated AvatarToggleView) for a JoybotSwitch with
     * the same id, layout params, visibility and content description, at the same position.
     * Lets a shared legacy layout show the new switch on the new screens only.
     */
    @NonNull
    public static JoybotSwitch replace(@NonNull View old) {
        JoybotSwitch s = new JoybotSwitch(old.getContext());
        s.setId(old.getId());
        s.setVisibility(old.getVisibility());
        s.setContentDescription(old.getContentDescription());
        if (old.getParent() instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) old.getParent();
            int i = p.indexOfChild(old);
            ViewGroup.LayoutParams lp = old.getLayoutParams();
            p.removeViewAt(i);
            if (lp != null) p.addView(s, i, lp); else p.addView(s, i);
        }
        return s;
    }

    private static float clamp(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }
}
