package com.fadcam.ui.studio;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.VideoSourceBottomSheet;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starting a Studio project: one flow, run by both the classic Studio list and the Library's
 * Projects view, so the two can never drift apart.
 *
 * <ol>
 *   <li>The source sheet ({@link VideoSourceBottomSheet}): a video from the phone, a blank audio
 *       project, or one of your recordings.</li>
 *   <li>One video opens straight in the editor as a new project. Several ask for a name, are
 *       measured and saved as one project in the order they were picked, then open.</li>
 * </ol>
 *
 * Moved whole out of FaditorMiniFragment; what it does is unchanged. Build it in the host's
 * {@code onCreate}: it registers for the picker's answer there, as the platform requires.
 */
public final class NewProjectFlow {

    private static final String TAG = "NewProjectFlow";
    /** The source sheet's tag in the host's child fragment manager (the classic list's own). */
    private static final String SHEET_TAG = "video_source";
    /** The default name offered for a multi-clip project: "Oct 16, 6:40 AM". */
    private static final String NAME_PATTERN = "MMM d, h:mm a";
    /** Measuring and saving a multi-clip project: off the main thread, one at a time. */
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "new-project");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Fragment host;
    @Nullable private final ActivityResultLauncher<Intent> editor;
    private final ActivityResultLauncher<Intent> picker;

    /**
     * @param editor how the host opens the editor when it wants to hear back (the classic list
     *               refreshes on return); null opens it with a plain {@code startActivity}
     */
    public NewProjectFlow(@NonNull Fragment host, @Nullable ActivityResultLauncher<Intent> editor) {
        this.host = host;
        this.editor = editor;
        picker = host.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onPicked);
        // A sheet brought back with its host (the app was closed in the background while it was
        // up) has lost its callback; give it this one, so its rows still do something.
        Fragment open = host.getChildFragmentManager().findFragmentByTag(SHEET_TAG);
        if (open instanceof VideoSourceBottomSheet) ((VideoSourceBottomSheet) open).setCallback(sheetCallback());
    }

    /** Show the source sheet: the first step of every new project. */
    public void start() {
        FragmentManager fm = host.getChildFragmentManager();
        if (fm.isStateSaved() || fm.findFragmentByTag(SHEET_TAG) != null) return;
        VideoSourceBottomSheet sheet = new VideoSourceBottomSheet();
        sheet.setCallback(sheetCallback());
        sheet.show(fm, SHEET_TAG);
    }

    /**
     * Open the editor: on a saved project by its id, or on a freshly picked video as a new one.
     * The classic list opens its rows through here as well.
     */
    public void openEditor(@NonNull Uri videoUri, @Nullable String projectId) {
        Intent intent = new Intent(host.requireContext(), FaditorEditorActivity.class);
        if (projectId != null) {
            // Saved project: the editor loads the source URI from storage itself.
            // Attaching the content:// URI with a grant flag here crashes the app
            // at launch if the persisted permission was lost (SecurityException).
            intent.putExtra(FaditorEditorActivity.EXTRA_PROJECT_ID, projectId);
        } else {
            // New project: URI is freshly picked, so the grant is valid.
            intent.setData(videoUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        launch(intent, R.string.projects_open_failed_access);
    }

    @NonNull
    private VideoSourceBottomSheet.Callback sheetCallback() {
        return new VideoSourceBottomSheet.Callback() {
            @Override
            public void onBrowseDevice() {
                openPicker();
            }

            @Override
            public void onStartBlankAudioProject() {
                // G21/B9: no video pick — the editor opens an empty audio project.
                launch(new Intent(host.requireContext(), FaditorEditorActivity.class)
                        .putExtra(FaditorEditorActivity.EXTRA_START_BLANK_AUDIO, true),
                        R.string.projects_open_failed);
            }

            @Override
            public void onRecordingSelected(@NonNull Uri videoUri) {
                openEditor(videoUri, null);
            }
        };
    }

    private void launch(@NonNull Intent intent, @StringRes int failure) {
        try {
            if (editor != null) editor.launch(intent);
            else host.startActivity(intent);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to launch editor", e);
            Toast.makeText(host.requireContext(), failure, Toast.LENGTH_LONG).show();
        }
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        // Several clips at once make one project, in the order they were picked.
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            picker.launch(intent);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to open video picker", e);
            Toast.makeText(host.requireContext(), R.string.faditor_error_picker, Toast.LENGTH_SHORT).show();
        }
    }

    /** One or many picked videos, in the order they were picked. */
    private void onPicked(@NonNull ActivityResult result) {
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || !host.isAdded()) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        for (Uri u : uris) {
            try {
                host.requireContext().getContentResolver().takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                FLog.w(TAG, "Could not take persistable URI permission", e);
            }
        }
        if (uris.size() == 1) openEditor(uris.get(0), null);
        else askNameThenBuild(uris);
    }

    private void askNameThenBuild(@NonNull List<Uri> uris) {
        Context c = host.requireContext();
        final EditText input = new EditText(c);
        input.setHint(R.string.faditor_new_project_hint);
        final String defaultName = new SimpleDateFormat(NAME_PATTERN, Locale.getDefault()).format(new Date());
        input.setText(defaultName);
        input.setSelectAllOnFocus(true);
        int pad = (int) (20 * c.getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new MaterialAlertDialogBuilder(c)
                .setTitle(c.getString(R.string.faditor_new_project_title, uris.size()))
                .setView(wrap)
                .setNegativeButton(R.string.faditor_cancel, null)
                .setPositiveButton(R.string.faditor_create, (d, w) -> {
                    String name = input.getText().toString().trim();
                    build(name.isEmpty() ? defaultName : name, uris);
                })
                .show();
    }

    /** Measure each video and save one project off the main thread, then open it. */
    private void build(@NonNull String name, @NonNull List<Uri> uris) {
        final Context app = host.requireContext().getApplicationContext();
        Toast.makeText(app, R.string.faditor_creating_project, Toast.LENGTH_SHORT).show();
        WORK.execute(() -> {
            FaditorProject project = new FaditorProject(name);
            Timeline timeline = project.getTimeline();
            int added = 0;
            for (Uri uri : uris) {
                long durationMs = probeDurationMs(app, uri);
                if (durationMs <= 0) {
                    FLog.w(TAG, "Skipping clip with unknown duration: " + uri);
                    continue;
                }
                Clip clip = new Clip(uri, durationMs);
                String shown = SourceName.of(app, uri.toString());
                clip.setDisplayName(shown != null ? shown : app.getString(R.string.projects_untitled));
                timeline.addClip(clip);
                added++;
            }
            final boolean saved = added > 0 && new ProjectStorage(app).save(project);
            MAIN.post(() -> {
                if (!host.isAdded()) return;
                if (!saved) {
                    Toast.makeText(app, R.string.faditor_create_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                // Open the saved multi-clip project by id (URI is loaded from storage).
                openEditor(uris.get(0), project.getId());
            });
        });
    }

    /** A video's length in milliseconds, or 0 if it can't be read. */
    private static long probeDurationMs(@NonNull Context c, @NonNull Uri uri) {
        MediaMetadataRetriever r = null;
        try {
            r = new MediaMetadataRetriever();
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                r.setDataSource(uri.getPath());
            } else {
                r.setDataSource(c, uri);
            }
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (r != null) try { r.release(); } catch (Exception ignored) { }
        }
    }
}
