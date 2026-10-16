package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.ui.VideoItem;
import com.fadcam.ui.faditor.ai.BRollBucket;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.transcript.NamedTranscript;
import com.fadcam.ui.faditor.transcript.TranscriptWord;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a {@link FaditorProject} into the {@link ProjectFacts} Joybot reasons over.
 *
 * <p>Off the main thread: it walks every transcript and lists the b-roll folder.</p>
 */
public final class ProjectFactsReader {

    private ProjectFactsReader() {}

    /** A title counts as "at the start" when its text appears within this long of 0. */
    private static final long TITLE_WINDOW_MS = 3000;

    @NonNull
    public static ProjectFacts read(@NonNull Context context, @NonNull FaditorProject project,
                                    boolean online) {
        ProjectFacts f = new ProjectFacts();
        f.projectId = project.getId();
        f.projectName = project.getName() == null ? "" : project.getName().trim();
        f.online = online;
        f.canvasPreset = project.getCanvasPreset() == null ? "original" : project.getCanvasPreset();

        Timeline tl = project.getTimeline();
        if (tl == null) return f;
        f.totalMs = tl.getTotalDurationMs();

        long at = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            if (c == null) continue;
            ProjectFacts.ClipFacts cf = new ProjectFacts.ClipFacts();
            cf.id = c.getId();
            cf.name = nameOf(c);
            cf.startMs = at;
            cf.durationMs = c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs();
            at += Math.max(0, c.getVisualDurationMs());
            cf.muted = c.isAudioMuted();
            cf.still = c.isImageClip();
            cf.slide = c.isGeneratedSlide();
            cf.captionsOn = c.isCaptionsEnabled();
            readSpeech(cf, c.getTranscripts(), active(c.getActiveNamedTranscript(), c.getTranscripts()),
                    c.getRemovedSpans());
            List<long[]> silences = c.getSilenceCandidates();
            if (silences != null) {
                for (long[] s : silences) {
                    if (s == null || s.length < 2 || s[1] <= s[0]) continue;
                    if (Fillers.isCut(s[0], s[1], c.getRemovedSpans())) continue;
                    cf.silencesLeft++;
                    cf.silenceLeftMs += s[1] - s[0];
                }
            }
            f.spine.add(cf);
        }

        for (AudioClip ac : tl.getAudioClips()) {
            if (ac == null) continue;
            ProjectFacts.ClipFacts cf = new ProjectFacts.ClipFacts();
            cf.id = ac.getId();
            cf.name = nameOf(ac);
            cf.audioTrack = true;
            cf.startMs = ac.getOffsetMs();
            cf.durationMs = ac.getTrimmedDurationMs();
            cf.muted = ac.isMuted();
            cf.captionsOn = ac.isCaptionsEnabled();
            readSpeech(cf, ac.getTranscripts(), active(ac.getActiveNamedTranscript(), ac.getTranscripts()),
                    ac.getRemovedSpans());
            f.audio.add(cf);
        }

        f.transitions = tl.getTransitions().size();
        f.textOverlays = tl.getTextOverlays().size();
        f.overlayClips = tl.getOverlayClips().size();
        f.visualizers = tl.getWaveformOverlays().size();

        Clip first = tl.getClipCount() > 0 ? tl.getClip(0) : null;
        f.titleAtStart = first != null && first.isGeneratedSlide();
        if (!f.titleAtStart) {
            for (TextOverlayItem o : tl.getTextOverlays()) {
                if (!o.isImage() && o.getStartMs() < TITLE_WINDOW_MS
                        && o.getText() != null && !o.getText().trim().isEmpty()) {
                    f.titleAtStart = true;
                    break;
                }
            }
        }

        try {
            f.brollAssets = new BRollBucket(context).listAssets().size();
        } catch (Exception ignored) {
            // No storage permission or no folder: zero is the honest count of what we can use.
        }

