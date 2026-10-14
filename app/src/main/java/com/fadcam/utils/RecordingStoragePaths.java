package com.fadcam.utils;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;

import java.io.File;

/**
 * Centralized helpers for recording/export storage paths across internal and SAF modes.
 */
public final class RecordingStoragePaths {

    public enum Category {
        CAMERA,
        DUAL,
        SCREEN,
        FADITOR,
        STREAM,
        SHOT,
        MINIAPPS,
        MINIAPPS_QR,
        UNKNOWN
    }

    public enum ShotSource {
        BACK,
        SELFIE,
        FADREC
    }

    public enum CameraSource {
        BACK,
        FRONT,
        DUAL
    }

    public enum FaditorOutputType {
        CONVERTED,
        MERGE
    }

    private RecordingStoragePaths() {
    }

    @NonNull
    public static String folderNameForCategory(@NonNull Category category) {
        switch (category) {
            case CAMERA:
                return Constants.RECORDING_SUBDIR_CAMERA;
            case DUAL:
                return Constants.RECORDING_SUBDIR_DUAL;
            case SCREEN:
                return Constants.RECORDING_SUBDIR_SCREEN;
            case FADITOR:
                return Constants.RECORDING_SUBDIR_FADITOR;
            case STREAM:
                return Constants.RECORDING_SUBDIR_STREAM;
            case SHOT:
                return Constants.RECORDING_SUBDIR_SHOT;
            case MINIAPPS:
                return Constants.RECORDING_SUBDIR_MINIAPPS;
            case MINIAPPS_QR:
                return Constants.RECORDING_SUBDIR_MINIAPPS_QR;
            case UNKNOWN:
            default:
                return "";
        }
    }

    @NonNull
    public static Category categoryFromFolderName(@Nullable String folderName) {
        if (folderName == null) return Category.UNKNOWN;
        if (Constants.RECORDING_SUBDIR_CAMERA.equals(folderName)) return Category.CAMERA;
        if (Constants.RECORDING_SUBDIR_DUAL.equals(folderName)) return Category.DUAL;
        if (Constants.RECORDING_SUBDIR_SCREEN.equals(folderName)) return Category.SCREEN;
        if (Constants.RECORDING_SUBDIR_FADITOR.equals(folderName)) return Category.FADITOR;
        if (Constants.RECORDING_SUBDIR_STREAM.equals(folderName)) return Category.STREAM;
        if (Constants.RECORDING_SUBDIR_SHOT.equals(folderName)) return Category.SHOT;
        if (Constants.RECORDING_SUBDIR_MINIAPPS.equals(folderName)) return Category.MINIAPPS;
        if (Constants.RECORDING_SUBDIR_MINIAPPS_QR.equals(folderName)) return Category.MINIAPPS_QR;
        return Category.UNKNOWN;
    }

    @NonNull
    public static String folderNameForShotSource(@Nullable ShotSource source) {
        if (source == null) return Constants.RECORDING_SUBDIR_SHOT_BACK;
        switch (source) {
            case SELFIE:
                return Constants.RECORDING_SUBDIR_SHOT_SELFIE;
            case FADREC:
                return Constants.RECORDING_SUBDIR_SHOT_FADREC;
            case BACK:
            default:
                return Constants.RECORDING_SUBDIR_SHOT_BACK;
        }
    }

    @NonNull
    public static String folderNameForCameraSource(@Nullable CameraSource source) {
        if (source == null) return Constants.RECORDING_SUBDIR_CAMERA_BACK;
        switch (source) {
            case FRONT:
                return Constants.RECORDING_SUBDIR_CAMERA_FRONT;
            case DUAL:
                return Constants.RECORDING_SUBDIR_CAMERA_DUAL;
            case BACK:
            default:
                return Constants.RECORDING_SUBDIR_CAMERA_BACK;
        }
    }

    @NonNull
    public static String folderNameForFaditorOutput(@Nullable FaditorOutputType outputType) {
        if (outputType == null) return Constants.RECORDING_SUBDIR_FADITOR_CONVERTED;
        switch (outputType) {
            case MERGE:
                return Constants.RECORDING_SUBDIR_FADITOR_MERGE;
            case CONVERTED:
            default:
                return Constants.RECORDING_SUBDIR_FADITOR_CONVERTED;
        }
    }

    @Nullable
    public static File getInternalBaseDir(@NonNull Context context) {
        File external = context.getExternalFilesDir(null);
        if (external == null) return null;
        return new File(external, Constants.RECORDING_DIRECTORY);
    }

