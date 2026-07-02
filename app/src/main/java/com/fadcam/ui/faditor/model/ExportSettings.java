package com.fadcam.ui.faditor.model;

/**
 * Configuration for video export.
 * Encapsulates resolution, quality, and format choices.
 */
public class ExportSettings {

    /** Output resolution presets. */
    public enum Resolution {
        /** Keep source resolution (no scaling). */
        ORIGINAL,
        /** 1920 x 1080 */
        FHD_1080P,
        /** 1280 x 720 */
        HD_720P,
        /** 854 x 480 */
        SD_480P
    }

    /** Export quality level (maps to encoder bitrate). */
    public enum Quality {
        HIGH,
        MEDIUM,
        LOW
    }

    /** Container format. */
    public enum Format {
        MP4,
        WEBM
    }

    private Resolution resolution = Resolution.ORIGINAL;
    private Quality quality = Quality.HIGH;
    private Format format = Format.MP4;

    /**
     * One-button audio cleanup: when on, the exported file gets a post-pass that
     * removes background noise, smooths dynamics, and performs two-pass loudness
     * normalization with true-peak limiting (ffmpeg highpass + afftdn + dynaudnorm
     * + two-pass loudnorm). Best for amateur footage recorded in noisy rooms or
     * with scenes at very different volumes.
     */
    private boolean cleanAudio = false;

    /**
     * Optional user-chosen output file name (without extension), set from the
     * export confirmation dialog. When {@code null} or blank, {@link
     * com.fadcam.ui.faditor.export.ExportManager} falls back to its default
     * "Faditor_&lt;timestamp&gt;" name — i.e. existing behavior is unchanged
     * unless the user actively edits the field.
     */
    private String outputFileName = null;

    // ── Getters ──────────────────────────────────────────────────────

    public boolean isCleanAudio() {
        return cleanAudio;
    }

    public void setCleanAudio(boolean cleanAudio) {
        this.cleanAudio = cleanAudio;
    }

    public String getOutputFileName() {
        return outputFileName;
    }

    public void setOutputFileName(String outputFileName) {
        this.outputFileName = outputFileName;
    }

    public Resolution getResolution() {
        return resolution;
    }

    public Quality getQuality() {
        return quality;
    }

    public Format getFormat() {
        return format;
    }

    // ── Setters ──────────────────────────────────────────────────────

    public void setResolution(Resolution resolution) {
        this.resolution = resolution;
    }

    public void setQuality(Quality quality) {
        this.quality = quality;
    }

    public void setFormat(Format format) {
        this.format = format;
    }

    @Override
    public String toString() {
        return "ExportSettings{" + resolution + ", " + quality + ", " + format + "}";
    }
}
