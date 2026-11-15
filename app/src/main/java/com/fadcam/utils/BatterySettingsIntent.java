package com.fadcam.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import com.fadcam.BuildConfig;

/**
 * The screen that lets the person exempt Joy Creator from battery optimisation, so long recordings are not cut.
 *
 * The side-loaded build asks Android directly (one "Allow" dialog), which needs the
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission. Google Play restricts that permission, so the Play build
 * (SHOW-1) opens Android's own battery-optimisation list instead, where the person picks Joy Creator themselves.
 */
public final class BatterySettingsIntent {

    private BatterySettingsIntent() { }

    public static Intent forThisApp(Context context) {
        if (BuildConfig.PLAY_BUILD) {
            return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }
}