    @Nullable
    public static File getInternalCategoryDir(
            @NonNull Context context,
            @NonNull Category category,
            boolean createIfMissing
    ) {
        File base = getInternalBaseDir(context);
        if (base == null) return null;
        if (!base.exists() && createIfMissing && !base.mkdirs()) return null;
        String childName = folderNameForCategory(category);
        if (childName.isEmpty()) return base;
        File child = new File(base, childName);
        if (!child.exists() && createIfMissing && !child.mkdirs()) return null;
        return child;
    }

    @Nullable
    public static File getInternalShotSourceDir(
            @NonNull Context context,
            @Nullable ShotSource source,
            boolean createIfMissing
    ) {
        File shotRoot = getInternalCategoryDir(context, Category.SHOT, createIfMissing);
        if (shotRoot == null) return null;
        String sourceFolder = folderNameForShotSource(source);
        File sourceDir = new File(shotRoot, sourceFolder);
        if (!sourceDir.exists() && createIfMissing && !sourceDir.mkdirs()) return null;
        return sourceDir;
    }

    @Nullable
    public static File getInternalCameraSourceDir(
            @NonNull Context context,
            @Nullable CameraSource source,
            boolean createIfMissing
    ) {
        File cameraRoot = getInternalCategoryDir(context, Category.CAMERA, createIfMissing);
        if (cameraRoot == null) return null;
        String sourceFolder = folderNameForCameraSource(source);
        File sourceDir = new File(cameraRoot, sourceFolder);
        if (!sourceDir.exists() && createIfMissing && !sourceDir.mkdirs()) return null;
        return sourceDir;
    }

    @Nullable
    public static File getInternalFaditorOutputDir(
            @NonNull Context context,
            @Nullable FaditorOutputType outputType,
            boolean createIfMissing
    ) {
        File faditorRoot = getInternalCategoryDir(context, Category.FADITOR, createIfMissing);
        if (faditorRoot == null) return null;
        String sourceFolder = folderNameForFaditorOutput(outputType);
        File sourceDir = new File(faditorRoot, sourceFolder);
        if (!sourceDir.exists() && createIfMissing && !sourceDir.mkdirs()) return null;
        return sourceDir;
    }

