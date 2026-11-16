package com.fadcam.ui.library;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.fadcam.R;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.text.FontLibrary;

import java.io.File;

/**
 * The picture on a card, through Glide (already the app's image loader: it recycles with the list,
 * caches, and draws a video's frame as readily as a photo's pixels).
 *
 * <p>Until a picture lands, and wherever there is none, the kind's glyph shows, so a missing file is
 * never an error on the screen. The glyph steps aside once the picture is in, because a sprite
 * sheet's transparent pixels would otherwise show it through. A font draws its own specimen.</p>
 */
public final class LibraryThumbs {

    /** One second in: a recording's first frame is very often black (the lobby's rule too). */
    private static final long FRAME_US = 1_000_000L;

    private LibraryThumbs() {}

    /**
     * @param picture the image view, laid over {@code glyph}
     * @param glyph   the kind's glyph (or a font's specimen), shown wherever the picture is not
     * @param sizePx  the longest side to decode at
     */
    public static void bind(@NonNull LibraryItem it, @NonNull ImageView picture, @NonNull TextView glyph,
                     int sizePx) {
        Glide.with(picture).clear(picture);
        picture.setImageDrawable(null);
        glyph.setTypeface(SheetKit.icons(glyph.getContext()));
        glyph.setText(LibraryKindStyle.glyph(it.kind));
        glyph.setVisibility(View.VISIBLE);

        if (it.fileKind == FileKind.FONT && it.fontPath != null) {
            Typeface face = FontLibrary.typefaceForFile(it.fontPath);
            if (face != null) {
                glyph.setTypeface(face);
                glyph.setText(R.string.library_font_sample);
            }
            return;
        }
        Object model = modelOf(it.thumb);
        if (model == null) return;
        if (it.thumbSheet != null && com.fadcam.joyfolder.SheetName.parse(it.thumbSheet) != null) {
            bindSheet(it, model, picture, glyph, sizePx);
            return;
        }
        RequestOptions o = new RequestOptions()
                .centerCrop()
                .override(sizePx, sizePx)
                .frame(FRAME_US)
                // A drawing's thumbnail is redrawn in place as the drawing changes.
                .signature(new ObjectKey(it.thumb + "|" + it.modified))
                .dontAnimate();
        Glide.with(picture).load(model).apply(o).listener(new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object m,
                                        @NonNull Target<Drawable> t, boolean first) {
                return false;
            }

            @Override
            public boolean onResourceReady(@NonNull Drawable d, @NonNull Object m, Target<Drawable> t,
                                           @NonNull DataSource s, boolean first) {
                glyph.setVisibility(View.INVISIBLE);
                return false;
            }
        }).into(picture);
    }

    /**
     * A moving picture: the whole sheet, played a frame at a time ({@link SheetDrawable}; it plays only while
     * the card is on screen). Cards crop it like any picture.
     */
    private static void bindSheet(@NonNull LibraryItem it, @NonNull Object model, @NonNull ImageView picture,
                                  @NonNull TextView glyph, int sizePx) {
        // R3-LIB-01: the whole sheet, but only as large as its cells need: the card's size times the grid, so every
        // frame still has the card's own resolution (fitted to the card's size, the whole sheet would shrink every
        // frame with it). A 4096 px sheet of 8 x 8 cells for a 128 px card decodes at about 1024 px, not 4096.
        com.fadcam.joyfolder.SheetName grid = com.fadcam.joyfolder.SheetName.parse(it.thumbSheet);
        int cell = Math.max(1, sizePx);
        int w = grid == null ? Target.SIZE_ORIGINAL : Math.max(cell, grid.cols * cell);
        int h = grid == null ? Target.SIZE_ORIGINAL : Math.max(cell, grid.rows * cell);
        Glide.with(picture).asBitmap().load(model)
                .apply(new RequestOptions().signature(new ObjectKey(it.thumb + "|" + it.modified + "|" + w + "x" + h))
                        .dontTransform().override(w, h))
                .into(new com.bumptech.glide.request.target.CustomViewTarget<ImageView, android.graphics.Bitmap>(picture) {
                    @Override
                    public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                                @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> t) {
                        SheetDrawable d = SheetDrawable.of(bmp, it.thumbSheet);
                        if (d == null) return;
                        view.setImageDrawable(d);
                        glyph.setVisibility(View.INVISIBLE);
                        if (view.isShown()) d.start();
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    }

                    @Override
                    protected void onResourceCleared(@Nullable Drawable placeholder) {
                        Drawable d = view.getDrawable();
                        if (d instanceof SheetDrawable) ((SheetDrawable) d).stop();
                        view.setImageDrawable(null);
                    }
                });
    }

    /** A content:// or file:// link, or an absolute path, as something Glide can open. */
    @Nullable
    private static Object modelOf(@Nullable String thumb) {
        if (thumb == null || thumb.isEmpty()) return null;
        if (thumb.startsWith("/")) return new File(thumb);
        return Uri.parse(thumb);
    }
}
