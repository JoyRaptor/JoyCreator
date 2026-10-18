package com.fadcam.vislab.view;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Listens to the phone's own heat warning (Android 10+). "Hot" from SEVERE until it falls back to
 * LIGHT, so it does not flicker on and off at the line.
 *
 * <p>Why: on the Note 9 (2026-10-18, 24 minutes of looks back to back while charging) the back
 * of the phone reached 40 C, Android said SEVERE, and the phone cut its graphics clock from 710
 * to 414 MHz, which slows everything on the phone, a screen recording included. Easing off when
 * the phone asks lets it cool by itself instead.</p>
 */
final class HeatGuard {

    @Nullable private final PowerManager power;
    private boolean hot;
    private int status;

    HeatGuard(@NonNull Context context) {
        power = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? context.getSystemService(PowerManager.class) : null;
    }

    /** Ask the phone (a quick call; about once a second is plenty). True while it runs hot. */
    boolean check() {
        if (power == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        status = power.getCurrentThermalStatus();
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) hot = true;
        else if (status <= PowerManager.THERMAL_STATUS_LIGHT) hot = false;
        return hot;
    }

    /** The last status read: 0 none, 1 light, 2 moderate, 3 severe, 4 critical... */
    int status() {
        return status;
    }
}
