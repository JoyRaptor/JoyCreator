package com.fadcam.ui.settingshub;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.fadcam.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * WHAT THE HUB HOLDS: its sections, in order, and each row's glyph and words. Data only; what a
 * row opens is {@link SettingsDestinations}, what it says live is {@link SettingsStatus}.
 *
 * <p>The order is the order of need. Your folder first (where everything you make lives), then the
 * two rooms that make things, then how the app looks, alerts and locks, then the gadgets, the
 * experiments, and last the help and the way back to the classic screen.</p>
 */
final class SettingsCatalog {

    /** How a row ends: a chevron into the app, or a mark that it leaves the app. */
    enum Trail { OPEN, LEAVE }

    static final class Row {
        final SettingsKey key;
        final String glyph;
        @StringRes final int title, summary, names;
        final Trail trail;

        Row(SettingsKey key, String glyph, int title, int summary, int names, Trail trail) {
            this.key = key;
            this.glyph = glyph;
            this.title = title;
            this.summary = summary;
            this.names = names;
            this.trail = trail;
        }
    }

    static final class Section {
        @StringRes final int title, chip;
        final String glyph;
        final List<Row> rows;

        Section(int title, int chip, String glyph, Row... rows) {
            this.title = title;
            this.chip = chip;
            this.glyph = glyph;
            this.rows = Collections.unmodifiableList(Arrays.asList(rows));
        }
    }

    private SettingsCatalog() {}

    static final List<Section> SECTIONS = Collections.unmodifiableList(Arrays.asList(
            new Section(R.string.settingshub_section_folder, R.string.settingshub_chip_folder, "folder",
                    row(SettingsKey.JOY_FOLDER, "folder", R.string.settingshub_joy_folder,
                            R.string.settingshub_joy_folder_desc, R.string.settingshub_joy_folder_names),
                    row(SettingsKey.RECORDING_LOCATION, "save_alt", R.string.settingshub_recording_location,
                            R.string.settingshub_recording_location_desc, R.string.settingshub_recording_location_names),
                    row(SettingsKey.SPACE, "pie_chart", R.string.settingshub_space,
                            R.string.settingshub_space_desc, R.string.settingshub_space_names),
                    row(SettingsKey.RECENTLY_DELETED, "restore_from_trash", R.string.settingshub_recently_deleted,
                            R.string.settingshub_recently_deleted_desc, R.string.settingshub_recently_deleted_names)),
            new Section(R.string.settingshub_section_studio, R.string.settingshub_chip_studio, "movie_edit",
                    row(SettingsKey.EDITOR, "tune", R.string.settingshub_editor,
                            R.string.settingshub_editor_desc, R.string.settingshub_editor_names),
                    row(SettingsKey.VIDEO_PLAYER, "smart_display", R.string.settingshub_video_player,
                            R.string.settingshub_video_player_desc, R.string.settingshub_video_player_names)),
            new Section(R.string.settingshub_section_recording, R.string.settingshub_chip_recording, "videocam",
                    row(SettingsKey.CAMERA_VIDEO, "videocam", R.string.settingshub_camera_video,
                            R.string.settingshub_camera_video_desc, R.string.settingshub_camera_video_names),
                    row(SettingsKey.AUDIO, "mic", R.string.settingshub_audio,
                            R.string.settingshub_audio_desc, R.string.settingshub_audio_names),
                    row(SettingsKey.SCREEN_RECORDING, "screen_share", R.string.settingshub_screen_recording,
                            R.string.settingshub_screen_recording_desc, R.string.settingshub_screen_recording_names),
                    row(SettingsKey.WATERMARK, "branding_watermark", R.string.settingshub_watermark,
                            R.string.settingshub_watermark_desc, R.string.settingshub_watermark_names)),
            new Section(R.string.settingshub_section_appearance, R.string.settingshub_chip_appearance, "palette",
                    row(SettingsKey.APPEARANCE, "palette", R.string.settingshub_appearance,
                            R.string.settingshub_appearance_desc, R.string.settingshub_appearance_names)),
            new Section(R.string.settingshub_section_notifications, R.string.settingshub_chip_notifications, "notifications",
                    row(SettingsKey.NOTIFICATIONS, "notifications", R.string.settingshub_notifications,
                            R.string.settingshub_notifications_desc, R.string.settingshub_notifications_names)),
            new Section(R.string.settingshub_section_security, R.string.settingshub_chip_security, "lock",
                    row(SettingsKey.SECURITY, "lock", R.string.settingshub_security,
                            R.string.settingshub_security_desc, R.string.settingshub_security_names)),
            new Section(R.string.settingshub_section_shortcuts, R.string.settingshub_chip_shortcuts, "widgets",
                    row(SettingsKey.SHORTCUTS, "shortcut", R.string.settingshub_shortcuts,
                            R.string.settingshub_shortcuts_desc, R.string.settingshub_shortcuts_names),
                    row(SettingsKey.TORCH, "flashlight_on", R.string.settingshub_torch,
                            R.string.settingshub_torch_desc, R.string.settingshub_torch_names),
                    row(SettingsKey.QR_SCANNER, "qr_code_scanner", R.string.settingshub_qr_scanner,
                            R.string.settingshub_qr_scanner_desc, R.string.settingshub_qr_scanner_names)),
            new Section(R.string.settingshub_section_labs, R.string.settingshub_chip_labs, "science",
                    row(SettingsKey.MOTION_LAB, "motion_photos_on", R.string.settingshub_motion_lab,
                            R.string.settingshub_motion_lab_desc, R.string.settingshub_motion_lab_names),
                    row(SettingsKey.SMART_DETECTION, "psychology", R.string.settingshub_smart_detection,
                            R.string.settingshub_smart_detection_desc, R.string.settingshub_smart_detection_names),
                    row(SettingsKey.REMOTE, "cast", R.string.settingshub_remote,
                            R.string.settingshub_remote_desc, R.string.settingshub_remote_names)),
            new Section(R.string.settingshub_section_about, R.string.settingshub_chip_about, "info",
                    row(SettingsKey.README, "menu_book", R.string.settingshub_readme,
                            R.string.settingshub_readme_desc, R.string.settingshub_readme_names),
                    row(SettingsKey.WHATS_NEW, "new_releases", R.string.settingshub_whats_new,
                            R.string.settingshub_whats_new_desc, R.string.settingshub_whats_new_names),
                    row(SettingsKey.SHOW_INTRO, "waving_hand", R.string.settingshub_show_intro,
                            R.string.settingshub_show_intro_desc, R.string.settingshub_show_intro_names),
                    row(SettingsKey.UPDATE_CHECK, "system_update", R.string.settingshub_update_check,
                            R.string.settingshub_update_check_desc, R.string.settingshub_update_check_names),
                    row(SettingsKey.DEBUG_LOG, "bug_report", R.string.settingshub_debug_log,
                            R.string.settingshub_debug_log_desc, R.string.settingshub_debug_log_names),
                    row(SettingsKey.SETTINGS_BACKUP, "settings_backup_restore", R.string.settingshub_settings_backup,
                            R.string.settingshub_settings_backup_desc, R.string.settingshub_settings_backup_names),
                    row(SettingsKey.ABOUT, "info", R.string.settingshub_about,
                            R.string.settingshub_about_desc, R.string.settingshub_about_names),
                    new Row(SettingsKey.RATE, "star_rate", R.string.settingshub_rate,
                            R.string.settingshub_rate_desc, R.string.settingshub_rate_names, Trail.LEAVE),
                    row(SettingsKey.CLASSIC_SETTINGS, "history", R.string.settingshub_classic,
                            R.string.settingshub_classic_desc, R.string.settingshub_classic_names))));

