package com.fadcam.ui.library;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * One thing in the Library, in either density.
 *
 * <ul>
 *   <li><b>Grid</b>: a 4:3 picture with the kind's corner cut, the name, then
 *       {@code RECORDING · 2h ago} in mono. The lobby's recents card, grown to half the width.</li>
 *   <li><b>List</b>: a 48dp square picture, name over meta, the size at the far end. For scanning
 *       many things by name.</li>
 * </ul>
 *
 * A small inbox mark says "shared in". The card is one control: its parts are its description.
 */
@SuppressLint("ViewConstructor")
final class LibraryCardView extends LinearLayout {

    private final boolean list;
    private final ImageView picture;
    private final TextView glyph, name, meta, size, shared, mark;
    private final CornerCut cut;
    private final int thumbPx;

    LibraryCardView(@NonNull Context c, boolean list) {
        super(c);
        this.list = list;
        cut = new CornerCut(Studio.INK_FAINT, Studio.INK_OFF);
        picture = new ImageView(c);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        glyph = LibraryViews.glyph(c, list ? 20f : 28f, Studio.INK_OFF);
        name = LibraryViews.line(c, list ? 13f : 11f, Studio.INK, false, Type.SEMIBOLD);
        meta = LibraryViews.line(c, list ? 9f : 8.5f, Studio.INK_FAINT, true, Type.MEDIUM);
        size = LibraryViews.line(c, 9f, Studio.INK_FAINT, true, Type.REGULAR);
        shared = LibraryViews.glyph(c, list ? 13f : 11f, list ? Studio.INK_DIM : Studio.INK);
        shared.setText("move_to_inbox");
        thumbPx = LibraryViews.dp(c, list ? 96 : 240);
        mark = LibraryViews.glyph(c, list ? 12f : 15f, Studio.GROUND);
        SheetKit.press(this);
        setClickable(true);
        setFocusable(true);
        if (list) buildRow(c); else buildCard(c);
    }

