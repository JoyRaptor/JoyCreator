package com.fadcam.ui.settingshub;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fadcam.FLog;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.ui.AppearanceSettingsFragment;
import com.fadcam.ui.AudioSettingsFragment;
import com.fadcam.ui.DebugLogBottomSheetFragment;
import com.fadcam.ui.DigitalForensicsSettingsFragment;
import com.fadcam.ui.MotionLabSettingsFragment;
import com.fadcam.ui.NotificationSettingsFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.PrefsBackupBottomSheetFragment;
import com.fadcam.ui.ReadmeBottomSheetFragment;
import com.fadcam.ui.SecuritySettingsFragment;
import com.fadcam.ui.ShortcutsSettingsFragment;
import com.fadcam.ui.StorageSettingsFragment;
import com.fadcam.ui.TrashFragment;
import com.fadcam.ui.VideoPlayerSettingsFragment;
import com.fadcam.ui.VideoSettingsFragment;
import com.fadcam.ui.WatermarkSettingsFragment;
import com.fadcam.ui.WhatsNewActivity;

/**
 * WHERE EACH ROW GOES. Every classic row opens exactly what the classic Settings tab opened (the
 * same fragment, sheet or activity, the same way, with the same tag), so behaviour is identical and
 * nothing here re-implements a setting. The few rows new to the hub open screens that already exist
 * elsewhere in the app.
 */
final class SettingsDestinations {

    private static final String TAG = "SettingsHub";

    /** {@code MainActivity} tab positions this hub can hand over to. */
    private static final int TAB_REMOTE = 2, TAB_CLASSIC_SETTINGS = 4;

    private SettingsDestinations() {}

    static void open(@NonNull Fragment host, @NonNull SettingsKey key) {
        FragmentActivity a = host.getActivity();
        if (a == null || !host.isAdded()) return;
        try {
            route(host, a, key);
        } catch (RuntimeException e) {
            FLog.w(TAG, "could not open " + key, e);
            Toast.makeText(a, R.string.settingshub_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static void route(@NonNull Fragment host, @NonNull FragmentActivity a, @NonNull SettingsKey key) {
        switch (key) {
            // ── folder and storage ──
            case JOY_FOLDER:         a.startActivity(com.fadcam.joyfolder.studio.JoyFolderSetupActivity.intent(a)); break;
            case RECORDING_LOCATION: over(a, new StorageSettingsFragment()); break;
            case SPACE:              a.startActivity(com.fadcam.joyfolder.studio.SpaceActivity.intent(a)); break;
            case RECENTLY_DELETED:   com.fadcam.ui.trash.RecentlyDeletedFragment.open(a); break;
            // ── studio ──
            case EDITOR:
                // Outside a project, the sheet shows only its app-wide rows.
                com.fadcam.ui.faditor.FaditorSettingsBottomSheet.newInstance()
                        .show(host.getParentFragmentManager(), "faditorSettings");
                break;
            case VIDEO_PLAYER:       over(a, new VideoPlayerSettingsFragment()); break;
            // ── recording ──
            case CAMERA_VIDEO:       over(a, new VideoSettingsFragment()); break;
            case AUDIO:              over(a, new AudioSettingsFragment()); break;
            case SCREEN_RECORDING:   over(a, new com.fadcam.fadrec.ui.ScreenRecordingSettingsFragment()); break;
            case WATERMARK:          over(a, new WatermarkSettingsFragment()); break;
            // ── appearance, notifications, security ──
            case APPEARANCE:         over(a, new AppearanceSettingsFragment()); break;
            case NOTIFICATIONS:      over(a, new NotificationSettingsFragment()); break;
            case SECURITY:           over(a, new SecuritySettingsFragment()); break;
            // ── shortcuts and tools ──
            case SHORTCUTS:          over(a, new ShortcutsSettingsFragment()); break;
            case TORCH:              OverlayNavUtil.show(a, com.fadcam.ui.miniapps.TorchToolFragment.newInstance(), "torch_tool"); break;
            case QR_SCANNER:         a.startActivity(new Intent(a, com.fadcam.ui.miniapps.QRScannerActivity.class)); break;
            // ── labs ──
            case MOTION_LAB:         over(a, new MotionLabSettingsFragment()); break;
            case SMART_DETECTION:    over(a, new DigitalForensicsSettingsFragment()); break;
            case REMOTE:             handOver(a, TAB_REMOTE); break;
            // ── about and help ──
            case README:             new ReadmeBottomSheetFragment().show(host.getChildFragmentManager(), "readme"); break;
            case WHATS_NEW:          a.startActivity(new Intent(a, WhatsNewActivity.class)); break;
            case SHOW_INTRO:         SettingsSwitchSheets.showIntro(host); break;
            case UPDATE_CHECK:       SettingsSwitchSheets.updateCheck(host); break;
            case DEBUG_LOG:          DebugLogBottomSheetFragment.newInstance().show(host.getParentFragmentManager(), "debug_log_tools"); break;
            case SETTINGS_BACKUP:    new PrefsBackupBottomSheetFragment().show(host.getParentFragmentManager(), "prefs_backup"); break;
            // The new About page; the classic one stays reachable from its foot.
            case ABOUT:              com.fadcam.ui.about.AboutJoyFragment.open(a); break;
            case RATE:               rate(a); break;
            case CLASSIC_SETTINGS:   handOver(a, TAB_CLASSIC_SETTINGS); break;
            default:                 throw new IllegalStateException("no destination for " + key);
        }
    }

    /** A settings screen over this one, tagged as the classic tab tags it; Back returns here. */
    private static void over(@NonNull FragmentActivity a, @NonNull Fragment screen) {
        OverlayNavUtil.show(a, screen, screen.getClass().getSimpleName());
    }

    /** Close the hub and show one of the main screen's tabs (Remote, or the classic Settings tab). */
    private static void handOver(@NonNull FragmentActivity a, int tab) {
        OverlayNavUtil.dismiss(a);
        if (a instanceof MainActivity) ((MainActivity) a).switchFragment(tab, true);
    }

    /** The Play Store page, or its web page where there is no store app. As the classic row does it. */
    private static void rate(@NonNull FragmentActivity a) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + a.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            a.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + a.getPackageName())));
        }
    }

    /** What's new is a web view, which a watch does not have (the sidebar skips it there too). */
    static boolean isWatch(@NonNull Context c) {
        try {
            return com.fadcam.utils.RuntimeCompat.isWatchDevice(c);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