    private static final Map<SettingsKey, Row> ROWS = new EnumMap<>(SettingsKey.class);
    private static final Map<SettingsKey, Integer> SECTION_OF = new EnumMap<>(SettingsKey.class);

    static {
        for (int s = 0; s < SECTIONS.size(); s++) {
            for (Row r : SECTIONS.get(s).rows) {
                ROWS.put(r.key, r);
                SECTION_OF.put(r.key, s);
            }
        }
    }

    private static Row row(SettingsKey key, String glyph, int title, int summary, int names) {
        return new Row(key, glyph, title, summary, names, Trail.OPEN);
    }

    @NonNull
    static Row row(@NonNull SettingsKey key) {
        return ROWS.get(key);
    }

    @NonNull
    static Map<SettingsKey, Integer> sectionOf() {
        return Collections.unmodifiableMap(SECTION_OF);
    }

    /** Each section's rows that this device can use (What's new needs a web view, which a watch lacks). */
    @NonNull
    static List<List<SettingsKey>> shown(@NonNull Context c) {
        boolean watch = SettingsDestinations.isWatch(c);
        List<List<SettingsKey>> out = new ArrayList<>(SECTIONS.size());
        for (Section s : SECTIONS) {
            List<SettingsKey> keys = new ArrayList<>(s.rows.size());
            for (Row r : s.rows) {
                if (watch && r.key == SettingsKey.WHATS_NEW) continue;
                keys.add(r.key);
            }
            out.add(keys);
        }
        return out;
    }

    /** What search reads: every shown row's words, in the user's language. */
    @NonNull
    static List<SettingsSearch.Entry> entries(@NonNull Context c, @NonNull List<List<SettingsKey>> shown) {
        List<SettingsSearch.Entry> out = new ArrayList<>();
        for (int s = 0; s < shown.size(); s++) {
            String section = c.getString(SECTIONS.get(s).title);
            for (SettingsKey k : shown.get(s)) {
                Row r = ROWS.get(k);
                out.add(new SettingsSearch.Entry(k, c.getString(r.title), c.getString(r.summary),
                        section, c.getString(r.names)));
            }
        }
        return out;
    }
}
