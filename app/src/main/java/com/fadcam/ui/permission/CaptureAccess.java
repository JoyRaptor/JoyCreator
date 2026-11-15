package com.fadcam.ui.permission;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.Studio;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Asks for the camera and microphone where they are first needed: on the way into Capture (SHELL-36, 11-14).
 *
 * <p>Until now the intro asked for everything before the app was even open (camera, microphone, media, notifications),
 * and Capture's own record path relied on that ("handled by onboarding"). Google Play reviewers flag permission walls
 * at startup, and a person cannot judge a request for a camera before they have seen what it is for. So the intro
 * asks for nothing, and this gate stands in front of Capture instead: a short sheet in plain words, then Android's own
 * prompt, then Capture. Notifications (the recording notice) and, on older Android, storage ride along as optional:
 * declining them never blocks Capture.</p>
 *
 * <p>Every other room already asks for itself at the moment of use (Studio's voiceover, VisLab's microphone, Joybot's
 * voice, Avatar's camera, the QR scanner, Photo, location in watermark settings).</p>
 *
 * <p>Create it in the activity's field initialiser or {@code onCreate} (it registers an ActivityResult launcher).</p>
 */
public final class CaptureAccess {

    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String[]> launcher;
    @Nullable private Runnable onReady;

    public CaptureAccess(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.launcher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::answered);
    }

    /**
     * What Capture cannot work without, for the mode it opens in. Camera mode films with sound: camera and microphone.
     * Screen mode asks for itself (Android's screen-capture consent, and it offers to record muted without the
     * microphone), so it needs nothing here; a screen-only person is never asked for the camera.
     */
    @NonNull
    String[] needed() {
        String mode = com.fadcam.SharedPreferencesManager.getInstance(activity).getCurrentRecordingMode();
        if (com.fadcam.Constants.MODE_FADREC.equals(mode)) return new String[0];
        return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    }

    /** Asked for alongside, never required: the recording notice, and storage on Android 12 and older. */
    @NonNull
    static String[] optional() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    private boolean has(@NonNull String p) {
        return ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED;
    }

    /** True when Capture can open without asking anything. */
    public boolean ready() {
        for (String p : needed()) if (!has(p)) return false;
        return true;
    }

    /** Runs {@code then} once what Capture needs is allowed; asks first when it is not. Also the record button's
     *  safety net: a permission turned off in Android's settings later is asked for again, not failed silently. */
    public void open(@NonNull Runnable then) {
        if (ready()) { then.run(); return; }
        onReady = then;
        explain();
    }

    // ── the sheet ───────────────────────────────────────────────────────────

    private void explain() {
        BottomSheetDialog d = new BottomSheetDialog(activity, R.style.CustomBottomSheetDialogTheme);
        SheetKit.install(d, null);
        LinearLayout root = column();
        SheetKit.Header header = SheetKit.header(activity, activity.getString(R.string.access_capture_title), null);
        header.addTrailing(SheetKit.closeButton(activity, d::dismiss));
        root.addView(header.view);
        root.addView(body(activity.getString(R.string.access_capture_why)));
        root.addView(buttons(
                SheetKit.pillButton(activity, activity.getString(R.string.access_not_now), false, v -> {
                    d.dismiss();
                    onReady = null;
                }),
                SheetKit.pillButton(activity, activity.getString(R.string.access_allow), true, v -> {
                    d.dismiss();
                    request();
                })));
        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
    }

    /** Android said no and will not ask again: the only way left is its own settings page. */
    private void explainSettings() {
        BottomSheetDialog d = new BottomSheetDialog(activity, R.style.CustomBottomSheetDialogTheme);
        SheetKit.install(d, null);
        LinearLayout root = column();
        SheetKit.Header header = SheetKit.header(activity, activity.getString(R.string.access_capture_title), null);
        header.addTrailing(SheetKit.closeButton(activity, d::dismiss));
        root.addView(header.view);
        root.addView(body(activity.getString(R.string.access_capture_blocked)));
        root.addView(buttons(
                SheetKit.pillButton(activity, activity.getString(R.string.access_not_now), false, v -> d.dismiss()),
                SheetKit.pillButton(activity, activity.getString(R.string.access_open_settings), true, v -> {
                    d.dismiss();
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", activity.getPackageName(), null)));
                    } catch (Exception ignored) {
                        // no settings app (very rare): nothing more to offer
                    }
                })));
        d.setContentView(SheetKit.fitNavBar(root));
        d.show();
    }

    private void request() {
        List<String> ask = new ArrayList<>();
        for (String p : needed()) if (!has(p)) ask.add(p);
        for (String p : optional()) if (!has(p)) ask.add(p);
        if (ask.isEmpty()) { ready(onReady); return; }
        launcher.launch(ask.toArray(new String[0]));
    }

    private void answered(@NonNull Map<String, Boolean> result) {
        Runnable then = onReady;
        onReady = null;
        if (ready()) { ready(then); return; }
        // Not allowed. If Android will no longer show its prompt, say where the switch is instead.
        for (String p : needed()) {
            if (!has(p) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, p)) {
                explainSettings();
                return;
            }
        }
        android.widget.Toast.makeText(activity, R.string.access_capture_declined, android.widget.Toast.LENGTH_LONG).show();
    }

    private static void ready(@Nullable Runnable then) {
        if (then != null) then.run();
    }

    // ── small parts ─────────────────────────────────────────────────────────

    private LinearLayout column() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, SheetKit.dp(activity, 16));
        return root;
    }

    private TextView body(@NonNull String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(Studio.INK_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setLineSpacing(0, 1.2f);
        int pad = SheetKit.dp(activity, 20);
        t.setPadding(pad, SheetKit.dp(activity, 4), pad, SheetKit.dp(activity, 16));
        return t;
    }

    private LinearLayout buttons(@NonNull TextView secondary, @NonNull TextView primary) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        int pad = SheetKit.dp(activity, 20);
        row.setPadding(pad, 0, pad, 0);
        row.addView(secondary);
        android.view.View gap = new android.view.View(activity);
        gap.setLayoutParams(new LinearLayout.LayoutParams(SheetKit.dp(activity, 8), 1));
        row.addView(gap);
        row.addView(primary);
        return row;
    }
}
