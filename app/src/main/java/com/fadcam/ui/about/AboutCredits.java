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
 * Below it, Google's MediaPipe models (face and body tracking, SHRUG-1): their licence asks that
 * a copy of it goes with them, so a row shows {@code assets/licenses/mediapipe-models.txt}.
 */
final class AboutCredits {

    /** View Source Code: this project's own source (S19 repointed it from FadCam's organisation). */
    static final String SOURCE_URL = "https://github.com/JoyRaptor/JoyCreator";
    /** Google's MediaPipe models: where they come from, their hashes and the Apache License 2.0. */
    static final String MEDIAPIPE_NOTICE = "licenses/mediapipe-models.txt";

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
        card.addView(linkRow(c, "code", R.string.view_source_code, R.string.aboutjoy_source_hint, "open_in_new",
                () -> AboutKit.openUrl(c, SOURCE_URL)), rowLp);

        TextView models = AboutKit.text(c, 12.5f, Studio.INK_DIM, AboutKit.BODY, Type.REGULAR);
        models.setText(R.string.aboutjoy_mediapipe);
        models.setLineSpacing(0, 1.25f);
        card.addView(models, gap(c, 8));
        LinearLayout.LayoutParams licLp = gap(c, 4);
        licLp.setMarginStart(-AboutKit.dp(c, 8));
        licLp.setMarginEnd(-AboutKit.dp(c, 8));
        card.addView(linkRow(c, "description", R.string.aboutjoy_mediapipe_read, R.string.aboutjoy_mediapipe_hint, null,
                () -> showNotice(c)), licLp);
        return col;
    }

    /** A flat row: a glyph, the words, an optional trailing mark (View Source Code: open-outside). */
    @NonNull
    private static LinearLayout linkRow(@NonNull Context c, @NonNull String glyph, int words, int hint,
                                        @androidx.annotation.Nullable String trailing, @NonNull Runnable tap) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(AboutKit.dp(c, 44));
        row.setPadding(AboutKit.dp(c, 8), 0, AboutKit.dp(c, 8), 0);
        row.setBackground(AboutKit.pressable(c, 8));
        row.setClickable(true);
        row.setFocusable(true);
        SheetKit.press(row);
        TooltipCompat.setTooltipText(row, c.getString(hint));
        row.setContentDescription(c.getString(words) + ": " + c.getString(hint));
        row.addView(AboutKit.glyph(c, glyph, 18f, Studio.INK_DIM));
        TextView label = AboutKit.text(c, 13.5f, Studio.INK, AboutKit.BODY, Type.MEDIUM);
        label.setText(words);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wLp.setMarginStart(AboutKit.dp(c, 12));
        row.addView(label, wLp);
        if (trailing != null) row.addView(AboutKit.glyph(c, trailing, 16f, Studio.INK_FAINT));
        row.setOnClickListener(v -> tap.run());
        return row;
    }

    /** The MediaPipe notice in a plain scrolling box (read from the app itself: no internet needed). */
    private static void showNotice(@NonNull Context c) {
        StringBuilder text = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                c.getAssets().open(MEDIAPIPE_NOTICE), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) text.append(line).append((char) 10);
        } catch (java.io.IOException e) {
            text.append(c.getString(R.string.aboutjoy_mediapipe));
        }
        TextView body = AboutKit.text(c, 10f, Studio.INK_DIM, AboutKit.MONO, Type.REGULAR);
        body.setText(text);
        body.setTextIsSelectable(true);
        int pad = AboutKit.dp(c, 16);
        body.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(body);
        new android.app.AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.aboutjoy_mediapipe_read)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @NonNull
    private static LinearLayout.LayoutParams gap(@NonNull Context c, float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AboutKit.dp(c, topDp);
        return lp;
    }
}
