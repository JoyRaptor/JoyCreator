package com.fadcam.ui.faditor.timeline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;

import java.util.Locale;

/**
 * The small black mark where playback and export jump over a struck word or a cut silence
 * (job STU-B3, owner ruling 2026-11-15). One look for the film track and the sound rows: a
 * 2 dp black stem down the clip with a small black tab at the top, edged with a faint zinc
 * hairline so it still reads on the dark cut region and on a black lane.
 */
public final class CutMarkPainter {

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint EDGE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF TAB = new RectF();

    static {
        FILL.setStyle(Paint.Style.FILL);
        FILL.setColor(Studio.GROUND);
        EDGE.setStyle(Paint.Style.STROKE);
        EDGE.setColor(Studio.alpha(Studio.INK, 0x55));
    }

    private CutMarkPainter() { }

    /** Draw the mark at content x {@code x}, from {@code top} to {@code bottom}. */
    public static void draw(@NonNull Canvas canvas, float x, float top, float bottom, float density) {
        float stem = 2f * density;
        float tabW = 7f * density;
        float tabH = 5f * density;
        EDGE.setStrokeWidth(Math.max(1f, 0.75f * density));
        // The stem.
        canvas.drawRect(x - stem / 2f, top, x + stem / 2f, bottom, FILL);
        canvas.drawLine(x + stem / 2f, top + tabH, x + stem / 2f, bottom, EDGE);
        // The tab.
        TAB.set(x - tabW / 2f, top, x + tabW / 2f, top + tabH);
        canvas.drawRoundRect(TAB, 1.5f * density, 1.5f * density, FILL);
        canvas.drawRoundRect(TAB, 1.5f * density, 1.5f * density, EDGE);
    }

    /** "Cut: 0.8 s skipped, tap the word to restore". */
    @NonNull
    public static String label(@NonNull Context context, long skippedMs) {
        String secs = String.format(Locale.getDefault(), "%.1f", Math.max(0L, skippedMs) / 1000.0);
        return context.getString(R.string.hover_studio_cut_mark, secs);
    }
}
