package com.fadcam.ui.about;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.services.UpdateCheckService;
import com.fadcam.ui.UpdateAvailableBottomSheet;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * THE VERSION: the number big in mono, a release tag beside it when there is one (beta9, Pro),
 * the build number and package under it, and the page's one primary control, Check for updates,
 * as a slanted Studio-green chip.
 *
 * <p>The answer is said in place, under the button, rather than in a dialog: a green tick when
 * this is the newest, the cyan "ready" line and the same Update sheet the classic screen shows
 * when there is a newer one, a quiet "Get the beta" when only a beta is newer, an amber note when
 * the feed could not be read.</p>
 */
final class AboutVersion {

    /** What the phone says this install is. */
    static final class Info {
        @NonNull final String name;
        final long code;
        @NonNull final String pkg;

        Info(@NonNull String name, long code, @NonNull String pkg) {
            this.name = name;
            this.code = code;
            this.pkg = pkg;
        }

        static Info read(@NonNull Context c) {
            String pkg = c.getPackageName();
            try {
                PackageInfo p = c.getPackageManager().getPackageInfo(pkg, 0);
                return new Info(p.versionName == null ? "" : p.versionName,
                        PackageInfoCompat.getLongVersionCode(p), pkg);
            } catch (PackageManager.NameNotFoundException e) {
                return new Info("", 0, pkg);
            }
        }
    }

    @NonNull final LinearLayout view;
    @NonNull private final Fragment host;
    @NonNull private final Info info;
    @NonNull private final AboutUpdateCheck check = new AboutUpdateCheck();
    @NonNull private final LinearLayout button;
    @NonNull private final TextView buttonGlyph, buttonText, statusGlyph, statusText, beta;
    @NonNull private final LinearLayout status;

    AboutVersion(@NonNull Fragment host, @NonNull Info info) {
        this.host = host;
        this.info = info;
        Context c = host.requireContext();
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.addView(AboutKit.label(c, R.string.aboutjoy_version_label));

        // ── the number ──
        int dash = info.name.indexOf('-');
        String number = dash > 0 ? info.name.substring(0, dash) : info.name;
        String tag = dash > 0 ? info.name.substring(dash + 1) : "";
        LinearLayout numberRow = new LinearLayout(c);
        numberRow.setOrientation(LinearLayout.HORIZONTAL);
        numberRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView big = AboutKit.text(c, 44f, Studio.INK, AboutKit.MONO, Type.MEDIUM);
        big.setText(number);
        big.setLetterSpacing(-0.04f);
        numberRow.addView(big);
        if (!tag.isEmpty()) {
            TextView t = AboutKit.text(c, 9f, Studio.INK_DIM, AboutKit.MONO, Type.MEDIUM);
            t.setText(tag);
            t.setAllCaps(true);
            t.setLetterSpacing(0.1f);
            t.setPadding(AboutKit.dp(c, 7), AboutKit.dp(c, 3), AboutKit.dp(c, 7), AboutKit.dp(c, 3));
            t.setBackground(SheetKit.pillBackground(c, Studio.RAISED));
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.setMarginStart(AboutKit.dp(c, 10));
            numberRow.addView(t, tLp);
        }
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = AboutKit.dp(c, 8);
        view.addView(numberRow, nLp);

        TextView meta = AboutKit.text(c, 10f, Studio.INK_FAINT, AboutKit.MONO, Type.REGULAR);
        meta.setText(c.getString(R.string.aboutjoy_build, String.valueOf(info.code), info.pkg));
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = AboutKit.dp(c, 8);
        view.addView(meta, mLp);

        // ── the one primary control ──
        button = AboutKit.slantChip(c, "system_update", c.getString(R.string.aboutjoy_check),
                Studio.GO, Studio.GO_END, 40f, 12.5f);
        buttonGlyph = (TextView) button.getChildAt(0);
        buttonText = (TextView) button.getChildAt(1);
        // Wide enough for its longest words, so it does not shrink to "Checking…" and jump back.
        button.setMinimumWidth(AboutKit.dp(c, 184));
        button.setClickable(true);
        button.setFocusable(true);
        // Hover label only: a screen reader reads the button's own words, which change while it checks.
        TooltipCompat.setTooltipText(button, c.getString(R.string.aboutjoy_check_hint));
        SheetKit.press(button);
        button.setOnClickListener(b -> check());
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, AboutKit.dp(c, 40));
        bLp.topMargin = AboutKit.dp(c, 18);
        view.addView(button, bLp);

