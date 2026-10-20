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
 * name in the display face at its heaviest, centred. Under it, small and quiet, the way back to
 * the classic About screen, which stays whole until this page is better in every way.
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

        TextView classic = AboutKit.text(c, 11f, Studio.INK_FAINT, AboutKit.BODY, Type.MEDIUM);
        classic.setText(R.string.aboutjoy_classic);
        classic.setGravity(Gravity.CENTER);
        classic.setMinHeight(AboutKit.dp(c, 40));
        classic.setPadding(AboutKit.dp(c, 14), 0, AboutKit.dp(c, 14), 0);
        classic.setBackground(SheetKit.pillBackground(c, 0));
        classic.setClickable(true);
        classic.setFocusable(true);
        SheetKit.press(classic);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(classic, c.getString(R.string.aboutjoy_classic_hint));
        classic.setOnClickListener(v -> {
            FragmentActivity a = host.getActivity();
            if (a != null) OverlayNavUtil.show(a, new AboutFragment(), "AboutFragment");
        });
        LinearLayout.LayoutParams cLp = wrap();
        cLp.topMargin = AboutKit.dp(c, 22);
        col.addView(classic, cLp);
        return col;
    }

    @NonNull
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
