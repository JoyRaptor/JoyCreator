package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Joybot's "what would help next?" — a local decision tree over {@link ProjectFacts}.
 *
 * <p>Owner, 2026-10-14: buttons should come up as automatic suggestions about the next likely
 * step. A long transcription → cut the ums; nothing transcribed → transcribe this. This is that,
 * built locally rather than as a model call, which keeps it instant, free and honest: every
 * sentence below quotes only numbers that are in the facts.</p>
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>At most {@link #MAX_IDEAS} ideas, most useful first; the first is Joybot's best guess and
 *       is the one his "I noticed…" sentence is about.</li>
 *   <li>{@link #SOMETHING_ELSE} is always last and only focuses the text box.</li>
 *   <li>A quiet idea (dismissed, or ignored twice) is never offered for that project again.</li>
 *   <li>Without a key or a custom service, ideas that need a model are not offered at all: an
 *       offline Joybot shows what works offline, not a row of buttons that each say "add a key".</li>
 * </ul>
 */
public final class IdeaEngine {

    private IdeaEngine() {}

    public static final int MAX_IDEAS = 3;

    /** Where Joybot was opened; the same project can call for different help in different rooms. */
    public enum Room { LOBBY, STUDIO, SPRITELAB, AVATAR, LIBRARY, JOYBRUSH }

    /** Guided flows that are actually built; an idea for an unbuilt flow is never shown. */
    public static final String FLOW_SLIDE = "slide";
    public static final String FLOW_PICTURE = "picture";
    public static final String ROUTE_RECORD = "record";

    /** Ideas Joybot offers and the sentence he opens with. */
    public static final class Offer {
        /** Joybot's opener, or null when he has nothing specific to say. */
        @Nullable public final String opener;
        /** Up to {@link #MAX_IDEAS} ideas, then {@link #SOMETHING_ELSE}. */
        @NonNull public final List<Idea> ideas;

        Offer(@Nullable String opener, @NonNull List<Idea> ideas) {
            this.opener = opener;
            this.ideas = Collections.unmodifiableList(ideas);
        }

        /** True when there is a real suggestion (not just "Something else?"). */
        public boolean hasIdea() {
            return !ideas.isEmpty() && ideas.get(0).kind != Idea.Kind.TYPE;
        }

        /** The suggestion the pulse is about, or null. */
        @Nullable
        public Idea best() {
            return hasIdea() ? ideas.get(0) : null;
        }
    }

    public static final Idea SOMETHING_ELSE = Idea.of("type", Idea.Kind.TYPE, "Something else?")
            .hint("Type or say what you'd like instead").build();

    /**
     * @param flows the guided flows this build ships ({@link #FLOW_SLIDE}, …)
     * @param quiet {@link Idea#quietId()}s and bare keys that must stay quiet
     */
    @NonNull
    public static Offer offer(@NonNull ProjectFacts f, @NonNull Room room,
                              @NonNull Set<String> flows, @NonNull Set<String> quiet) {
        List<Idea> all = new ArrayList<>();
        collect(f, room, flows, all);

        List<Idea> kept = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // Stable sort: equal priorities keep the order the rules produced them in.
        List<Idea> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> Integer.compare(b.priority, a.priority));
        for (Idea i : sorted) {
            if (i.needsKey && !f.online) continue;
            if (quiet.contains(i.quietId()) || quiet.contains(i.key)) continue;
            if (!seen.add(i.quietId())) continue;
            kept.add(i);
            if (kept.size() == MAX_IDEAS) break;
        }
        String opener = null;
        for (Idea i : kept) {
            if (i.opener != null) { opener = i.opener; break; }
        }
        // The opener must be about the FIRST button, or the sentence and the green button disagree.
        if (!kept.isEmpty() && kept.get(0).opener == null) opener = null;
        kept.add(SOMETHING_ELSE);
        return new Offer(opener, kept);
    }

    // ── the tree ────────────────────────────────────────────────────────────

    private static void collect(ProjectFacts f, Room room, Set<String> flows, List<Idea> out) {
        if (f.isEmpty()) {
            emptyProject(f, flows, out);
            return;
        }
        speech(f, out);
        title(f, flows, out);
        music(f, out);
        transitions(f, out);
        broll(f, out);
        sheets(f, room, out);
    }

    private static void emptyProject(ProjectFacts f, Set<String> flows, List<Idea> out) {
        String name = f.projectName.isEmpty() ? "This project" : q(f.projectName);
        String opener = name + " is empty. Want a hand getting started?";
        if (flows.contains(FLOW_SLIDE)) {
            out.add(Idea.of("intro_slide", Idea.Kind.FLOW, "Make an intro slide")
                    .flow(FLOW_SLIDE).say("Help me make an intro slide")
                    .opener(opener)
                    .hint("Answer three quick questions and I'll build an animated title card")
                    .priority(60).build());
        }
        if (flows.contains(FLOW_PICTURE)) {
            out.add(Idea.of("picture", Idea.Kind.FLOW, "Make a picture")
                    .flow(FLOW_PICTURE).say("Help me make a picture")
                    .opener(opener)
                    .hint("Design, generate, or bring in a picture from another app")
                    .priority(55).build());
        }
        out.add(Idea.of("record", Idea.Kind.ROUTE, "Record something")
                .flow(ROUTE_RECORD).say("I want to record something")
                .opener(opener)
                .hint("Go to Capture to film your screen or your camera")
                .priority(50).build());
    }

    /** Transcribe → captions → ums → silences → chapters: the talking-video ladder. */
    private static void speech(ProjectFacts f, List<Idea> out) {
        // Rung 1: sound with no words yet. Only the spine: the transcribe tool reads spine clips.
        List<ProjectFacts.ClipFacts> untranscribed = new ArrayList<>();
        for (ProjectFacts.ClipFacts c : f.spine) {
            if (c.speechCandidate() && !c.hasTranscript) untranscribed.add(c);
        }
        if (!untranscribed.isEmpty()) {
            ProjectFacts.ClipFacts first = untranscribed.get(0);
            boolean many = untranscribed.size() > 1;
            String opener = many
                    ? untranscribed.size() + " of your clips have sound but no words yet. Want me to transcribe them?"
                    : q(first.name) + " has sound but no words yet. Want me to transcribe it?";
            Idea.Builder t = Idea.of("transcribe", Idea.Kind.DIRECT,
                            many ? "Transcribe all " + untranscribed.size() : "Transcribe this")
                    .target(many ? "" : first.id)
                    .say(many ? "Transcribe my clips" : "Transcribe " + q(first.name))
                    .opener(opener)
                    .hint("Turn the speech into words, on the phone, no internet needed")
                    .undo(many ? "Transcribe " + untranscribed.size() + " clips" : "Transcribe " + first.name)
                    .priority(100);
            Idea.Builder c = Idea.of("captions_new", Idea.Kind.DIRECT, "Add captions")
                    .target(many ? "" : first.id)
                    .say(many ? "Add captions to my clips" : "Add captions to " + q(first.name))
                    .hint("Transcribe, then show the words on screen as captions")
                    .undo("Add captions")
                    .priority(99);
            for (ProjectFacts.ClipFacts u : untranscribed) {
                t.step(Idea.Step.of("generate_transcript", "clipId", u.id, "engine", "whisper"));
                c.step(Idea.Step.of("generate_transcript", "clipId", u.id, "engine", "whisper"));
                c.step(Idea.Step.of("toggle_captions", "clipId", u.id, "enabled", true));
            }
            out.add(t.build());
            out.add(c.build());
        }

        for (ProjectFacts.ClipFacts c : f.spine) {
            if (!c.hasTranscript || c.still || c.slide) continue;
            String n = q(c.name);

            // Rung 2: ums that are still in. A count of words we can SEE.
            if (c.fillersLeft > 0) {
                out.add(Idea.of("cut_ums", Idea.Kind.DIRECT, "Cut the ums")
                        .target(c.id).say("Cut the ums in " + n)
                        .opener(n + " still has " + c.fillersLeft + " filler word"
                                + (c.fillersLeft == 1 ? "" : "s") + " (um, uh) in it. Want me to cut "
                                + (c.fillersLeft == 1 ? "it" : "them") + "?")
                        .hint("Cut every um and uh out of the video; each cut can be tapped back")
                        .undo("Cut " + c.fillersLeft + " filler word" + (c.fillersLeft == 1 ? "" : "s"))
                        .step(Idea.Step.of("cut_all_fillers", "clipId", c.id))
                        .priority(90).build());
            } else if (!c.synthesized && c.words >= 40) {
                // Speech engines quietly drop ums. The two-engine pass is how they are found.
                out.add(Idea.of("find_ums", Idea.Kind.DIRECT, "Find the ums")
                        .target(c.id).say("Find and cut the ums in " + n)
                        .hint("Compare two transcriptions and the quiet gaps to find hidden ums, then cut them")
                        .undo("Find and cut the ums")
                        .step(Idea.Step.of("ai_enhance"))
                        .priority(52).build());
            }

            // Rung 3: words but no captions.
            if (!c.captionsOn && c.words > 0) {
                out.add(Idea.of("captions_on", Idea.Kind.DIRECT, "Turn on captions")
                        .target(c.id).say("Turn on captions for " + n)
                        .opener(n + " has a transcript, but captions are off.")
                        .hint("Show the transcript on screen as captions")
                        .undo("Turn on captions")
                        .step(Idea.Step.of("toggle_captions", "clipId", c.id, "enabled", true))
                        .priority(80).build());
            }

            // Rung 4: quiet stretches already found and not cut.
            if (c.silencesLeft > 0) {
                out.add(Idea.of("silences", Idea.Kind.DIRECT, "Remove silences")
                        .target(c.id).say("Remove the silences in " + n)
                        .opener("I found " + c.silencesLeft + " quiet stretch"
                                + (c.silencesLeft == 1 ? "" : "es") + " in " + n + " ("
                                + seconds(c.silenceLeftMs) + " in all) that "
                                + (c.silencesLeft == 1 ? "is" : "are") + " still in.")
                        .hint("Cut the quiet stretches; each cut can be tapped back")
                        .undo("Remove " + c.silencesLeft + " silence" + (c.silencesLeft == 1 ? "" : "s"))
                        .step(Idea.Step.of("remove_silence", "clipId", c.id))
                        .priority(75).build());
            } else if (c.speechMs >= 60_000) {
                out.add(Idea.of("tighten", Idea.Kind.DIRECT, "Tighten the pauses")
                        .target(c.id).say("Tighten the pauses in " + n)
                        .hint("Find the long quiet gaps and cut them")
                        .undo("Tighten the pauses")
                        .step(Idea.Step.of("detect_silence", "clipId", c.id, "sensitivity", 0.5))
                        .step(Idea.Step.of("remove_silence", "clipId", c.id))
                        .priority(48).build());
            }

            // Rung 5: long talks.
            if (c.speechMs >= 180_000) {
                out.add(Idea.of("chapters", Idea.Kind.DIRECT, "Make chapters")
                        .target(c.id).say("Make chapters for " + n)
                        .opener(n + " runs " + minutes(c.speechMs) + ". Want chapter markers?")
                        .hint("Find where the topics change, from the pauses and sentences")
                        .step(Idea.Step.of("auto_chapters", "clipId", c.id))
                        .priority(55).build());
                out.add(Idea.of("best60", Idea.Kind.ASK, "Find the best 60 seconds")
                        .target(c.id)
                        .say("Find the best 60 seconds of " + n + " for a short social clip")
                        .hint("I read the transcript and propose a 60-second cut for you to approve")
                        .needsKey().priority(54).build());
            }
        }
    }

    private static void title(ProjectFacts f, Set<String> flows, List<Idea> out) {
        if (f.titleAtStart || !flows.contains(FLOW_SLIDE) || f.spine.isEmpty()) return;
        String name = f.projectName.isEmpty() ? "Your video" : q(f.projectName);
        out.add(Idea.of("title", Idea.Kind.FLOW, "Make a title card")
                .flow(FLOW_SLIDE).say("Help me make a title card")
                .opener(name + " starts without a title. Want me to make one?")
                .hint("Answer three quick questions and I'll build an animated title")
                .priority(65).build());
    }

    private static void music(ProjectFacts f, List<Idea> out) {
        if (!f.audioOnly() || f.visualizers > 0) return;
        ProjectFacts.ClipFacts song = f.audio.get(0);
        out.add(Idea.of("visualizer", Idea.Kind.DIRECT, "Make a visualizer")
                .target(song.id).say("Add a visualizer for " + q(song.name))
                .opener(q(song.name) + " has nothing moving on screen yet. Want a visualizer?")
                .hint("Add bars that dance to the sound, over the whole track")
                .undo("Add a visualizer")
                .step(Idea.Step.of("add_visualizer", "audioClipId", song.id))
                .priority(70).build());
        out.add(Idea.of("lyrics", Idea.Kind.ASK, "Lyric captions")
                .target(song.id)
                .say("I'll paste the lyrics for " + q(song.name) + " — line them up with the song as captions")
                .hint("Paste the words and I'll time them to the music")
                .needsKey().priority(60).build());
    }

    private static void transitions(ProjectFacts f, List<Idea> out) {
        int n = f.spine.size();
        if (n < 3 || f.transitions > 0 || f.videoClipCount() < 2) return;
        Idea.Builder b = Idea.of("transitions", Idea.Kind.DIRECT, "Add gentle transitions")
                .say("Add gentle transitions between my clips")
                .opener("Your " + n + " clips cut straight from one to the next. Want soft crossfades?")
                .hint("Put a short crossfade on every cut between clips")
                .undo("Add " + (n - 1) + " crossfades")
                .priority(45);
        for (int seam = 0; seam < n - 1; seam++) {
            b.step(Idea.Step.of("add_transition", "type", "CROSS_DISSOLVE",
                    "durationMs", 400, "clipIndex", seam));
        }
        out.add(b.build());
    }

    private static void broll(ProjectFacts f, List<Idea> out) {
        if (f.brollAssets == 0 || f.overlayClips > 0) return;
        ProjectFacts.ClipFacts talk = null;
        for (ProjectFacts.ClipFacts c : f.spine) if (c.hasTranscript && !c.still) { talk = c; break; }
        if (talk == null) return;
        out.add(Idea.of("broll", Idea.Kind.DIRECT, "Suggest b-roll spots")
                .target(talk.id).say("Suggest b-roll for " + q(talk.name))
                .opener("You have " + f.brollAssets + " b-roll file" + (f.brollAssets == 1 ? "" : "s")
                        + " waiting and none placed yet.")
                .hint("I read what you say and pick matching b-roll; you approve each one")
                .step(Idea.Step.of("suggest_broll_placements", "clipId", talk.id))
                .needsKey().priority(40).build());
    }

    private static void sheets(ProjectFacts f, Room room, List<Idea> out) {
        int boost = room == Room.SPRITELAB ? 60 : 0;
        for (ProjectFacts.SheetFacts s : f.sheets) {
            if (s.sequence || s.cells < 2) continue;
            String n = q(s.name);
            if (s.namedCells == 0) {
                out.add(Idea.of("name_cells", Idea.Kind.ASK, "Name the cells")
                        .target(s.id).say("Look at my sprite sheet " + n + " and name its cells")
                        .opener(n + " has " + s.cells + " cells and none of them have names yet.")
                        .hint("I look at each cell and name it (idle, walk 1, jump…) so you can ask for animations by name")
                        .needsKey().priority(35 + boost).build());
            }
            if (s.animations == 0) {
                out.add(Idea.of("walk_cycle", Idea.Kind.ASK, "Make an animation")
                        .target(s.id).say("Make an animation from my sprite sheet " + n)
                        .hint("I pick the frames that belong together and loop them")
                        .needsKey().priority(33 + boost).build());
            }
        }
    }

    // ── words ───────────────────────────────────────────────────────────────

    /** A name in curly quotes, the way Joybot always refers to your things. */
    @NonNull
    static String q(@NonNull String name) {
        return "“" + name + "”";
    }

    /** "12 s" or "1 min 5 s": rounded for speech, never padded. */
    @NonNull
    static String seconds(long ms) {
        long s = Math.round(ms / 1000.0);
        if (s < 60) return s + " s";
        return (s / 60) + " min " + (s % 60) + " s";
    }

    /** "4 minutes", or "about 1 hour 5 minutes". Whole minutes, rounded down. */
    @NonNull
    static String minutes(long ms) {
        long m = ms / 60_000;
        if (m < 60) return m + (m == 1 ? " minute" : " minutes");
        return String.format(Locale.US, "%d h %d min", m / 60, m % 60);
    }
}
