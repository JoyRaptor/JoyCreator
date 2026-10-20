package com.fadcam.ui.about;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.TooltipCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.motion.Motion;
import com.fadcam.ui.type.Type;

/**
 * PRIVACY: one card, closed by default, that opens to the classic screen's own questions and
 * answers ({@code questions_array} / {@code answers_array}), so the two screens never disagree.
 * Questions in ink, answers in dim ink, the array's own group headings ("General") as small
 * faint capitals. Opening it slides the page open and brings the card into view.
 */
final class AboutPrivacy {

    private AboutPrivacy() {}

    /** @param page what slides open around the card (the page's column) */
    @NonNull
    static LinearLayout build(@NonNull Context c, @NonNull ViewGroup page) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(AboutKit.label(c, R.string.aboutjoy_privacy_label));

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(AboutKit.card(c));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = AboutKit.dp(c, 10);
        col.addView(card, cLp);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(AboutKit.dp(c, 50));
        header.setPadding(AboutKit.dp(c, 14), 0, AboutKit.dp(c, 12), 0);
        header.setBackground(AboutKit.pressable(c, 14));
        header.setClickable(true);
        header.setFocusable(true);
        // Hover label says what a tap does; a screen reader reads the title itself.
        TooltipCompat.setTooltipText(header, c.getString(R.string.aboutjoy_privacy_toggle));
        SheetKit.press(header);
        header.addView(AboutKit.glyph(c, "privacy_tip", 18f, Studio.INK_DIM));
        TextView title = AboutKit.text(c, 14f, Studio.INK, AboutKit.BODY, Type.SEMIBOLD);
        title.setText(R.string.privacy_information);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.setMarginStart(AboutKit.dp(c, 12));
        header.addView(title, tLp);
        TextView chevron = AboutKit.glyph(c, "expand_more", 20f, Studio.INK_FAINT);
        header.addView(chevron);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView answers = AboutKit.text(c, 12.5f, Studio.INK_DIM, AboutKit.BODY, Type.REGULAR);
        answers.setText(questionsAndAnswers(c));
        answers.setLineSpacing(0, 1.3f);
        answers.setPadding(AboutKit.dp(c, 14), AboutKit.dp(c, 4), AboutKit.dp(c, 14), AboutKit.dp(c, 16));
        answers.setVisibility(View.GONE);
        card.addView(answers, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header.setOnClickListener(h -> {
            boolean open = answers.getVisibility() != View.VISIBLE;
            boolean calm = Motion.reduced(c);
            if (!calm) {
                AutoTransition slide = new AutoTransition();
                slide.setDuration(Motion.MENU);
                slide.setInterpolator(Motion.EASE_OUT);
                TransitionManager.beginDelayedTransition(page, slide);
            }
            answers.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(open ? 180f : 0f).setDuration(calm ? 0 : Motion.MENU).start();
            // Once it has opened, show as much of it as fits, from its top.
            if (open) card.postDelayed(() -> card.requestRectangleOnScreen(
                    new Rect(0, 0, card.getWidth(), card.getHeight()), false), calm ? 0 : Motion.MENU);
        });
        return col;
    }

    /** The classic screen's questions and answers, set as one piece of styled text. */
    @NonNull
    private static CharSequence questionsAndAnswers(@NonNull Context c) {
        Resources r = c.getResources();
        String[] q = r.getStringArray(R.array.questions_array);
        String[] a = r.getStringArray(R.array.answers_array);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < Math.min(q.length, a.length); i++) {
            String question = q[i].trim(), answer = a[i].trim();
            if (question.isEmpty() && answer.isEmpty()) continue;   // the arrays' spacer rows
            if (sb.length() > 0) sb.append("\n\n");
            if (answer.isEmpty()) {                                  // a group heading, e.g. "General"
                int s = sb.length();
                sb.append(question.toUpperCase(java.util.Locale.getDefault()));
                span(sb, s, new ForegroundColorSpan(Studio.INK_FAINT), new RelativeSizeSpan(0.8f),
                        new StyleSpan(Typeface.BOLD));
                continue;
            }
            int s = sb.length();
            sb.append(question);
            span(sb, s, new ForegroundColorSpan(Studio.INK), new StyleSpan(Typeface.BOLD));
            sb.append('\n').append(answer);
        }
        return sb;
    }

    private static void span(@NonNull SpannableStringBuilder sb, int start, @NonNull Object... spans) {
        for (Object s : spans) sb.setSpan(s, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
