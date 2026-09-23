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
            v.setContentDescription(label);
            androidx.core.view.ViewCompat.setTooltipText(v, label);
        }

        /**
         * Press feedback: scale(.97) over 140ms, ease-out, on the pressed STATE — a
         * StateListAnimator, so it never competes with a view's own touch listener. Transform
         * only, which is the one property that animates without a relayout.
         */
        public static void pressable(@NonNull View v) {
            android.animation.StateListAnimator sla = new android.animation.StateListAnimator();
            sla.addState(new int[]{android.R.attr.state_pressed}, scaleTo(v, 0.97f));
            sla.addState(new int[0], scaleTo(v, 1f));
            v.setStateListAnimator(sla);
        }

        @NonNull
        private static android.animation.Animator scaleTo(@NonNull View v, float s) {
            android.animation.ObjectAnimator a =
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, s),
                            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, s));
            a.setDuration(PRESS_MS);
            a.setInterpolator(easeOut());
            return a;
        }

        /**
         * THE chip — record 06 {@code .dchip}: 11sp w600, padding 7 × 13, radius 999, the
         * control fill with a 1dp inset ring, drawer-dim ink. Single line with an ellipsis,
         * never two (a two-line tap target is its own defect — record 06 MINOR 09).
         */
        @NonNull
        public static TextView chip(@NonNull Context ctx, @NonNull CharSequence text) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextSize(11f);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            int ph = px(ctx, 13), pv = px(ctx, 7);
            t.setPadding(ph, pv, ph, pv);
            setChipOn(t, false);
            pressable(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            // `.dr` gap: 6px.
            lp.setMarginEnd(px(ctx, 6));
            t.setLayoutParams(lp);
            return t;
        }

        /**
         * Selected / not, when the panel does not know the OBJECT's colour. "State colour always
         * a RING" (record 06 §04): cyan ring and full drawer ink when on; the control fill, its
         * faint ring and drawer-dim ink when off. Dim is #C9C9D3 — the OFF state stays readable,
         * which the old half-alpha grey never was.
         */
        public static void setChipOn(@NonNull TextView t, boolean on) {
            Context c = t.getContext();
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(c, 999));
            g.setColor(CTL);
            g.setStroke(on ? Math.max(1, px(c, 1.5f)) : Math.max(1, px(c, 1)),
                    on ? Studio.ARMED : RING);
            t.setBackground(g);
            t.setTextColor(on ? Studio.DRAWER_INK : Studio.DRAWER_DIM);
            com.fadcam.ui.type.Type.body(t, on ? com.fadcam.ui.type.Type.BOLD
                    : com.fadcam.ui.type.Type.SEMIBOLD);
            t.setSelected(on);
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
            TextView t = new TextView(ctx);
            t.setText(text);
            com.fadcam.ui.type.Type.mono(t, com.fadcam.ui.type.Type.MEDIUM);
            t.setTextSize(8f);
            t.setLetterSpacing(0.14f);
            t.setSingleLine(true);
            t.setAllCaps(true);   // after setSingleLine: both are TransformationMethods, last one wins
            t.setTextColor(Studio.DRAWER_LABEL);
            t.setPadding(0, px(ctx, 7), 0, px(ctx, 3));
            return t;
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
            Context c = bar.getContext();
            int h = Math.max(1, px(c, 4));
            GradientDrawable track = new GradientDrawable();
            track.setColor(TRACK);
            track.setCornerRadius(h / 2f);
            GradientDrawable on = new GradientDrawable();
            on.setColor(fill);
            on.setCornerRadius(h / 2f);
            android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                    on, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
            android.graphics.drawable.LayerDrawable ld =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{track, clip});
            ld.setId(0, android.R.id.background);
            ld.setId(1, android.R.id.progress);
            for (int i = 0; i < 2; i++) {
                ld.setLayerHeight(i, h);
                ld.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
            }
            int progress = bar.getProgress();
            bar.setProgressDrawable(ld);
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(Studio.DRAWER_INK);
            int t = px(c, 15);
            thumb.setSize(t, t);
            bar.setThumb(thumb);
            bar.setSplitTrack(false);
            // setProgressDrawable can drop the level on some API levels; put it back.
            bar.setProgress(progress);
        }

        /**
         * A popover's surface: the drawer scrim, rounded, with the 1dp edge. Popovers float over
         * the canvas, so they are "over video" exactly as a drawer is, and take the same dark
         * glass rather than an opaque grey slab.
         */
        @NonNull
        public static GradientDrawable surface(@NonNull Context ctx, float radiusDp) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(SCRIM);
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
                b.setColor(0x00000000);
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
