package com.fadcam.vislab.studio;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.overlay.TextOverlayRenderer;
import com.fadcam.vislab.gl.VisRenderer;
import com.fadcam.vislab.room.Pictures;

import java.util.Collections;
import java.util.Map;

/**
 * Pictures for a look drawn in Studio: VisLab's own ({@link Pictures}: the Joy emblem, a picked
 * file, a Studio picture's file) plus Studio titles shown in a picture layer (roadmap R9), drawn
 * by Studio's own title renderer so they look exactly as on the timeline. Called on the GL
 * thread when a layer's picture reference changes.
 */
final class StudioPictures implements VisRenderer.ImageSource {

    /** Titles are drawn as for a 720-tall frame: sharp enough, and a small texture. */
    private static final int TEXT_FRAME_H = 720;

    @NonNull private final Pictures pictures;
    @NonNull private volatile Map<String, TextOverlayItem> texts = Collections.emptyMap();

    StudioPictures(@NonNull Context c) {
        pictures = new Pictures(c);
    }

    /** The titles the current frame's references name (VisShow.textItems). */
    void setTexts(@Nullable Map<String, TextOverlayItem> t) {
        texts = t != null ? t : Collections.emptyMap();
    }

    @Nullable
    @Override
    public Bitmap bitmap(@Nullable String ref) {
        if (ref != null && ref.startsWith(VisShow.TEXT)) {
            TextOverlayItem o = texts.get(ref);
            if (o == null) return null;
            try {
                return TextOverlayRenderer.render(o, TEXT_FRAME_H * 16 / 9, TEXT_FRAME_H);
            } catch (RuntimeException e) {
                return null;   // a title that cannot draw shows nothing, never breaks the look
            }
        }
        return pictures.bitmap(ref);
    }
}
