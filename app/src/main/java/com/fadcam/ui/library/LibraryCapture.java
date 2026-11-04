package com.fadcam.ui.library;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.fadcam.R;
import com.fadcam.utils.RecordingStoragePaths;

/**
 * Which recorder made a recording: the Camera / Screen / Stream / Dual sub-filter of the
 * Recordings chip, the old Records tabs' sort of split. Read from what the item already says: the
 * classic Records' category (recordings outside the Joy Creator folder) or, for files in the folder,
 * the readable name the recorder gave it ("Screen recording 2026-..."), plus the older FadCam
 * file prefixes. A recording that says neither belongs to no sub-filter but still shows under
 * All recordings.
 */
final class LibraryCapture {

    private LibraryCapture() {}

    enum Source {
        CAMERA("videocam", R.string.library_source_camera),
        SCREEN("smartphone", R.string.library_source_screen),
        STREAM("cast", R.string.library_source_stream),
        DUAL("flip_camera_android", R.string.library_source_dual);

        final String glyph;
        @StringRes final int label;

        Source(String glyph, @StringRes int label) {
            this.glyph = glyph;
            this.label = label;
        }
    }

    /** The recorder behind a recording, or null when it cannot be told (or the item is not one). */
    @Nullable
    static Source of(@NonNull LibraryItem it) {
        if (it.kind != LibraryKind.RECORDINGS) return null;
        if (it.captureCategory != null) {
            switch (it.captureCategory) {
                case "CAMERA": return Source.CAMERA;
                case "SCREEN": return Source.SCREEN;
                case "STREAM": return Source.STREAM;
                case "DUAL":   return Source.DUAL;
                default:       break;   // fall through to the name
            }
        }
        String file = it.fileName == null ? "" : it.fileName;
        RecordingStoragePaths.JoyKind k = RecordingStoragePaths.JoyKind.ofName(file);
        if (k != null) {
            switch (k) {
                case CAMERA:
                case FRONT_CAMERA: return Source.CAMERA;
                case DUAL_CAMERA:  return Source.DUAL;
                case SCREEN:       return Source.SCREEN;
                case STREAM:       return Source.STREAM;
                default:           return null;
            }
        }
        if (file.startsWith(com.fadcam.Constants.RECORDING_FILE_PREFIX_FADREC)) return Source.SCREEN;
        if (file.startsWith("Stream_")) return Source.STREAM;
        return null;
    }
}
