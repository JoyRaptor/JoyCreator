package com.fadcam.ui.faditor.tools;

import com.fadcam.ui.faditor.Studio;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Home of {@link Kit}, the drawer control set FxPanel, the pickers and the object sheet build
 * with.
 *
 * <p>The TEXT DRAWER SHELL that used to live here is gone (2026-09-23): text opens in the one
 * {@link ObjectDrawer} every object uses, with Text · Transform · Effects · Lanes tabs, so a
 * second shell with its own header, grip, peek and height logic had nothing left to do. The
 * class keeps its name only so the Kit's callers need not change.</p>
 */
public final class TextOverlayDrawer {

    private TextOverlayDrawer() {}

    /**
     * THE DRAWER KIT — record 06's drawer controls, built in ONE place.
     *
     * <p>JoyRaptor: "things calling helpers instead of hard coded so there are fewer areas to
     * break." Before this, the effects panel, the two pickers and the object sheet each built
     * their own chip, their own section heading and their own slider, at four sizes, three radii
     * and five greys — so a correction to one never reached the others. Every value below is
     * record 06 (UI-Studio-final.html) §02/§04, cited at the line that uses it.</p>
     *
     * <p>It lives in this file only because this lane may not add Java files; it is a pure static
     * toolbox with no tie to TextOverlayDrawer and can move to its own {@code DrawerKit.java}
     * unchanged. {@code ObjectDrawer}, {@code PipDrawerTabs}, {@code AudioDrawerTabs} and
     * {@code PuppetDrawerTabs} are the obvious next callers.</p>
     *
     * <p>No colour here is new. Each is an existing {@link Studio} token, with record 06's alpha
     * applied through {@link Studio#alpha} — {@code rgba(255,255,255,.10)} is the drawer's own
     * ink at 10%, because the drawer ink IS this app's white.</p>
     */
    public static final class Kit {

        private Kit() { }

        /** {@code --scrim} rgba(0,0,0,.64). 6.63:1 body ink over a blown-out frame. */
        public static final int SCRIM = Studio.alpha(Studio.GROUND, 0xA3);
        /** {@code --dctl} rgba(255,255,255,.10): a control's fill on the scrim. */
        public static final int CTL   = Studio.alpha(Studio.DRAWER_INK, 0x1A);
        /** {@code --dring} rgba(255,255,255,.12): the 1dp ring an unselected control wears. */
        public static final int RING  = Studio.alpha(Studio.DRAWER_INK, 0x1F);
        /** {@code --edge} rgba(255,255,255,.10): the only border a drawer surface has. */
        public static final int EDGE  = Studio.alpha(Studio.DRAWER_INK, 0x1A);
        /** {@code .dsl .tr} rgba(255,255,255,.18): an empty slider track. */
        public static final int TRACK = Studio.alpha(Studio.DRAWER_INK, 0x2E);
        /** {@code .dgrab span} rgba(255,255,255,.30): the grab pill. */
        public static final int GRAB  = Studio.alpha(Studio.DRAWER_INK, 0x4D);
        /** "Any press 140ms ease-out, scale(.97)". */
        public static final long PRESS_MS = 140L;
        /** Popovers "from scale(.95) never scale(0), 220ms ease-out". */
        public static final long POP_MS = 220L;

        /** {@code --ease-out} cubic-bezier(.23,1,.32,1). Never ease-in. */
        @NonNull
        public static android.view.animation.Interpolator easeOut() {
            return new android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f);
        }

