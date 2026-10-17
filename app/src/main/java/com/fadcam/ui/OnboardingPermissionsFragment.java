package com.fadcam.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.github.appintro.SlidePolicy;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.type.Type;

/**
 * First-run page 2: the permissions. Handles the requests and enforces the slide policy for
 * AppIntro. Dressed like the "Before we start" page: three rows (camera, microphone, files)
 * each with a state mark that turns solid cyan, check punched out, once allowed, and one
 * gradient primary that asks for whatever is still missing. An optional fourth row offers
 * to turn battery optimisation off; it never gates anything.
 */
public class OnboardingPermissionsFragment extends Fragment implements SlidePolicy {
    private static final int PERMISSIONS_REQUEST_CODE = 101;
    private boolean permissionsGranted = false;
    private MaterialButton grantButton;
    private int permissionRequestCount = 0; // Track how many times requested
    private boolean permanentlyDenied = false;
    private TextView permissionStatusText; // Text view to show status instead of toasts
    private boolean statusToastShown = false; // Flag to prevent multiple toasts
    private TextView openSettingsLink;
    private boolean cameraOk, micOk, filesOk, batteryOk;
    private View root; // set before the first state pass, when getView() is still null

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.onboarding_permissions_slide, container, false);
        root = view;
        grantButton = view.findViewById(R.id.permissions_grant_button);
        permissionStatusText = view.findViewById(R.id.permission_status_text);
        if (permissionStatusText != null) {
            permissionStatusText.setVisibility(View.GONE); // Hide initially
        }
        grantButton.setEnabled(true);
        grantButton.setAlpha(1f);
        grantButton.setOnClickListener(v -> {
            if (!permissionsGranted) {
                // Always show the runtime permission dialog, even if permanently denied
                requestAllPermissionsAlways();
            }
        });
        applyTypefaces(view);
        checkPermissionsAndUpdateUI();
        if (!permissionsGranted && !permanentlyDenied) {
            requestAllPermissionsAlways();
        }

        openSettingsLink = view.findViewById(R.id.open_settings_link);
        if (openSettingsLink != null) {
            // Denied for good: the system will not ask again, so its settings page is the way.
            openSettingsLink.setOnClickListener(v2 -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            });
        }

        View batteryRow = view.findViewById(R.id.row_battery);
        if (batteryRow != null) {
            batteryRow.setOnClickListener(v3 -> {
                if (batteryOk) return;
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            });
        }
        return view;
    }

    /** Archivo for what is looked at, Plex for what is read, as on the other two pages. */
    private static void applyTypefaces(View view) {
        int[] display = {R.id.permissionsTitle, R.id.title_camera, R.id.title_mic,
                R.id.title_files, R.id.title_battery, R.id.permissions_grant_button};
        for (int id : display) {
            TextView t = view.findViewById(id);
            if (t != null) Type.display(t, id == R.id.permissionsTitle ? Type.BLACK : Type.BOLD);
        }
        int[] body = {R.id.permissionsDescription, R.id.why_camera, R.id.why_mic,
                R.id.why_files, R.id.why_battery, R.id.permission_status_text,
                R.id.open_settings_link};
        for (int id : body) {
            TextView t = view.findViewById(id);
            if (t != null) Type.body(t, Type.REGULAR);
        }
    }

    private void showPermissionStatus(int stringResId) {
        if (permissionStatusText != null) {
            permissionStatusText.setText(stringResId);
            permissionStatusText.setVisibility(View.VISIBLE);
        } else if (!statusToastShown) {
            Toast.makeText(requireContext(), stringResId, Toast.LENGTH_SHORT).show();
            statusToastShown = true;
        }
    }

    private void requestAllPermissionsAlways() {
        // Always request, system will ignore already granted
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        requestPermissions(permissions.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        permissionRequestCount++;
    }

    private boolean isAnyPermissionPermanentlyDenied() {
        // Check for each permission if it's denied and shouldShowRequestPermissionRationale is false
        if (getActivity() == null) return false;
        boolean denied = false;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.CAMERA)) denied = true;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.RECORD_AUDIO)) denied = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.READ_MEDIA_VIDEO)) denied = true;
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.POST_NOTIFICATIONS)) denied = true;
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)) denied = true;
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE)) denied = true;
        }
        return denied;
    }

    private boolean has(String permission) {
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissionsAndUpdateUI() {
        // The gate is exactly the set that is requested: camera, microphone, storage (media
        // on Android 13+, read and write before) and, on 13+, notifications.
        cameraOk = has(Manifest.permission.CAMERA);
        micOk = has(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            filesOk = has(Manifest.permission.READ_MEDIA_VIDEO) && has(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            filesOk = has(Manifest.permission.WRITE_EXTERNAL_STORAGE) && has(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        permissionsGranted = cameraOk && micOk && filesOk;
        permanentlyDenied = isAnyPermissionPermanentlyDenied();

        View view = root;
        if (view == null) return;

        setMark(view, R.id.row_camera, R.id.mark_camera, R.string.onboardingv2_perm_camera,
                R.string.onboardingv2_perm_camera_why, cameraOk);
        setMark(view, R.id.row_mic, R.id.mark_mic, R.string.onboardingv2_perm_mic,
                R.string.onboardingv2_perm_mic_why, micOk);
        setMark(view, R.id.row_files, R.id.mark_files, R.string.onboardingv2_perm_files,
                R.string.onboardingv2_perm_files_why, filesOk);

        android.os.PowerManager pm = (android.os.PowerManager) requireContext().getSystemService(android.content.Context.POWER_SERVICE);
        batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
        setMark(view, R.id.row_battery, R.id.mark_battery, R.string.onboardingv2_perm_battery,
                batteryOk ? R.string.onboardingv2_perm_battery_done : R.string.onboardingv2_perm_battery_why,
                batteryOk);
        TextView batteryWhy = view.findViewById(R.id.why_battery);
        if (batteryWhy != null) {
            batteryWhy.setText(batteryOk ? R.string.onboardingv2_perm_battery_done
                                         : R.string.onboardingv2_perm_battery_why);
        }
        // Only the not-yet-done battery row is a button; its own description says so.
        View batteryRow = view.findViewById(R.id.row_battery);
        if (batteryRow != null) {
            batteryRow.setClickable(!batteryOk);
            if (batteryOk) {
                batteryRow.setContentDescription(getString(R.string.onboardingv2_perm_row_cd,
                        getString(R.string.onboardingv2_perm_battery),
                        getString(R.string.onboardingv2_perm_state_granted),
                        getString(R.string.onboardingv2_perm_battery_done)));
            } else {
                batteryRow.setContentDescription(getString(R.string.onboardingv2_perm_battery_cd));
            }
        }

        if (permissionsGranted) {
            // A finished step is not a broken one: no dimming. It becomes a quiet filled pill
            // with the aqua carried in the ink, so nothing on the page shouts "press me".
            grantButton.setEnabled(false);
            grantButton.setAlpha(1f);
            grantButton.setBackgroundResource(R.drawable.intro_secondary_pill);
            grantButton.setTextColor(Studio.GO);
            grantButton.setText(R.string.onboardingv2_perm_all_set);
            grantButton.setContentDescription(getString(R.string.onboardingv2_perm_all_set_cd));
            if (permissionStatusText != null) permissionStatusText.setVisibility(View.GONE);
        } else {
            // Set explicitly: a permission revoked from Settings brings us back through here.
            grantButton.setEnabled(true);
            grantButton.setAlpha(1f);
            grantButton.setBackgroundResource(R.drawable.studio_action_pill);
            grantButton.setTextColor(Studio.ON_GO);
            grantButton.setText(R.string.onboardingv2_perm_allow);
            grantButton.setContentDescription(getString(R.string.onboardingv2_perm_allow_cd));
        }

        // Only after we have asked once, and only if the system will no longer ask.
        if (openSettingsLink != null) {
            openSettingsLink.setVisibility(
                    !permissionsGranted && permanentlyDenied && permissionRequestCount > 0
                            ? View.VISIBLE : View.GONE);
        }
    }

    /** One row's state mark: allowed = solid cyan disc with the check cut out; else a grey disc. */
    private void setMark(View view, int rowId, int markId, int titleRes, int whyRes, boolean on) {
        TextView mark = view.findViewById(markId);
        if (mark != null) {
            mark.setBackgroundResource(on ? R.drawable.perm_mark_on : R.drawable.perm_mark_off);
            mark.setVisibility(View.VISIBLE);
            // Same glyph either way; the "off" disc hides it by matching its own fill.
            mark.setTextColor(on ? Studio.GROUND : Studio.RAISED);
        }
        if (rowId == R.id.row_battery) return;
        View row = view.findViewById(rowId);
        if (row != null) {
            row.setContentDescription(getString(R.string.onboardingv2_perm_row_cd,
                    getString(titleRes),
                    getString(on ? R.string.onboardingv2_perm_state_granted
                                 : R.string.onboardingv2_perm_state_missing),
                    getString(whyRes)));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        checkPermissionsAndUpdateUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            checkPermissionsAndUpdateUI();
        }
    }

    @Override
    public boolean isPolicyRespected() {
        // Only allow Done if all permissions are granted
        return permissionsGranted;
    }

    @Override
    public void onUserIllegallyRequestedNextPage() {
        showPermissionStatus(R.string.onboardingv2_perm_nudge);
        if (grantButton != null) {
            grantButton.performClick();
        }
    }

    /**
     * Called to refresh language-specific UI elements when the language has changed
     * without recreating the entire fragment
     */
    public void refreshLanguage() {
        View view = getView();
        if (view == null) return;
        int[][] pairs = {
                {R.id.permissionsTitle, R.string.onboardingv2_perm_title},
                {R.id.permissionsDescription, R.string.onboardingv2_perm_why},
                {R.id.title_camera, R.string.onboardingv2_perm_camera},
                {R.id.why_camera, R.string.onboardingv2_perm_camera_why},
                {R.id.title_mic, R.string.onboardingv2_perm_mic},
                {R.id.why_mic, R.string.onboardingv2_perm_mic_why},
                {R.id.title_files, R.string.onboardingv2_perm_files},
                {R.id.why_files, R.string.onboardingv2_perm_files_why},
                {R.id.title_battery, R.string.onboardingv2_perm_battery},
                {R.id.open_settings_link, R.string.onboardingv2_perm_open_settings},
        };
        for (int[] p : pairs) {
            TextView t = view.findViewById(p[0]);
            if (t != null) t.setText(p[1]);
        }
        if (permissionStatusText != null && permissionStatusText.getVisibility() == View.VISIBLE) {
            permissionStatusText.setText(R.string.onboardingv2_perm_nudge);
        }
        // Button text, battery line and row descriptions follow the current state.
        checkPermissionsAndUpdateUI();
        if (view instanceof ViewGroup) {
            view.invalidate();
            view.requestLayout();
        }
    }
}
