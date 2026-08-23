package com.fadcam.ui.faditor.model;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Shared audio parameters interface — implemented by both {@link Clip} and {@link AudioClip}.
 * This is the A7 shared carrier so a video clip's audio gets the identical drawer as a standalone audio clip.
 * The interface provides all audio parameters and operations needed by the audio drawer.
 */
public interface AudioParams {

    /** Clip-local time of the audio start within the source. */
    long getInPointMs();

    /** Clip-local time of the audio end within the source. */
    long getOutPointMs();

    /** Timeline position where this clip starts. */
    long getOffsetMs();

    /** Duration of the source media. */
    long getSourceDurationMs();

    /** Duration of the visible/trimmed portion. */
    long getTrimmedDurationMs();

    /** Current volume level (0.0–2.0). */
    float getVolumeLevel();

    /** Set volume level (0.0–2.0). */
    void setVolumeLevel(float level);

    /** Whether the clip is muted. */
    boolean isMuted();

    /** Set muted state. */
    void setMuted(boolean muted);

    /** Get volume keyframes. */
    @NonNull List<? extends VolumeKeyframe> getVolumeKeyframes();

    /** Whether there are any volume keyframes. */
    boolean hasVolumeKeyframes();

    /** Set volume keyframes (replaces all). */
    void setVolumeKeyframes(@NonNull List<? extends VolumeKeyframe> kfs);

    /** Add or update a volume keyframe at the given clip-local time. */
    void addOrUpdateVolumeKeyframe(long timeMs, float volume);

    /** Clear all volume keyframes. */
    void clearVolumeKeyframes();

    /** Get the effective gain at a clip-local time (applies keyframes + volumeLevel). */
    float gainAtClipMs(long clipMs);

    /** Pan value (-1..1). Default 0 (center). */
    float getPan();

    /** Set pan value (-1..1). */
    void setPan(float pan);

    /** Source URI of the media. */
    @NonNull Uri getSourceUri();

    /** Set source URI. */
    void setSourceUri(@NonNull Uri uri);

    /** Clip ID. */
    @NonNull String getId();

    /** Clip label/name. */
    @Nullable String getLabel();

    /** Set label. */
    void setLabel(@Nullable String label);

    /** Check if this clip has a baked source. */
    boolean isBakedSource();

    /** Get the original source URI before baking. */
    @Nullable String getBakedFromUri();

    /** Get the baked file path. */
    @Nullable String getBakedFromFile();

    /** Set baked source info. */
    void setBakedFrom(@Nullable String originalUri, @Nullable String bakedFilePath);

    /** Get clip-local time of a volume keyframe under (±66ms of) the playhead, or null. */
    @Nullable default Long volumeKeyUnderPlayhead(long absMs) {
        return null;
    }

    /** Remove the envelope keyframe at an exact clip-local ms. */
    default void removeVolumeKeyframeAt(long localMs) {
        // Default no-op
    }

    /** Seek to the nearest envelope key strictly before/after the playhead. */
    default void jumpToAdjacentVolumeKey(boolean forward) {
        // Default no-op for implementations that don't support this (e.g., Clip)
    }
}