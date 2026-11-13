package com.fadcam.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.FLog;
import com.fadcam.R;
import com.google.android.material.button.MaterialButton;

/**
 * The intro's last page, "Before we start": three plain promises, the Joybot key offer, and the
 * way in.
 *
 * <p>SHELL-35 (11-13). Until now this page was FadCam's "verify you are human" gate: three boxes
 * the user had to tick (one of them a joke marked "Not optional") before "Let me in" would light,
 * and the third linked FadCam's own privacy policy on GitHub. A forced-consent gate fails Play
 * review and the link was another project's promise, not ours. The boxes are gone: the three
 * lines now say what is true about the app, and the button is always ready. Joy Creator's own
 * privacy policy is still a draft awaiting the owner (PRIVACY.md), so no link is shown until it
 * is published.</p>
 *
 * <p>The Joybot key card (owner, 09-17: "have one already? You can add its api now") had two
 * buttons wired to nothing. "Add it now" opens Joybot's connect-an-AI settings; "Later, from
 * chat" folds the card away, since its words already say where to find it again.</p>
 */
public class OnboardingHumanFragment extends Fragment {
    private MaterialButton continueButton;
    private TextView label1, label2, label3;
    private TextView titleText, descText;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.onboarding_human_slide, container, false);

        label1 = v.findViewById(R.id.checkbox_label_1);
        label2 = v.findViewById(R.id.checkbox_label_2);
        label3 = v.findViewById(R.id.checkbox_label_3);
        titleText = v.findViewById(R.id.tvHumanTitle);
        descText = v.findViewById(R.id.tvHumanDesc);
        continueButton = v.findViewById(R.id.btn_human_continue);

        // Statements, not boxes: no tick glyph, and the rows take no taps.
        promise(v, R.id.checkbox_row_1, R.id.checkbox_icon_1);
        promise(v, R.id.checkbox_row_2, R.id.checkbox_icon_2);
        promise(v, R.id.checkbox_row_3, R.id.checkbox_icon_3);
        refreshLanguage();

        View card = v.findViewById(R.id.intro_ai_card);
        View add = v.findViewById(R.id.intro_ai_add);
        View later = v.findViewById(R.id.intro_ai_later);
        if (add != null) {
            androidx.appcompat.widget.TooltipCompat.setTooltipText(add, getString(R.string.hover_intro_ai_add));
            add.setOnClickListener(b -> openAiSettings());
        }
        if (later != null && card != null) {
            androidx.appcompat.widget.TooltipCompat.setTooltipText(later, getString(R.string.hover_intro_ai_later));
            later.setOnClickListener(b -> card.setVisibility(View.GONE));
        }

        continueButton.setOnClickListener(view -> {
            if (getActivity() instanceof OnboardingActivity) {
                ((OnboardingActivity) getActivity()).finishOnboarding();
            } else {
                requireActivity().finish();
            }
        });
        return v;
    }

    private static void promise(View root, int rowId, int iconId) {
        LinearLayout row = root.findViewById(rowId);
        ImageView icon = root.findViewById(iconId);
        if (icon != null) icon.setVisibility(View.GONE);
        if (row != null) {
            row.setClickable(false);
            row.setFocusable(false);
            row.setBackground(null);
            // The tick box gave each line its indent; without it the words met the screen edge (Note 9, 11-13).
            int pad = Math.round(20 * row.getResources().getDisplayMetrics().density);
            row.setPaddingRelative(pad, row.getPaddingTop(), pad, row.getPaddingBottom());
        }
    }

    /** Joybot's settings, where an AI is connected (the same place as Settings, AI and Joybot). */
    private void openAiSettings() {
        try {
            startActivity(new Intent(requireContext(), com.fadcam.ui.faditor.ai.ChatAssistantActivity.class)
                    .putExtra(com.fadcam.ui.faditor.ai.ChatAssistantActivity.EXTRA_ROOM, "LOBBY")
                    .putExtra(com.fadcam.ui.faditor.ai.ChatAssistantActivity.EXTRA_OPEN_SETTINGS, true));
        } catch (Exception e) {
            FLog.w("Onboarding", "Joybot settings did not open", e);
        }
    }

    /**
     * Called to refresh language-specific UI elements when the language has changed
     * without recreating the entire fragment.
     */
    public void refreshLanguage() {
        if (titleText != null) titleText.setText(R.string.onboarding_human_title);
        if (descText != null) descText.setText(R.string.onboarding_promise_desc);
        if (label1 != null) label1.setText(R.string.onboarding_promise_1);
        if (label2 != null) label2.setText(R.string.onboarding_promise_2);
        if (label3 != null) label3.setText(R.string.onboarding_promise_3);
        if (continueButton != null) {
            continueButton.setText(R.string.onboarding_human_button);
            // Force LTR for the button (the page's language switcher can flip it otherwise).
            continueButton.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            continueButton.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            continueButton.setEnabled(true);
        }
        View view = getView();
        if (view != null) {
            view.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            view.requestLayout();
        }
    }
}
