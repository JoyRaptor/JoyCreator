package cc.joycreator.textlab;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The fonts a look names, by the keys in {@code TL.FONTS} ({@code tools/textlab-web/tl-glyphs.js}),
 * and how a look's weight and width reach them. The browser asks a variable face for a weight in its
 * range and for a width by CSS keyword; this does the same with font variations, clamped to the
 * face's range the way the browser's font matching does.
 */
final class TlFonts {

    private static final String TAG = "TextLab";

    /** One face: where it is, and the weight and width ranges it can take (equal ends = fixed). */
    static final class Face {
        final String key;
        final int res;            // a res/font file, or 0
        @Nullable final String asset;
        final int wMin, wMax;
        final double sMin, sMax;  // width, percent; 0 = the face has no widths
        Typeface typeface;

        Face(String key, int res, @Nullable String asset, int wMin, int wMax, double sMin, double sMax) {
            this.key = key;
            this.res = res;
            this.asset = asset;
            this.wMin = wMin;
            this.wMax = wMax;
            this.sMin = sMin;
            this.sMax = sMax;
        }

        boolean oneWeight() {
            return wMin == wMax;
        }
    }

    private final Map<String, Face> faces = new HashMap<>();

    TlFonts(@NonNull Context c) {
        add(c, new Face("archivo", R.font.archivo_variable, null, 100, 900, 62, 125));
        add(c, new Face("plex", R.font.plex_sans_variable, null, 100, 700, 85, 100));
        add(c, new Face("mono", R.font.plex_mono_semibold, null, 600, 600, 0, 0));
        add(c, new Face("ubuntu", R.font.ubuntu_regular, null, 400, 400, 0, 0));
        add(c, new Face("doto", 0, "doto.ttf", 400, 400, 0, 0));
        add(c, new Face("pacifico", 0, "textlab/fonts/pacifico.ttf", 400, 400, 0, 0));
        add(c, new Face("bebas", 0, "textlab/fonts/bebas_neue.ttf", 400, 400, 0, 0));
        add(c, new Face("cinzel", 0, "textlab/fonts/cinzel_variable.ttf", 400, 900, 0, 0));
        add(c, new Face("racing", 0, "textlab/fonts/racing_sans_one.ttf", 400, 400, 0, 0));
        add(c, new Face("orbitron", 0, "textlab/fonts/orbitron_variable.ttf", 400, 900, 0, 0));
    }

    private void add(@NonNull Context c, @NonNull Face f) {
        try {
            f.typeface = f.res != 0 ? ResourcesCompat.getFont(c, f.res)
                    : Typeface.createFromAsset(c.getAssets(), f.asset);
        } catch (RuntimeException e) {
            Log.w(TAG, "font not loaded: " + f.key, e);
        }
        if (f.typeface != null) faces.put(f.key, f);
    }

    /** The face for a family key; an unknown key draws in Archivo, as the look book does. */
    @NonNull
    Face face(@Nullable String family) {
        Face f = family != null ? faces.get(family) : null;
        if (f == null) f = faces.get("archivo");
        if (f == null) throw new IllegalStateException("TextLab has no fonts");
        return f;
    }

    /** The CSS width keyword the look book asks for, as the percent the browser then applies. */
    static double stretchPercent(double w) {
        if (w <= 68) return 62.5;
        if (w <= 81) return 75;
        if (w <= 93) return 87.5;
        if (w < 106) return 100;
        if (w < 119) return 112.5;
        return 125;
    }

    /** A paint set up to draw the face at {@code px} with the look's weight and width. */
    @NonNull
    Paint paint(@NonNull Face f, double weight, double width, float px) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        p.setTypeface(f.typeface);
        p.setTextSize(px);
        p.setColor(0xffffffff);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            StringBuilder axes = new StringBuilder();
            if (!f.oneWeight()) {
                double wt = Math.max(f.wMin, Math.min(f.wMax, weight));
                axes.append(String.format(Locale.ROOT, "'wght' %.1f", wt));
            }
            if (f.sMax > 0) {
                double s = Math.max(f.sMin, Math.min(f.sMax, stretchPercent(width)));
                if (axes.length() > 0) axes.append(", ");
                axes.append(String.format(Locale.ROOT, "'wdth' %.2f", s));
            }
            if (axes.length() > 0) p.setFontVariationSettings(axes.toString());
        }
        return p;
    }
}
