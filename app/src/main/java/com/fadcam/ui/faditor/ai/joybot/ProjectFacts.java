package com.fadcam.ui.faditor.ai.joybot;

import java.util.ArrayList;
import java.util.List;

/**
 * What Joybot is allowed to know about a project when he decides what to suggest.
 *
 * <p><b>Every field is a measurement.</b> Counts, durations and flags read straight off the
 * project; nothing estimated, nothing predicted. Joybot's rule is that he never invents a number,
 * and the only way to keep that rule is to make it impossible for a guess to reach the sentence
 * builder: the suggestions in {@link IdeaEngine} may only quote values held here.</p>
 *
 * <p>Plain Java on purpose (no Android, no org.json) so the decision tree runs in the JVM harness.
 * {@code ProjectFactsReader} fills it from a live project.</p>
 */
public final class ProjectFacts {

    /** One clip, video or audio, as Joybot sees it. */
    public static final class ClipFacts {
        public String id = "";
        /** A human name: the clip's display name or its file name, never an id. */
        public String name = "";
        /** True for a clip on an audio track; false for the video spine. */
        public boolean audioTrack;
        /** Where the clip starts on the timeline. */
        public long startMs;
        public long durationMs;
        public boolean muted;
        /** Still pictures and black spacers: nothing to transcribe, nothing to tighten. */
        public boolean still;
        /** An animated slide Joybot (or the user) generated. */
        public boolean slide;

        public boolean hasTranscript;
        /** Words not cut out of the video. */
        public int words;
        /** Total span of the transcript's kept words, first word start to last word end. */
        public long speechMs;
        /** Filler words still in the video: marked ones plus plain "um"/"uh" tokens. */
        public int fillersLeft;
        /** Of those, already marked for cutting (the synthesizer marks them). */
        public int fillersMarked;
        /** True once a "best of both" (synthesized) transcript exists. */
        public boolean synthesized;
        public boolean captionsOn;

        /** Quiet stretches the silence tool found that are not cut yet. */
        public int silencesLeft;
        public long silenceLeftMs;

        /** Could hold speech worth transcribing: real media with sound, long enough to matter. */
        public boolean speechCandidate() {
            return !still && !slide && !muted && durationMs >= 3000;
        }
    }

    /** A sprite sheet in the project. */
    public static final class SheetFacts {
        public String id = "";
        public String name = "";
        public int cells;
        public int namedCells;
        public int animations;
        /** Image sequences are timed frames, not cells to name. */
        public boolean sequence;
    }

    public String projectId = "";
    public String projectName = "";

    /** The video spine, in timeline order. */
    public final List<ClipFacts> spine = new ArrayList<>();
    /** Audio-track clips, in timeline order. */
    public final List<ClipFacts> audio = new ArrayList<>();

    public int transitions;
    public int textOverlays;
    /** Floating video/picture layers above the spine. */
    public int overlayClips;
    /** Waveform / spectrum visualizers. */
    public int visualizers;
    /** The first seconds already open on a title: a slide first, or text that starts at once. */
    public boolean titleAtStart;
    public String canvasPreset = "original";
    public long totalMs;

    /** Pictures/clips waiting in the shared b-roll bucket. */
    public int brollAssets;

    public final List<SheetFacts> sheets = new ArrayList<>();

    /** A key or a custom service is set, so tools that call a model can run. */
    public boolean online;

    // ── derived, still measurements ─────────────────────────────────────

    /** Real video clips on the spine: not stills, not slides. */
    public int videoClipCount() {
        int n = 0;
        for (ClipFacts c : spine) if (!c.still && !c.slide) n++;
        return n;
    }

    /** Spine clips of any kind, plus audio clips. */
    public boolean isEmpty() {
        return spine.isEmpty() && audio.isEmpty() && overlayClips == 0 && textOverlays == 0;
    }

    /** Sound but no moving picture: a song or a voice track over stills/black. */
    public boolean audioOnly() {
        return !audio.isEmpty() && videoClipCount() == 0;
    }
}
