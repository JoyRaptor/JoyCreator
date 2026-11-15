package com.fadcam.ui.about;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fadcam.R;
import com.fadcam.ui.AboutFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * THE SIGN-OFF, like the last card of a film's credits: "Made by" in faint capitals, the maker's
 * name in the display face at its heaviest, centred. Under it, small and quiet, the maker's support
 * link (only once it is filled in) and the way back to the classic About screen, which stays whole
 * until this page is better in every way.
 */
final class AboutSignOff {

    private AboutSignOff() {}

    @NonNull
    static LinearLayout build(@NonNull Fragment host) {
        android.content.Context c = host.requireContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView madeBy = AboutKit.label(c, R.string.aboutjoy_made_by);
        col.addView(madeBy, wrap());

        TextView maker = AboutKit.text(c, 24f, Studio.INK, AboutKit.DISPLAY, Type.BLACK);
        maker.setText(R.string.aboutjoy_maker);
        maker.setLetterSpacing(-0.03f);
        LinearLayout.LayoutParams mLp = wrap();
        mLp.topMargin = AboutKit.dp(c, 8);
        col.addView(maker, mLp);

        // The one support link in the app (L32 / SHELL-60): shown only once the owner has put
        // their page in support_url, never as a prompt, and it unlocks nothing.
        String supportUrl = c.getString(R.string.support_url).trim();
        boolean support = !supportUrl.isEmpty();
        if (support) {
            TextView give = quietPill(c, R.string.aboutjoy_support, R.string.aboutjoy_support_hint);
            give.setOnClickListener(v -> AboutKit.openUrl(v.getContext(), supportUrl));
            LinearLayout.LayoutParams gLp = wrap();
            gLp.topMargin = AboutKit.dp(c, 22);
            col.addView(give, gLp);
        }

        TextView classic = quietPill(c, R.string.aboutjoy_classic, R.string.aboutjoy_classic_hint);
        classic.setOnClickListener(v -> {
            FragmentActivity a = host.getActivity();
            if (a != null) OverlayNavUtil.show(a, new AboutFragment(), "AboutFragment");
        });
        LinearLayout.LayoutParams cLp = wrap();
        cLp.topMargin = AboutKit.dp(c, support ? 8 : 22);
        col.addView(classic, cLp);
        return col;
    }

    /** Small, faint, pill-shaped: a way out that never competes with the page. */
    @NonNull
    private static TextView quietPill(@NonNull android.content.Context c, int text, int hint) {
        TextView pill = AboutKit.text(c, 11f, Studio.INK_FAINT, AboutKit.BODY, Type.MEDIUM);
        pill.setText(text);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(AboutKit.dp(c, 40));
        pill.setPadding(AboutKit.dp(c, 14), 0, AboutKit.dp(c, 14), 0);
        pill.setBackground(SheetKit.pillBackground(c, 0));
        pill.setClickable(true);
        pill.setFocusable(true);
        SheetKit.press(pill);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(pill, c.getString(hint));
        return pill;
    }

    @NonNull
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
