package com.fadcam.ui.settingshub;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.picker.PickerBottomSheetFragment;

import java.util.ArrayList;

/**
 * The two classic rows that open a one-switch sheet rather than a screen: Show the intro and Check
 * for updates. The same sheet, keys, words and side effects as {@code SettingsHomeFragment}'s
 * {@code showOnboardingSwitchSheet} and {@code showAutoUpdateSwitchSheet}. The hub's row values
 * follow by themselves: it listens for preference changes. Both sheets show Joy Creator's
 * JoybotSwitch rather than FadCam's avatar (the hub is the first screen to carry it).
 */
final class SettingsSwitchSheets {

    /** The classic screen's key for this switch (it has no constant of its own). */
    static final String KEY_AUTO_UPDATE = "auto_update_check_enabled";

    private SettingsSwitchSheets() {}

    static void showIntro(@NonNull Fragment host) {
        final String resultKey = "picker_result_onboarding";
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(host.requireContext());
        host.getParentFragmentManager().setFragmentResultListener(resultKey, host, (k, b) -> {
            if (!b.containsKey(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)) return;
            boolean state = b.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
            // Turning it on also clears the first-install mark, so the intro really runs again.
            if (state) prefs.sharedPreferences.edit().putBoolean(Constants.FIRST_INSTALL_CHECKED_KEY, false).apply();
            prefs.setShowOnboarding(state);
        });
        String title = host.getString(R.string.setting_onboarding_title);
        PickerBottomSheetFragment.newInstanceWithSwitch(title, new ArrayList<>(), null, resultKey,
                host.getString(R.string.note_onboarding), title, prefs.isShowOnboarding())
                .withJoybotSwitch()
                .show(host.getParentFragmentManager(), "onboarding_switch_sheet");
    }

    static void updateCheck(@NonNull Fragment host) {
        final String resultKey = "picker_result_auto_update";
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(host.requireContext());
        host.getParentFragmentManager().setFragmentResultListener(resultKey, host, (k, b) -> {
            if (!b.containsKey(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)) return;
            prefs.sharedPreferences.edit()
                    .putBoolean(KEY_AUTO_UPDATE, b.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE))
                    .apply();
        });
        String title = host.getString(R.string.setting_auto_update_check_title);
        PickerBottomSheetFragment.newInstanceWithSwitch(title, new ArrayList<>(), null, resultKey,
                host.getString(R.string.note_auto_update_check), title,
                prefs.sharedPreferences.getBoolean(KEY_AUTO_UPDATE, true))
                .withJoybotSwitch()
                .show(host.getParentFragmentManager(), "auto_update_switch_sheet");
    }
}
