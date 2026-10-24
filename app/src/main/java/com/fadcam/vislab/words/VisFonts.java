package com.fadcam.vislab.words;

import android.content.Context;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.text.FontLibrary;
import com.fadcam.ui.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * The fonts a Words layer can be drawn in (roadmap R1): VisLab's own bold display font (the
 * default, key ""), Studio's built-in text fonts and every font the person imported, by the same
 * keys Studio's text and captions save ("popular", "file:/.../Name.ttf"), so one font list serves
 * the whole app. A look keeps the key in its layer ({@code "font": "<key>"}); a key that no longer
 * resolves (a deleted import) falls back to the default.
 */
public final class VisFonts {

    /** The default: VisLab's display font. */
    public static final String DEFAULT = "";

    private VisFonts() {}

    /** The typeface for {@code key} (null or "" = VisLab's display font). */
    @NonNull
    public static Typeface typeface(@NonNull Context c, @Nullable String key) {
        if (key == null || key.isEmpty()) {
            Typeface t = Type.display(c.getApplicationContext(), Type.EXTRA);
            return t != null ? t : Typeface.DEFAULT_BOLD;
        }
        if (key.startsWith("file:")) {
            FontLibrary.init(c);
            Typeface t = FontLibrary.typefaceForFile(key.substring(5));
            return t != null ? t : typeface(c, DEFAULT);
        }
        return TextOverlayItem.typefaceFor(key, false, false);
    }

    /** Every choice as {key, label}: the default first, Studio's built-ins, then imported fonts. */
    @NonNull
    public static List<String[]> choices(@NonNull Context c) {
        FontLibrary.init(c);
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{DEFAULT, "Joy"});
        for (String[] f : FontLibrary.TEXT_FONTS) out.add(f);
        out.addAll(FontLibrary.imported());
        return out;
    }
}