        for (SpriteSheet s : project.getSpriteSheets()) {
            if (s == null) continue;
            ProjectFacts.SheetFacts sf = new ProjectFacts.SheetFacts();
            sf.id = s.getId();
            sf.name = s.getName();
            sf.cells = s.cellCount();
            sf.sequence = s.isSequence();
            for (String n : s.getCellNames().values()) if (n != null && !n.trim().isEmpty()) sf.namedCells++;
            sf.animations = s.getPresets().size();
            f.sheets.add(sf);
        }
        f.newestRecordingNotInProject = newestRecordingNotIn(context, tl);
        return f;
    }

    /**
     * The newest Library video that no clip on this timeline comes from, by its name; "" if none.
     * Off the main thread (the Library index may be read from disk). Any failure is "none".
     */
    @NonNull
    private static String newestRecordingNotIn(@NonNull Context context, @Nullable Timeline tl) {
        try {
            Set<String> used = new HashSet<>();
            if (tl != null) {
                for (int i = 0; i < tl.getClipCount(); i++) {
                    Clip c = tl.getClip(i);
                    if (c != null && c.getSourceUri() != null) used.add(c.getSourceUri().toString());
                }
                for (AudioClip ac : tl.getAudioClips()) {
                    if (ac != null && ac.getSourceUri() != null) used.add(ac.getSourceUri().toString());
                }
            }
            VideoItem best = null;
            List<VideoItem> library = VideoIndexRepository.getInstance(context)
                    .getVideos(SharedPreferencesManager.getInstance(context));
            for (VideoItem v : library) {
                if (v == null || v.uri == null || v.mediaType != VideoItem.MediaType.VIDEO) continue;
                if (v.isTemporary || v.isProcessingUri || v.isSkeleton) continue;
                if (used.contains(v.uri.toString())) continue;
                if (best == null || v.lastModified > best.lastModified) best = v;
            }
            return best == null ? "" : stripExt(best.displayName == null ? "" : best.displayName.trim());
        } catch (Exception e) {
            return "";
        }
    }

    @Nullable
    private static NamedTranscript active(@Nullable NamedTranscript a, @NonNull List<NamedTranscript> all) {
        if (a != null) return a;
        return all.isEmpty() ? null : all.get(0);
    }

    private static void readSpeech(ProjectFacts.ClipFacts cf, List<NamedTranscript> all,
                                   @Nullable NamedTranscript nt, List<long[]> removed) {
        for (NamedTranscript t : all) {
            if (t != null && "synth".equalsIgnoreCase(t.engine)) { cf.synthesized = true; break; }
        }
        if (nt == null || nt.transcript == null) return;
        cf.hasTranscript = true;
        long firstMs = -1, lastMs = -1;
        for (TranscriptWord w : nt.transcript.words) {
            if (Fillers.isCut(w.startMs, w.endMs, removed)) continue;
            boolean filler = w.struck || Fillers.isFiller(w.text);
            if (filler) {
                cf.fillersLeft++;
                if (w.struck) cf.fillersMarked++;
                continue;
            }
            cf.words++;
            if (firstMs < 0) firstMs = w.startMs;
            lastMs = Math.max(lastMs, w.endMs);
        }
        if (firstMs >= 0 && lastMs > firstMs) cf.speechMs = lastMs - firstMs;
    }

    /** A clip's human name: its rename, else its file name without the extension. */
    @NonNull
    public static String nameOf(@NonNull Clip clip) {
        String dn = clip.getDisplayName();
        if (dn != null && !dn.trim().isEmpty()) return stripExt(dn.trim());
        return fileName(clip.getSourceUri(), clip.isImageClip() ? "Picture" : "Video");
    }

    @NonNull
    public static String nameOf(@NonNull AudioClip clip) {
        String l = clip.getLabel();
        if (l != null && !l.trim().isEmpty()) return stripExt(l.trim());
        return fileName(clip.getSourceUri(), "Sound");
    }

    @NonNull
    private static String fileName(@Nullable Uri uri, @NonNull String fallback) {
        if (uri == null) return fallback;
        String s = uri.getLastPathSegment();
        if (s == null || s.isEmpty()) return fallback;
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        if (s.startsWith("faditor_")) s = s.substring(8);
        if (s.startsWith("tmp_")) s = s.substring(4);
        s = stripExt(s);
        return s.isEmpty() ? fallback : s;
    }

    @NonNull
    private static String stripExt(@NonNull String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 && s.length() - dot <= 5 ? s.substring(0, dot) : s;
    }
}