    private void buildCard(@NonNull Context c) {
        setOrientation(VERTICAL);
        setBackground(LibraryViews.pressable(Studio.PANEL, LibraryViews.dp(c, 12)));
        setClipToOutline(true);
        FrameLayout wrap = new AspectFrame(c);
        wrap.setBackgroundColor(Studio.LANE_B);
        fillThumb(c, wrap, 22);
        shared.setBackground(LibraryViews.fill(Studio.alpha(Studio.GROUND, 0x99), LibraryViews.dp(c, 99)));
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(LibraryViews.dp(c, 20), LibraryViews.dp(c, 20));
        sLp.gravity = Gravity.TOP | Gravity.START;
        sLp.setMargins(LibraryViews.dp(c, 6), LibraryViews.dp(c, 6), 0, 0);
        wrap.addView(shared, sLp);
        addView(wrap, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        name.setPadding(LibraryViews.dp(c, 9), LibraryViews.dp(c, 7), LibraryViews.dp(c, 9), 0);
        addView(name);
        meta.setPadding(LibraryViews.dp(c, 9), LibraryViews.dp(c, 3), LibraryViews.dp(c, 9), LibraryViews.dp(c, 9));
        addView(meta);
    }

    private void buildRow(@NonNull Context c) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(LibraryViews.dp(c, 60));
        setPadding(LibraryViews.dp(c, 8), LibraryViews.dp(c, 6), LibraryViews.dp(c, 12), LibraryViews.dp(c, 6));
        setBackground(LibraryViews.pressable(Studio.GROUND, LibraryViews.dp(c, 10)));
        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackground(LibraryViews.fill(Studio.LANE_B, LibraryViews.dp(c, 8)));
        wrap.setClipToOutline(true);
        fillThumb(c, wrap, 12);
        addView(wrap, new LayoutParams(LibraryViews.dp(c, 48), LibraryViews.dp(c, 48)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setPadding(LibraryViews.dp(c, 12), 0, LibraryViews.dp(c, 8), 0);
        col.addView(name);
        meta.setPadding(0, LibraryViews.dp(c, 4), 0, 0);
        col.addView(meta);
        addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LayoutParams shLp = new LayoutParams(LibraryViews.dp(c, 20), LibraryViews.dp(c, 20));
        shLp.setMarginEnd(LibraryViews.dp(c, 6));
        addView(shared, shLp);
        addView(size);
    }

    private void fillThumb(@NonNull Context c, @NonNull FrameLayout wrap, int cutDp) {
        wrap.addView(glyph, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View corner = new View(c);
        corner.setBackground(cut);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(LibraryViews.dp(c, cutDp), LibraryViews.dp(c, cutDp));
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        wrap.addView(corner, lp);
        // The choose mark: only while choosing (paintChoice). Top end, where the Trash screen keeps it.
        int m = list ? 18 : 22, inset = list ? 3 : 7;
        FrameLayout.LayoutParams mLp = new FrameLayout.LayoutParams(LibraryViews.dp(c, m), LibraryViews.dp(c, m));
        mLp.gravity = Gravity.TOP | Gravity.END;
        mLp.setMargins(0, LibraryViews.dp(c, inset), LibraryViews.dp(c, inset), 0);
        mark.setVisibility(GONE);
        wrap.addView(mark, mLp);
    }

    void bind(@NonNull LibraryItem it, boolean choosing, boolean chosen) {
        bind(it);
        paintChoice(it, choosing, chosen);
    }

    /**
     * Only the mark and the description: a tap that chooses must not reload the picture. Chosen is the
     * owner's ON: a solid cyan disc with the tick cut out of it; not chosen is a quiet ring.
     */
    void paintChoice(@NonNull LibraryItem it, boolean choosing, boolean chosen) {
        Context c = getContext();
        mark.setVisibility(choosing ? VISIBLE : GONE);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        if (chosen) {
            disc.setColor(Studio.ARMED);
            mark.setText("check");
        } else {
            disc.setColor(Studio.alpha(Studio.GROUND, 0x66));
            disc.setStroke(Math.max(1, LibraryViews.dp(c, 1.5f)), Studio.INK);
            mark.setText("");
        }
        mark.setBackground(disc);
        float back = chosen ? 0.55f : 1f;
        picture.setAlpha(back);
        glyph.setAlpha(back);
        setSelected(chosen);
        String what = LibraryFormat.describe(c, it);
        if (choosing) what = c.getString(chosen ? R.string.library_chosen_desc : R.string.library_not_chosen_desc, what);
        setContentDescription(what);
        if (android.os.Build.VERSION.SDK_INT >= 26) setTooltipText(what);
    }

    void bind(@NonNull LibraryItem it) {
        Context c = getContext();
        int[] pair = LibraryKindStyle.colours(c, it.kind);
        cut.setColours(pair[0], pair[1]);
        name.setText(it.name);
        meta.setText(LibraryFormat.meta(c, it, list));
        shared.setVisibility(it.sharedIn ? VISIBLE : GONE);
        if (list) {
            String s = LibraryFormat.size(c, it.size);
            size.setText(s);
            size.setVisibility(s.isEmpty() ? GONE : VISIBLE);
        }
        LibraryThumbs.bind(it, picture, glyph, thumbPx);
        String what = LibraryFormat.describe(c, it);
        setContentDescription(what);
        // The platform call, not TooltipCompat: below API 26 the compat helper takes over the
        // long-press, and on a card the long-press is the item menu.
        if (android.os.Build.VERSION.SDK_INT >= 26) setTooltipText(what);
    }

    /** A frame three quarters as tall as it is wide, whatever width the grid gives it. */
    private static final class AspectFrame extends FrameLayout {
        AspectFrame(@NonNull Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(w * 3 / 4, MeasureSpec.EXACTLY));
        }
    }
}
