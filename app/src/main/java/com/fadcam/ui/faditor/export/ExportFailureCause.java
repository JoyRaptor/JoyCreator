package com.fadcam.ui.faditor.export;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Classified cause of an export failure — the first stone of the bug-reporting pipeline.
 * Retry is per-cause, decided here, not globally.
 */
public enum ExportFailureCause {
    STORAGE_FULL("Not enough space to save the export. Free up some room and try again.", true),
    SOURCE_MISSING("One of the clips can't be found. It may have been moved or deleted.", false),
    CODEC_FAILURE("This video couldn't be encoded. Try a different quality setting.", false),
    TRANSIENT_IO("The export was interrupted. Your project is safe.", true),
    UNKNOWN("Export failed. Your project is safe — nothing was lost.", false);

    @NonNull public final String userMessage;
    public final boolean retryable;

    ExportFailureCause(@NonNull String userMessage, boolean retryable) {
        this.userMessage = userMessage;
        this.retryable = retryable;
    }

    /**
     * Classify a throwable into a cause. Conservative: loose substring matching is avoided;
     * unrecognized errors fall through to UNKNOWN rather than a confident wrong diagnosis.
     */
    @NonNull
    public static ExportFailureCause classify(@Nullable Throwable error, @Nullable String message) {
        String msg = message != null ? message : "";
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        String className = error != null ? error.getClass().getName() : "";
        String lowerClass = className.toLowerCase(java.util.Locale.ROOT);

        // Storage full — ENOSPC / No space left
        if (lower.contains("enospc") || lower.contains("no space") || lower.contains("not enough space") || lower.contains("storage full")) {
            return STORAGE_FULL;
        }
        if (error instanceof java.io.IOException && lower.contains("space")) {
            // Conservative: only if message clearly indicates space
            return STORAGE_FULL;
        }

        // Source missing — FileNotFoundException is the clear signal
        if (error instanceof java.io.FileNotFoundException) return SOURCE_MISSING;
        if (lowerClass.contains("filenotfoundexception")) return SOURCE_MISSING;
        if (lower.contains("file not found") || lower.contains("enoent") || lower.contains("can't be found") || lower.contains("source file missing")) {
            return SOURCE_MISSING;
        }

        // Interrupted / transient I/O — InterruptedException, FileLock, etc.
        if (error instanceof InterruptedException) return TRANSIENT_IO;
        if (lowerClass.contains("interruptedexception") || lower.contains("interrupted") || lower.contains("file locked") || lower.contains("transient")) {
            return TRANSIENT_IO;
        }
        // IOException with interruption hint
        if (error instanceof java.io.IOException && (lower.contains("interrupted") || lower.contains("locked"))) {
            return TRANSIENT_IO;
        }

        // Codec / format failure — IllegalStateException from encoder, or explicit codec/format text
        if (lower.contains("codec") || lower.contains("encoder") || lower.contains("format") || lower.contains("mime")) {
            // Be conservative: only if message explicitly mentions codec/format, not generic IllegalState
            return CODEC_FAILURE;
        }
        if (error instanceof IllegalStateException && (lower.contains("codec") || lower.contains("encode"))) {
            return CODEC_FAILURE;
        }

        return UNKNOWN;
    }

    @NonNull
    public static ExportFailureCause classify(@Nullable String errorClass, @Nullable String message) {
        // errorClass is a String class name from ExportService broadcast (no Throwable object in UI process)
        String className = errorClass != null ? errorClass : "";
        String lowerClass = className.toLowerCase(java.util.Locale.ROOT);
        String msg = message != null ? message : "";
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        if (lowerClass.contains("filenotfoundexception")) return SOURCE_MISSING;
        if (lowerClass.contains("interruptedexception")) return TRANSIENT_IO;
        // Delegate to main classifier with a synthetic throwable for class-based checks
        // For string-only, we still want the same substring logic — reuse via null throwable + message
        // but also allow class name to trigger codec etc. via lowerClass
        if (lower.contains("enospc") || lower.contains("no space") || lower.contains("not enough space") || lower.contains("storage full")) return STORAGE_FULL;
        if (lower.contains("file not found") || lower.contains("enoent") || lower.contains("can't be found") || lower.contains("source file missing")) return SOURCE_MISSING;
        if (lower.contains("interrupted") || lower.contains("file locked") || lower.contains("transient")) return TRANSIENT_IO;
        if (lower.contains("codec") || lower.contains("encoder") || lower.contains("format") || lower.contains("mime")) return CODEC_FAILURE;
        return classify((Throwable) null, message);
    }

    @NonNull
    public static ExportFailureCause classify(@Nullable String message) {
        return classify((Throwable) null, message);
    }
}