        // ── the answer, said in place ──
        status = new LinearLayout(c);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setVisibility(View.GONE);
        // A screen reader says the answer when it lands, without the page having to announce it.
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusGlyph =AboutKit.glyph(c, "check_circle", 15f, Studio.GO);
        status.addView(statusGlyph);
        statusText = AboutKit.text(c, 12.5f, Studio.INK_DIM, AboutKit.BODY, Type.REGULAR);
        statusText.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sLp.setMarginStart(AboutKit.dp(c, 8));
        status.addView(statusText, sLp);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = AboutKit.dp(c, 12);
        view.addView(status, stLp);

        beta = SheetKit.pillButton(c, c.getString(R.string.aboutjoy_get_beta), false, b -> { });
        beta.setVisibility(View.GONE);
        LinearLayout.LayoutParams betaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        betaLp.topMargin = AboutKit.dp(c, 10);
        view.addView(beta, betaLp);
    }

    /** The page is going: drop any answer still on its way. */
    void release() {
        check.cancel();
    }

    private void check() {
        if (check.isRunning()) return;
        busy(true);
        status.setVisibility(View.GONE);
        beta.setVisibility(View.GONE);
        check.run(info.name, new AboutUpdateCheck.Listener() {
            @Override public void onAnswer(@NonNull UpdateCheckService.UpdateCheckResult r) { answer(r); }
            @Override public void onFailed() { failed(); }
        });
    }

    private void busy(boolean on) {
        button.setEnabled(!on);
        button.setAlpha(on ? 0.6f : 1f);
        buttonGlyph.setText(on ? "sync" : "system_update");
        buttonText.setText(on ? R.string.aboutjoy_checking : R.string.aboutjoy_check);
    }

    private void answer(@NonNull UpdateCheckService.UpdateCheckResult r) {
        if (!host.isAdded()) return;
        busy(false);
        Context c = view.getContext();
        if (r.hasStable) {
            say("new_releases", Studio.ARMED, c.getString(R.string.aboutjoy_update_ready, r.stableVersion));
            // The very sheet the classic About screen shows, with the same arguments.
            if (!host.getParentFragmentManager().isStateSaved()) {
                UpdateAvailableBottomSheet.newInstance(r.stableVersion, "", r.stableUrl)
                        .show(host.getParentFragmentManager(), "UpdateAvailableBottomSheet");
            }
        } else if (r.hasBeta) {
            say("check_circle", Studio.GO, c.getString(R.string.aboutjoy_beta_ready, r.betaVersion));
            if (r.betaUrl != null && !r.betaUrl.isEmpty()) {
                final String url = r.betaUrl;
                beta.setOnClickListener(b -> AboutKit.openUrl(c, url));
                beta.setVisibility(View.VISIBLE);
            }
        } else {
            say("check_circle", Studio.GO, c.getString(R.string.aboutjoy_up_to_date));
        }
    }

    private void failed() {
        if (!host.isAdded()) return;
        busy(false);
        say("error_outline", Studio.CAREFUL, view.getContext().getString(R.string.aboutjoy_update_failed));
    }

    private void say(@NonNull String ligature, int colour, @NonNull String words) {
        statusGlyph.setText(ligature);
        statusGlyph.setTextColor(colour);
        statusText.setText(words);
        status.setVisibility(View.VISIBLE);
    }
}
