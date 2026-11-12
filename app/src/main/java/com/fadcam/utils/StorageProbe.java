package com.fadcam.utils;

import android.os.StatFs;
import android.system.Os;
import android.system.StructStatVfs;

import java.io.File;
import java.io.FileDescriptor;

/**
 * Free space where a recording is being written (work-loss audit F5). Asking the open file itself
 * answers for any place a recording can go, a path in the app or a file in the Joy Creator folder
 * on the phone or on an SD card, which a path-based check cannot do for the latter.
 *
 * <p>Every answer is {@code -1} when the phone cannot say; {@link RecordingRoom} treats that as
 * "carry on".</p>
 */
public final class StorageProbe {

    private StorageProbe() {}

    /** Bytes free on the volume that holds this open file, or -1. */
    public static long freeBytes(FileDescriptor fd) {
        if (fd == null) return -1;
        try {
            StructStatVfs s = Os.fstatvfs(fd);
            if (s.f_blocks <= 0) return -1;
            return s.f_bavail * s.f_frsize;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Bytes free on the volume that holds this file or folder (the nearest one that exists), or -1. */
    public static long freeBytes(File file) {
        File f = file;
        while (f != null && !f.exists()) f = f.getParentFile();
        if (f == null) return -1;
        try {
            return new StatFs(f.getAbsolutePath()).getAvailableBytes();
        } catch (Throwable t) {
            return -1;
        }
    }
}
