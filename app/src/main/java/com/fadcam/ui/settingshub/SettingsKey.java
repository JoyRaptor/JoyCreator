package com.fadcam.ui.settingshub;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every row the Settings hub can show, by name, and the row of the classic Settings tab
 * ({@code res/layout/fragment_settings_home.xml}) each one takes over, so nothing the classic
 * screen offered can quietly go missing. Pure Java: {@code tools/settingshub-tests} reads the
 * classic layout and checks every one of its rows lands here or in {@link #LEGACY_LEFT_OUT}.
 */
public enum SettingsKey {

    // ── Folder and storage ──
    JOY_FOLDER(null),
    RECORDING_LOCATION("group_storage"),
    SPACE(null),
    RECENTLY_DELETED(null),

    // ── Studio ──
    EDITOR(null),
    VIDEO_PLAYER("group_video_player_settings"),

    // ── Recording ──
    CAMERA_VIDEO("group_video_quick"),
    AUDIO("group_audio_quick"),
    SCREEN_RECORDING("group_screen_recording"),
    WATERMARK("group_watermark_quick"),

    // ── Appearance, notifications, security ──
    APPEARANCE("group_appearance"),
    NOTIFICATIONS("group_notifications"),
    SECURITY("group_security"),

    // ── Shortcuts and tools ──
    SHORTCUTS("group_widgets"),
    TORCH("group_mini_torch"),
    QR_SCANNER("group_mini_qr_scanner"),

    // ── Labs ──
    MOTION_LAB("group_motion_lab"),
    SMART_DETECTION("group_digital_forensics"),
    REMOTE(null),

    // ── About and help ──
    WHATS_NEW(null),
    SHOW_INTRO("group_onboarding"),
    UPDATE_CHECK("group_auto_update"),
    DEBUG_LOG("group_debug_logging"),
    SETTINGS_BACKUP("group_prefs_backup"),
    ABOUT("group_about"),
    RATE("group_review"),
    CLASSIC_SETTINGS(null);

    /** The classic Settings row this replaces ({@code R.id} name), or null for a row new to the hub. */
    public final String legacyRow;

    SettingsKey(String legacyRow) {
        this.legacyRow = legacyRow;
    }

    /**
     * Classic rows left out on purpose: the "Coming soon" mini-app cards. Each only opened a sheet
     * saying the tool does not exist yet, so a row for it would be a dead knob. They stay in the
     * classic screen, one tap away under Classic settings.
     */
    public static final List<String> LEGACY_LEFT_OUT = Collections.unmodifiableList(Arrays.asList(
            "group_mini_compass", "group_mini_sound_meter", "group_mini_sensor", "group_mini_speedometer",
            "group_mini_clinometer", "group_mini_pedometer", "group_mini_metal_detector",
            "group_mini_parking_marker", "group_mini_qr_generator"));

    /** The hub row that took over a classic row, or null. */
    public static SettingsKey forLegacyRow(String id) {
        if (id == null) return null;
        for (SettingsKey k : values()) if (id.equals(k.legacyRow)) return k;
        return null;
    }
}