    @Nullable
    public static DocumentFile getSafBaseDir(@NonNull Context context, @Nullable String treeUriString) {
        if (treeUriString == null || treeUriString.isEmpty()) return null;
        try {
            Uri treeUri = Uri.parse(treeUriString);
            DocumentFile base = DocumentFile.fromTreeUri(context, treeUri);
            if (base == null || !base.exists() || !base.canWrite()) return null;
            return base;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public static DocumentFile getSafCategoryDir(
            @NonNull Context context,
            @Nullable String treeUriString,
            @NonNull Category category,
            boolean createIfMissing
    ) {
        DocumentFile joy = joyRecordingsDir(context, treeUriString);
        if (joy != null) return joy;
        DocumentFile base = getSafBaseDir(context, treeUriString);
        if (base == null) return null;
        String folderName = folderNameForCategory(category);
        if (folderName.isEmpty()) return base;
        return findOrCreateChildDirectory(base, folderName, createIfMissing);
    }

    @Nullable
    public static DocumentFile getSafShotSourceDir(
            @NonNull Context context,
            @Nullable String treeUriString,
            @Nullable ShotSource source,
            boolean createIfMissing
    ) {
        DocumentFile joy = joyRecordingsDir(context, treeUriString);
        if (joy != null) return joy;
        DocumentFile shotRoot = getSafCategoryDir(context, treeUriString, Category.SHOT, createIfMissing);
        if (shotRoot == null) return null;
        return findOrCreateChildDirectory(shotRoot, folderNameForShotSource(source), createIfMissing);
    }

    @Nullable
    public static DocumentFile getSafCameraSourceDir(
            @NonNull Context context,
            @Nullable String treeUriString,
            @Nullable CameraSource source,
            boolean createIfMissing
    ) {
        DocumentFile joy = joyRecordingsDir(context, treeUriString);
        if (joy != null) return joy;
        DocumentFile cameraRoot = getSafCategoryDir(context, treeUriString, Category.CAMERA, createIfMissing);
        if (cameraRoot == null) return null;
        return findOrCreateChildDirectory(cameraRoot, folderNameForCameraSource(source), createIfMissing);
    }

    @Nullable
    public static DocumentFile getSafFaditorOutputDir(
            @NonNull Context context,
            @Nullable String treeUriString,
            @Nullable FaditorOutputType outputType,
            boolean createIfMissing
    ) {
        DocumentFile joy = joyRecordingsDir(context, treeUriString);
        if (joy != null) return joy;
        DocumentFile faditorRoot = getSafCategoryDir(context, treeUriString, Category.FADITOR, createIfMissing);
        if (faditorRoot == null) return null;
        return findOrCreateChildDirectory(faditorRoot, folderNameForFaditorOutput(outputType), createIfMissing);
    }

    @Nullable
    public static DocumentFile findOrCreateChildDirectory(
            @NonNull DocumentFile parent,
            @NonNull String childName,
            boolean createIfMissing
    ) {
        for (DocumentFile child : parent.listFiles()) {
            if (child != null && child.isDirectory() && childName.equals(child.getName())) {
                return child;
            }
        }
        if (!createIfMissing) return null;
        return parent.createDirectory(childName);
    }

    // ── The Joy Creator folder ──────────────────────────────────────────
    //
    // When the recorder's custom location is the user's Joy Creator folder, recordings follow the
    // folder's rules instead of the old layout: no Camera/Back, Screen/, FadShot/… subfolders, just
    // the folder itself (or its Recordings folder, once the user has one), and names a person can
    // read: "Screen recording 2026-10-13 14.32.mp4". Everywhere else nothing changes.

    /** What a recording is, and the words its file name starts with in the Joy Creator folder. */
    public enum JoyKind {
        CAMERA("Camera"),
        FRONT_CAMERA("Front camera"),
        DUAL_CAMERA("Dual camera"),
        SCREEN("Screen recording"),
        STREAM("Stream"),
        PHOTO("Photo"),
        SELFIE("Selfie"),
        SCREENSHOT("Screenshot"),
        CONVERTED("Converted"),
        MERGED("Merged");

        public final String label;

        JoyKind(String label) {
            this.label = label;
        }

        /** The kind a readable name starts with ("Screen recording 2026-…"), or null for any other name. */
        @Nullable
        public static JoyKind ofName(@Nullable String fileName) {
            if (fileName == null) return null;
            JoyKind best = null;
            for (JoyKind k : values()) {
                if (fileName.startsWith(k.label + " ") && (best == null || k.label.length() > best.label.length())) {
                    best = k;
                }
            }
            return best;
        }
    }

    /** True when {@code treeUriString} is the user's Joy Creator folder. */
    public static boolean isJoyFolder(@NonNull Context context, @Nullable String treeUriString) {
        if (treeUriString == null || treeUriString.isEmpty()) return false;
        Uri joy = com.fadcam.joyfolder.android.JoyFolderHome.treeUri(context);
        return joy != null && joy.toString().equals(treeUriString);
    }

    /** Where recordings go inside the Joy Creator folder: its Recordings folder if the user has one, else the top. */
    @NonNull
    private static String joyRecordingsRel(@NonNull Context context) {
        com.fadcam.joyfolder.JoyFolder jf = com.fadcam.joyfolder.android.JoyFolderHome.open(context);
        if (jf == null) return "";
        try {
            if (jf.roles.reconcile(jf.fs)) jf.saveRoles();   // a Recordings folder made a moment ago counts
        } catch (Exception ignored) {
        }
        String rel = jf.roles.get(com.fadcam.joyfolder.FolderRoles.Role.RECORDINGS);
        return rel == null ? "" : rel;
    }

    @Nullable
    private static DocumentFile joyRecordingsDir(@NonNull Context context, @Nullable String treeUriString) {
        if (!isJoyFolder(context, treeUriString)) return null;
        DocumentFile dir = getSafBaseDir(context, treeUriString);
        if (dir == null) return null;
        String rel = joyRecordingsRel(context);
        if (rel.isEmpty()) return dir;
        for (String part : rel.split("/")) {
            DocumentFile child = findOrCreateChildDirectory(dir, part, false);
            if (child == null) return getSafBaseDir(context, treeUriString);   // gone: back to the top
            dir = child;
        }
        return dir;
    }

    /**
     * The file name for a new recording when it goes into the Joy Creator folder, or null when it
     * doesn't (the caller keeps its usual name). Unique in its folder: a second take in the same
     * minute becomes "… 14.32 2.mp4".
     *
     * @param part   0 or 1 for the first piece of a recording, 2+ for the pieces after a split
     * @param ending including its dot, e.g. ".mp4"
     */
    @Nullable
    public static String joyRecordingName(@NonNull Context context, @Nullable String treeUriString,
                                          @NonNull JoyKind kind, int part, @NonNull String ending) {
        if (!isJoyFolder(context, treeUriString)) return null;
        String name = com.fadcam.joyfolder.ReadableNames.recording(kind.label, System.currentTimeMillis(),
                java.util.TimeZone.getDefault(), (part > 1 ? " part " + part : "") + ending);
        com.fadcam.joyfolder.JoyFolder jf = com.fadcam.joyfolder.android.JoyFolderHome.open(context);
        if (jf == null) return name;
        try {
            return jf.freeName(joyRecordingsRel(context), name);
        } catch (Exception e) {
            return name;
        }
    }
}
