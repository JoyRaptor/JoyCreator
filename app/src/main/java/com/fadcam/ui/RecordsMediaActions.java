package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.service.RecordsDeletionRequestItem;
import com.fadcam.service.RecordsDeletionService;
import com.fadcam.ui.faditor.FaditorEditorActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * The two things a recording can be sent on to, shared by the classic Records screen and the
 * Library's item sheet so both do exactly the same thing:
 * <ul>
 *   <li><b>Save to gallery</b>: queues a copy (or move) into Downloads/FadCam through the same
 *       background service the Records dock watches ({@link RecordsDeletionService}).</li>
 *   <li><b>Open in Studio</b>: starts the Studio editor with the recordings as its clips.</li>
 * </ul>
 */
public final class RecordsMediaActions {

    private RecordsMediaActions() {}

    /** The queue entry for one file; null when it has no address or no name to save under. */
    @Nullable
    public static RecordsDeletionRequestItem requestItem(@Nullable Uri uri, @Nullable String displayName, long size) {
        if (uri == null || displayName == null) return null;
        return new RecordsDeletionRequestItem(uri.toString(), displayName, Math.max(0L, size),
                "content".equals(uri.getScheme()));
    }

    /** Queues the save and returns at once; progress shows in the Records dock and the notification. */
    public static void saveToGallery(@NonNull Context c, @NonNull List<RecordsDeletionRequestItem> items,
                                     boolean moveFiles) {
        if (items.isEmpty()) return;
        RecordsDeletionService.startSaveSession(c, items, moveFiles);
    }

    /** Starts the Studio editor on these clips, in the order given. */
    public static void openInStudio(@NonNull Context c, @NonNull List<Uri> clips) {
        if (clips.isEmpty()) return;
        Intent intent = new Intent(c, FaditorEditorActivity.class);
        intent.putExtra(FaditorEditorActivity.EXTRA_VIDEO_URIS, new ArrayList<>(clips));
        if (!(c instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(intent);
    }
}
