package com.fadcam.ui.faditor.compositor;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.layers.Track;
import com.fadcam.ui.faditor.layers.TrackKind;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * PLAN §3.2 (M-COMP-1): the single authority deciding, from {@code timeline.getLayers()} /
 * {@code timeline.getAudioTracks()} + the M6 {@code TrackFlags} side-table at the current
 * playhead, WHAT each existing preview overlay surface should show.
 *
 * <p>This does NOT introduce new rendering machinery. It models the existing View overlay
 * stack ({@code TextOverlayLayer}, the audio {@code MediaPlayer} volume paths, and the new
 * {@link LayerImageOverlayView}) as tracks and feeds them filtered data through their
 * existing setters. {@code FaditorEditorActivity} calls these pure query methods from its
 * existing sync/playhead paths ({@code syncTimelineOverlays()}, {@code updateCurrentTimeDisplay()},
 * {@code refreshEditorAfterUndoRedo()}) instead of growing new logic inline.</p>
 *
 * <p>Stateless / no fields — every method takes the current {@link Timeline} and derives its
 * answer fresh, mirroring the "synchronized-from-flat, never cached" contract {@code Timeline}
 * itself documents for {@code getLayers()}/{@code getAudioTracks()}. This guarantees a plain
 * project (no hidden/muted flags, no image tracks) is a pure pass-through: {@link Track#isHidden()}
 * and {@link Track#isMuted()} are {@code false} by construction when no {@code TrackFlags} entry
 * exists (see {@code Timeline#applyTrackFlags}), so every filter below no-ops.</p>
 */
public final class LayerPreviewController {

    private LayerPreviewController() { }

    // ── Hidden TEXT/STICKER tracks → filtered TextOverlayLayer input ──────────────

    /**
     * The list to feed {@code TextOverlayLayer#setData}/{@code #rebuild}: every
     * {@link TextOverlayItem} belonging to a TEXT (or STICKER, once that kind is
     * produced) track that is NOT hidden. A plain project has exactly one TEXT track,
     * always unhidden by default, so this returns {@code timeline.getTextOverlays()}
     * unchanged (same objects, same order) — byte-identical behavior.
     */
    @NonNull
    public static List<TextOverlayItem> visibleTextOverlays(@NonNull Timeline timeline) {
        // M-EXPORT-1: this is now the SHARED authority for both the live preview
        // (TextOverlayLayer#setData call sites) AND the export path
        // (ExportManager#assembleClipVideoEffects → CompositeExportOverlay), so
        // visibility + draw-order decisions cannot diverge between the two.
        // Z-order: tracks are drawn in getLayers() order refined by a STABLE sort
        // on Track#getZIndex() (the M6 TrackFlags side-table value). Every track's
        // zIndex is 0 unless a flags entry says otherwise, so a plain project keeps
        // the exact original order (stable sort = no-op) — byte-identical behavior.
        // Per-item zHint is deliberately NOT consulted: TimedItem views are rebuilt
        // with default zHint=0 on every getLayers() call (M5 ephemeral-views note),
        // so within a track insertion order IS the z order today.
        List<Track> layers = new ArrayList<>(timeline.getLayers());
        layers.sort(java.util.Comparator.comparingInt(Track::getZIndex));
        List<TextOverlayItem> result = new ArrayList<>();
        for (Track track : layers) {
            if (track.getKind() != TrackKind.TEXT && track.getKind() != TrackKind.STICKER) continue;
            if (track.isHidden()) continue; // Mirrored on export (shared: ExportManager uses this method).
            for (TimedItem item : track.getItems()) {
                TextOverlayItem overlay = item.getTextOverlay();
                if (overlay != null) result.add(overlay);
            }
        }
        return result;
    }

    // ── IMAGE tracks → LayerImageOverlayView input ─────────────────────────────────

    /**
     * Every {@link TimedItem} from a visible (not hidden) IMAGE track, across all IMAGE
     * tracks, for {@link LayerImageOverlayView#setItems}. Always empty today — nothing
     * can create an IMAGE track yet (PLAN §3.2 scope item 4) — so this is inert for every
     * current project; it exists so M10's creation UI has a working preview path already.
     */
    @NonNull
    public static List<TimedItem> visibleImageItems(@NonNull Timeline timeline) {
        List<Track> layers = timeline.getLayers();
        List<TimedItem> result = new ArrayList<>();
        for (Track track : layers) {
            if (track.getKind() != TrackKind.IMAGE) continue;
            if (track.isHidden()) continue; // TODO(M-EXPORT-1): mirror this skip in ExportManager.
            result.addAll(track.getItems());
        }
        return result;
    }

    // ── Muted AUDIO tracks → per-clip preview-volume gate ──────────────────────────

    /**
     * Whether {@code clip}'s owning AUDIO track is muted (PLAN §3.2 scope item 3).
     * A plain project has one AUDIO track, unmuted by default ({@code Track#isMuted()}
     * is {@code false} unless a {@code TrackFlags} entry says otherwise), so this
     * returns {@code false} for every clip in every project that never touched the M6
     * mute toggle. Track mute MULTIPLIES over the clip's own {@code isMuted()}/volume —
     * callers gate with {@code clip.isMuted() || LayerPreviewController.isTrackMuted(...)},
     * never replacing the clip's own setting.
     */
    public static boolean isAudioClipTrackMuted(@NonNull Timeline timeline, @NonNull AudioClip clip) {
        List<Track> audioTracks = timeline.getAudioTracks();
        for (Track track : audioTracks) {
            if (track.getKind() != TrackKind.AUDIO) continue;
            if (!track.isMuted()) continue;
            for (TimedItem item : track.getItems()) {
                if (item.getAudioClip() == clip) return true;
            }
        }
        return false;
    }

    /**
     * Effective preview playback volume for an audio clip: 0 if the clip itself is
     * muted OR its owning track is muted (track-mute multiplies over, never overwrites,
     * the clip's own mute/level per PLAN §3.2 scope item 3), else the clip's own level.
     * Convenience wrapper around {@link #isAudioClipTrackMuted} for the five call sites
     * in {@code FaditorEditorActivity} that already compute
     * {@code ac.isMuted() ? 0f : ac.getVolumeLevel()}.
     */
    public static float effectivePreviewVolume(@NonNull Timeline timeline, @NonNull AudioClip clip) {
        if (clip.isMuted() || isAudioClipTrackMuted(timeline, clip)) return 0f;
        return clip.getVolumeLevel();
    }
}
