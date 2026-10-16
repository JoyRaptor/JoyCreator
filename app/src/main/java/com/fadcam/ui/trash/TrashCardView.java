package com.fadcam.ui.trash;

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

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.library.CornerCut;
import com.fadcam.ui.library.LibraryKindStyle;
import com.fadcam.ui.library.LibraryThumbs;
import com.fadcam.ui.library.LibraryViews;
import com.fadcam.ui.type.Type;

/**
 * One deleted thing: the Library's grid card (4:3 picture with the kind's corner cut, the name, a mono
 * meta line) with the time left in the meta, and a choose mark in the picture's top corner.
 *
 * <p>The mark shows only while choosing. Chosen is the owner's ON: a solid cyan disc with the tick
 * cut out of it; not chosen is a quiet ring. A chosen card's picture steps back so the mark leads.</p>
 */
@SuppressLint("ViewConstructor")
final class TrashCardView extends LinearLayout {

    private final ImageView picture;
    private final TextView glyph, name, meta, mark;
    private final CornerCut cut;
    private final int thumbPx;
    private TrashEntry bound;

    TrashCardView(@NonNull Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(LibraryViews.pressable(Studio.PANEL, LibraryViews.dp(c, 12)));
        setClipToOutline(true);
        setClickable(true);
        setFocusable(true);
        SheetKit.press(this);
        cut = new CornerCut(Studio.INK_FAINT, Studio.INK_OFF);
        thumbPx = LibraryViews.dp(c, 240);

        FrameLayout wrap = new AspectFrame(c);
        wrap.setBackgroundColor(Studio.LANE_B);
        glyph = LibraryViews.glyph(c, 28f, Studio.INK_OFF);
        wrap.addView(glyph, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        picture = new ImageView(c);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wrap.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View corner = new View(c);
        corner.setBackground(cut);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(LibraryViews.dp(c, 22), LibraryViews.dp(c, 22));
        cLp.gravity = Gravity.BOTTOM | Gravity.END;
        wrap.addView(corner, cLp);
        mark = LibraryViews.glyph(c, 15f, Studio.GROUND);
        FrameLayout.LayoutParams mLp = new FrameLayout.LayoutParams(LibraryViews.dp(c, 22), LibraryViews.dp(c, 22));
        mLp.gravity = Gravity.TOP | Gravity.END;
        mLp.setMargins(0, LibraryViews.dp(c, 7), LibraryViews.dp(c, 7), 0);
        wrap.addView(mark, mLp);
        addView(wrap, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        name = LibraryViews.line(c, 11f, Studio.INK, false, Type.SEMIBOLD);
        name.setPadding(LibraryViews.dp(c, 9), LibraryViews.dp(c, 7), LibraryViews.dp(c, 9), 0);
        addView(name);
        meta = LibraryViews.line(c, 8.5f, Studio.INK_FAINT, true, Type.MEDIUM);
        meta.setPadding(LibraryViews.dp(c, 9), LibraryViews.dp(c, 3), LibraryViews.dp(c, 9), LibraryViews.dp(c, 9));
        addView(meta);
    }

    /** The whole card: picture, words, mark. */
    void bind(@NonNull TrashEntry e, @NonNull TrashExpiry x, boolean choosing, boolean chosen) {
        Context c = getContext();
        if (bound != e) {
            bound = e;
            int[] pair = LibraryKindStyle.colours(c, e.look.kind);
            cut.setColours(pair[0], pair[1]);
            name.setText(e.look.name);
            LibraryThumbs.bind(e.look, picture, glyph, thumbPx);
        }
        meta.setText(TrashText.meta(c, e, x));
        paintChoice(e, x, choosing, chosen);
    }

    /** Only the mark and the description: a tap that chooses must not reload the picture. */
    void paintChoice(@NonNull TrashEntry e, @NonNull TrashExpiry x, boolean choosing, boolean chosen) {
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
        String what = TrashText.describe(c, e, x, chosen);
        setContentDescription(what);
        // The platform call, not TooltipCompat: below API 26 the compat helper takes over the
        // long-press, and on a card the long-press is how choosing starts.
        if (android.os.Build.VERSION.SDK_INT >= 26) setTooltipText(what);
    }

    /** A frame three quarters as tall as it is wide, as the Library's cards are. */
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
