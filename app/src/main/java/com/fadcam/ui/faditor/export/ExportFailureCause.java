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
    /**
     * FIX-5 (2026-09-21): the app's own helper copy was lost mid-export (cache eviction
     * pre-fix-1, or a failed warm remux). Retrying recreates it — this is never the
     * user's clips going missing, so it must not read as SOURCE_MISSING.
     */
    HELPER_LOST("A helper file the export was using went missing — it is recreated on"
            + " retry. Your project is safe.", true),
    /**
     * FIX-5: the pre-flight decode probe found a clip window no frame comes out of.
     * Retrying is cheap (the probe re-runs in about a minute, cool phone) and may pass
     * after a cool-down; the dialog shows the service's message verbatim (clip + source
     * time) rather than this template.
     */
    PREFLIGHT_UNREADABLE("A clip could not be read. Your project is safe.", true),
    /**
     * FIX-5: Media3's muxer watchdog ("no output sample written in N ms") — the pipeline
     * stalled, typically at a clip seam deep in a long export. Deliberately NOT
     * retryable: this exact stall failed twice at the identical 30:35 seam, and each
     * blind retry costs another hour on a hotter phone. The dialog offers options
     * (cool down, split, lower resolution) instead of a Retry loop.
     */
    SEAM_STALL("The export stalled while reading the video and had to stop. Let the phone"
            + " cool down with the app closed, keep it plugged in, then try again — if it"
            + " stops at the same place, trim around that spot. Your project is safe.", false),
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

        // FIX-5: check the app-specific failures BEFORE the generic buckets below —
        // a remux ENOENT is a FileNotFoundException too, but it is OUR helper, not the
        // user's missing clip, and the watchdog text deserves its own non-retryable cause.
        // Watchdog stall: "no output sample written in the last N milliseconds".
        if (lower.contains("no output sample written")) {
            return SEAM_STALL;
        }
        // Pre-flight probe failure (service-authored "clip N could not be read at M:SS").
        if (lowerClass.contains("windowprobefailure") || lower.contains("could not be read at")) {
            return PREFLIGHT_UNREADABLE;
        }
        // Helper lost: the warm-phase message ("helper file for X") or an ENOENT on a
        // remuxed copy (pre-fix-1 cache eviction, e.g. the 2026-09-21 11:32 failure).
        if (lower.contains("helper file")
                || ((lower.contains("-remuxed-") || lower.contains("remuxed"))
                    && (lower.contains("enoent") || lower.contains("no such file")))) {
            return HELPER_LOST;
        }

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
        if (lower.contains("no output sample written")) return SEAM_STALL;
        if (lowerClass.contains("windowprobefailure") || lower.contains("could not be read at"))
            return PREFLIGHT_UNREADABLE;
        if (lower.contains("helper file")
                || ((lower.contains("-remuxed-") || lower.contains("remuxed"))
                    && (lower.contains("enoent") || lower.contains("no such file"))))
            return HELPER_LOST;
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
