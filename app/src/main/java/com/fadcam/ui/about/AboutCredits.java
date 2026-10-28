package com.fadcam.ui.about;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.TooltipCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * OPEN SOURCE: the honest, quiet credit. Joy Creator is built on FadCam, which is licensed under
 * the GPL-3.0, and that licence asks that the notice and the way to the source stay reachable. So
 * this card carries, unchanged, what the classic About screen carried: the open-source note
 * ({@code fadsec_info}), the copyright and licence line ({@code copyright_info}) and its View
 * Source Code link, to the very same address. It is credit, not a hero: one card, no logo.
 */
final class AboutCredits {

    /** View Source Code: this project's own source (S19 repointed it from FadCam's organisation). */
    static final String SOURCE_URL = "https://github.com/JoyRaptor/JoyCreator";

    private AboutCredits() {}

    @NonNull
    static LinearLayout build(@NonNull Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(AboutKit.label(c, R.string.aboutjoy_open_label));

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(AboutKit.card(c));
        card.setClipToPadding(false);   // the source row reaches into the padding
        card.setPadding(AboutKit.dp(c, 14), AboutKit.dp(c, 14), AboutKit.dp(c, 14), AboutKit.dp(c, 8));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = AboutKit.dp(c, 10);
        col.addView(card, cLp);

        TextView basedOn = AboutKit.text(c, 14f, Studio.INK, AboutKit.BODY, Type.SEMIBOLD);
        basedOn.setText(R.string.aboutjoy_based_on);
        card.addView(basedOn);

        TextView note = AboutKit.text(c, 12.5f, Studio.INK_DIM, AboutKit.BODY, Type.REGULAR);
        note.setText(R.string.fadsec_info);
        note.setLineSpacing(0, 1.25f);
        card.addView(note, gap(c, 6));

        TextView licence = AboutKit.text(c, 10f, Studio.INK_FAINT, AboutKit.MONO, Type.REGULAR);
        licence.setText(c.getString(R.string.copyright_info).trim());
        licence.setLineSpacing(0, 1.3f);
        card.addView(licence, gap(c, 10));

        // Pulled out by 8dp each side, with 8dp of its own padding back: the glyph lines up with the
        // words above it, and the press reaches nearer the card's edges.
        LinearLayout.LayoutParams rowLp = gap(c, 8);
        rowLp.setMarginStart(-AboutKit.dp(c, 8));
        rowLp.setMarginEnd(-AboutKit.dp(c, 8));
        card.addView(sourceRow(c), rowLp);
        return col;
    }

    /** View Source Code: a flat row, the code glyph, the classic words, an open-outside mark. */
    @NonNull
    private static LinearLayout sourceRow(@NonNull Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(AboutKit.dp(c, 44));
        row.setPadding(AboutKit.dp(c, 8), 0, AboutKit.dp(c, 8), 0);
        row.setBackground(AboutKit.pressable(c, 8));
        row.setClickable(true);
        row.setFocusable(true);
        SheetKit.press(row);
        TooltipCompat.setTooltipText(row, c.getString(R.string.aboutjoy_source_hint));
        row.addView(AboutKit.glyph(c, "code", 18f, Studio.INK_DIM));
        TextView words = AboutKit.text(c, 13.5f, Studio.INK, AboutKit.BODY, Type.MEDIUM);
        words.setText(R.string.view_source_code);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wLp.setMarginStart(AboutKit.dp(c, 12));
        row.addView(words, wLp);
        row.addView(AboutKit.glyph(c, "open_in_new", 16f, Studio.INK_FAINT));
        row.setOnClickListener(v -> AboutKit.openUrl(c, SOURCE_URL));
        return row;
    }

    @NonNull
    private static LinearLayout.LayoutParams gap(@NonNull Context c, float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AboutKit.dp(c, topDp);
        return lp;
    }
}