        /** The drawer curve, cubic-bezier(.32,.72,0,1) — record 06 §04. */
        @NonNull
        public static android.view.animation.Interpolator drawerCurve() {
            return new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f);
        }

        private static int px(@NonNull Context c, float dp) {
            return Math.round(dp * c.getResources().getDisplayMetrics().density);
        }

        /**
         * Name a control for TalkBack AND for the hover tooltip a stylus or mouse shows — the
         * standing rule is that every button earns a hover label, and a glyph is not one.
         *
         * <p>Never on a view whose LONG PRESS is its own gesture: below API 26 the compat tooltip
         * is delivered through a long-click listener and would replace that gesture. Such views
         * take {@code setContentDescription} alone.</p>
         */
        public static void describe(@NonNull View v, @NonNull CharSequence label) {
            // One naming helper app-wide (drawer audit 2026-09-24, T2).
            ObjectDrawer.Kit.describe(v, label);
        }

        /**
         * Press feedback: scale(.97) over 140ms, on the pressed STATE. Delegated, so there is
         * one press in the drawers (drawer audit 2026-09-24, T2).
         */
        public static void pressable(@NonNull View v) {
            ObjectDrawer.Kit.pressable(v);
        }

        /**
         * THE chip — ObjectDrawer.Kit's {@code .dchip}, so the Effects tab and the
         * Transform/Mask tabs wear ONE face (drawer audit 2026-09-24, T2). Kept here for its
         * callers: this version also places the chip and gives it press feedback.
         */
        @NonNull
        public static TextView chip(@NonNull Context ctx, @NonNull CharSequence text) {
            TextView t = ObjectDrawer.Kit.chip(ctx, text);
            pressable(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            // `.dr` gap: 6px.
            lp.setMarginEnd(px(ctx, 6));
            t.setLayoutParams(lp);
            return t;
        }

        /**
         * Selected / not. Delegated (drawer audit 2026-09-24, T2): this used to draw a cyan RING
         * for "on" while every other drawer fills with the object's accent, so a selected chip
         * looked different on the Effects tab than on Transform. A popover over a drawer shares
         * that drawer's accent ({@link ObjectDrawer.Kit#accent()}).
         */
        public static void setChipOn(@NonNull TextView t, boolean on) {
            ObjectDrawer.Kit.setChipOn(t, on);
        }

        /**
         * The ONE primary action on a panel: the aqua-to-lime gradient, dark ink. "Gradient means
         * action" (Studio.java) and "one filled action per view" — so a panel calls this once.
         */
        public static void setPrimaryAction(@NonNull TextView t) {
            t.setBackgroundResource(com.fadcam.R.drawable.studio_action_pill);
            t.setTextColor(Studio.ON_GO);
            com.fadcam.ui.type.Type.body(t, com.fadcam.ui.type.Type.BOLD);
        }

        /**
         * A section label — record 06 {@code .dsec}: mono 8sp, tracked .14em, upper case, in
         * drawer-LABEL ink (#C4C4CE; the #A6A6B2 it replaced measured 3.49:1 — MAJOR 04).
         */
        @NonNull
        public static TextView sectionLabel(@NonNull Context ctx, @NonNull CharSequence text) {
            return ObjectDrawer.Kit.sectionLabel(ctx, text);   // drawer audit 2026-09-24, T2
        }

        /**
         * A live numeric readout — record 06 {@code .dscrub b}: mono 12sp w600, drawer ink,
         * tabular figures, so a value changing under a drag does not jitter sideways.
         */
        @NonNull
        public static TextView valueText(@NonNull Context ctx) {
            TextView t = new TextView(ctx);
            com.fadcam.ui.type.Type.mono(t, com.fadcam.ui.type.Type.SEMIBOLD);
            t.setTextSize(12f);
            t.setTextColor(Studio.DRAWER_INK);
            t.setFontFeatureSettings("tnum");
            t.setSingleLine(true);
            return t;
        }

        /**
         * THE slider look — record 06 {@code .dsl}: a 4dp track at white 18%, the filled part in
         * {@code fill}, a 15dp white thumb. Look only: the bar's range, listener and value are
         * the caller's and are not touched.
         */
        public static void styleSlider(@NonNull SeekBar bar, int fill) {
            ObjectDrawer.Kit.styleSlider(bar, fill);   // drawer audit 2026-09-24, T2
        }

        /**
         * A popover's surface: the drawer scrim, rounded, with the 1dp edge. Popovers float over
         * the canvas, so they are "over video" exactly as a drawer is, and take the same dark
         * glass rather than an opaque grey slab.
         */
        @NonNull
        public static GradientDrawable surface(@NonNull Context ctx, float radiusDp) {
            GradientDrawable g = new GradientDrawable();
            // The user's see-through setting, not a fixed 64%: a popover must not be darker
            // than the drawer it opened from (drawer audit 2026-09-24, T3).
            g.setColor(ObjectDrawer.Kit.drawerFill(ctx));
            g.setCornerRadius(px(ctx, radiusDp));
            g.setStroke(Math.max(1, px(ctx, 1)), EDGE);
            return g;
        }

        /**
         * A popover arriving: from scale(.95) and transparent to rest, 220ms ease-out — never
         * from scale(0), which reads as the thing being born rather than revealed. Transform and
         * opacity only.
         */
        public static void popIn(@NonNull View v) {
            v.setScaleX(0.95f);
            v.setScaleY(0.95f);
            v.setAlpha(0f);
            v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(POP_MS).setInterpolator(easeOut()).start();
        }

        /**
         * A drawn 18dp checkbox — record 06 {@code .dcb .bx}: radius 5, a 1.5dp ring in
         * {@code ring} when off; filled with {@code fill} and a dark check when on. Replaces
         * ☑ / ☐ text glyphs, which rendered in whatever face the OEM had and at text size.
         */
        @NonNull
        public static android.graphics.drawable.Drawable checkbox(@NonNull Context ctx,
                                                                   boolean on, int fill,
                                                                   int ring) {
            int box = px(ctx, 18);
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(px(ctx, 5));
            b.setSize(box, box);
            if (!on) {
                b.setColor(Studio.alpha(Studio.GROUND, 0));
                b.setStroke(Math.max(1, px(ctx, 1.5f)), ring);
                b.setBounds(0, 0, box, box);
                return b;
            }
            b.setColor(fill);
            android.graphics.drawable.Drawable check = androidx.core.content.ContextCompat
                    .getDrawable(ctx, com.fadcam.R.drawable.ic_check);
            if (check == null) { b.setBounds(0, 0, box, box); return b; }
            check = check.mutate();
            check.setTint(Studio.ON_GO);
            android.graphics.drawable.LayerDrawable ld =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{b, check});
            int inner = px(ctx, 13);
            ld.setLayerSize(0, box, box);
            ld.setLayerSize(1, inner, inner);
            ld.setLayerGravity(1, Gravity.CENTER);
            ld.setBounds(0, 0, box, box);
            return ld;
        }
    }
}
