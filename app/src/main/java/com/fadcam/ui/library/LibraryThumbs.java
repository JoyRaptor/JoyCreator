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

    /** A content:// or file:// link, or an absolute path, as something Glide can open. */
    @Nullable
    private static Object modelOf(@Nullable String thumb) {
        if (thumb == null || thumb.isEmpty()) return null;
        if (thumb.startsWith("/")) return new File(thumb);
        return Uri.parse(thumb);
    }
}
