import com.fadcam.ui.faditor.ai.joybot.Idea;
import com.fadcam.ui.faditor.ai.joybot.IdeaEngine;
import com.fadcam.ui.faditor.ai.joybot.Outcome;
import com.fadcam.ui.faditor.ai.joybot.Fillers;
import com.fadcam.ui.faditor.ai.joybot.ProjectFacts;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joybot's suggestion tree (plan B3). What it must get right:
 * the most useful idea first, never more than three, "Something else?" always last, quiet ideas
 * stay quiet, offline shows only what works offline, and every number in his sentence is one the
 * project actually holds.
 *
 * Run: bash tools/jvm-harness/run-ideas.sh
 */
public class IdeaEngineTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + what); }
        else { fail++; System.out.println("FAIL  " + what + "   " + detail); }
    }

    static void eq(String what, Object want, Object got) {
        check(what, String.valueOf(want).equals(String.valueOf(got)), "want=" + want + " got=" + got);
    }

    /** A natural question must find the topic with this id. */
    static void helpIs(String question, String wantId) {
        com.fadcam.ui.faditor.ai.joybot.HelpBook.Topic t =
                com.fadcam.ui.faditor.ai.joybot.HelpBook.find(question);
        eq("help: " + question, wantId, t == null ? null : t.id);
    }

    static final Set<String> FLOWS = new HashSet<>(Arrays.asList(IdeaEngine.FLOW_SLIDE));
    static final Set<String> NONE = new HashSet<>();

    static ProjectFacts.ClipFacts video(String id, String name, long ms) {
        ProjectFacts.ClipFacts c = new ProjectFacts.ClipFacts();
        c.id = id; c.name = name; c.durationMs = ms;
        return c;
    }

    static ProjectFacts.ClipFacts transcribed(String id, String name, long ms, int words) {
        ProjectFacts.ClipFacts c = video(id, name, ms);
        c.hasTranscript = true; c.words = words; c.speechMs = ms; c.captionsOn = true;
        c.synthesized = true;
        return c;
    }

    static ProjectFacts facts(ProjectFacts.ClipFacts... spine) {
        ProjectFacts f = new ProjectFacts();
        f.projectName = "Interview";
        f.titleAtStart = true;
        for (ProjectFacts.ClipFacts c : spine) f.spine.add(c);
        return f;
    }

    static String labels(IdeaEngine.Offer o) {
        StringBuilder sb = new StringBuilder();
        for (Idea i : o.ideas) { if (sb.length() > 0) sb.append(" | "); sb.append(i.label); }
        return sb.toString();
    }

    static String last(IdeaEngine.Offer o) { return o.ideas.get(o.ideas.size() - 1).label; }

    public static void main(String[] args) {
        // ── empty project ────────────────────────────────────────────────
        {
            ProjectFacts f = new ProjectFacts();
            f.projectName = "Untitled 3";
            IdeaEngine.Offer o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("empty: opener", "“Untitled 3” is empty. Want a hand getting started?", o.opener);
            eq("empty: buttons", "Make an intro slide | Record something | Something else?", labels(o));
            check("empty: hasIdea", o.hasIdea(), "");
            IdeaEngine.Offer o2 = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, NONE, NONE);
            eq("empty, slide flow not built: no slide button", "Record something | Something else?", labels(o2));
        }

        // ── one clip, sound, no words ────────────────────────────────────
        {
            ProjectFacts f = facts(video("c1", "Take 2", 90_000));
            IdeaEngine.Offer o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("untranscribed: first button", "Transcribe this", o.ideas.get(0).label);
            eq("untranscribed: second button", "Add captions", o.ideas.get(1).label);
            eq("untranscribed: opener", "“Take 2” has sound but no words yet. Want me to transcribe it?", o.opener);
            Idea t = o.ideas.get(0);
            eq("transcribe is direct", Idea.Kind.DIRECT, t.kind);
            eq("transcribe step", "generate_transcript{clipId=c1, engine=whisper}", t.steps.get(0));
            eq("captions = transcribe then switch on", 2, o.ideas.get(1).steps.size());
            eq("last is Something else", "Something else?", last(o));
        }

        // ── muted, still and short clips are not speech ──────────────────
        {
            ProjectFacts.ClipFacts muted = video("m", "Muted", 60_000); muted.muted = true;
            ProjectFacts.ClipFacts still = video("s", "Photo", 5_000); still.still = true;
            ProjectFacts.ClipFacts tiny = video("t", "Blip", 1_500);
            IdeaEngine.Offer o = IdeaEngine.offer(facts(muted, still, tiny), IdeaEngine.Room.STUDIO, FLOWS, NONE);
            check("no transcribe for muted/still/short", !labels(o).contains("Transcribe"), labels(o));
        }

        // ── several clips ────────────────────────────────────────────────
        {
            IdeaEngine.Offer o = IdeaEngine.offer(facts(video("a", "A", 10_000), video("b", "B", 10_000),
                    video("c", "C", 10_000)), IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("three untranscribed: label", "Transcribe all 3", o.ideas.get(0).label);
            eq("three untranscribed: three steps", 3, o.ideas.get(0).steps.size());
            eq("three untranscribed: opener", "3 of your clips have sound but no words yet. Want me to transcribe them?", o.opener);
            eq("three ideas + something else", 4, o.ideas.size());
        }

        // ── the cap: a project with far more than three things to suggest ──
        {
            ProjectFacts.ClipFacts c = transcribed("c1", "Talk", 600_000, 1500);
            c.fillersLeft = 3; c.captionsOn = false; c.silencesLeft = 2; c.silenceLeftMs = 3000;
            ProjectFacts f = facts(c, video("c2", "B", 10_000), video("c3", "C", 10_000));
            f.titleAtStart = false; f.online = true; f.brollAssets = 2;
            IdeaEngine.Offer o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("busy project: capped at three + something else", 4, o.ideas.size());
            eq("busy project: best first", "Transcribe all 2", o.ideas.get(0).label);
            eq("busy project: something else last", "Something else?", last(o));
        }

        // ── ums ──────────────────────────────────────────────────────────
        {
            ProjectFacts.ClipFacts c = transcribed("c1", "Talk", 120_000, 300);
            c.fillersLeft = 12; c.fillersMarked = 12;
            IdeaEngine.Offer o = IdeaEngine.offer(facts(c), IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("fillers: first", "Cut the ums", o.ideas.get(0).label);
            eq("fillers: opener quotes the count",
                    "“Talk” still has 12 filler words (um, uh) in it. Want me to cut them?", o.opener);
            eq("fillers: undo label", "Cut 12 filler words", o.ideas.get(0).undoLabel);
            c.fillersLeft = 1;
            o = IdeaEngine.offer(facts(c), IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("fillers: singular", "“Talk” still has 1 filler word (um, uh) in it. Want me to cut it?", o.opener);
            c.fillersLeft = 0; c.synthesized = false;
            o = IdeaEngine.offer(facts(c), IdeaEngine.Room.STUDIO, FLOWS, NONE);
            check("no visible ums, not synthesized: offer find", labels(o).contains("Find the ums"), labels(o));
            c.synthesized = true;
            o = IdeaEngine.offer(facts(c), IdeaEngine.Room.STUDIO, FLOWS, NONE);
            check("synthesized and clean: no um button", !labels(o).contains("ums"), labels(o));
        }

        // ── captions off ─────────────────────────────────────────────────
        {
            ProjectFacts.ClipFacts c = transcribed("c1", "Talk", 30_000, 80); c.captionsOn = false;
            IdeaEngine.Offer o = IdeaEngine.offer(facts(c), IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("captions off: first", "Turn on captions", o.ideas.get(0).label);
            eq("captions off: step", "toggle_captions{clipId=c1, enabled=true}", o.ideas.get(0).steps.get(0));
        }

        // ── silences ─────────────────────────────────────────────────────
        {
            ProjectFacts.ClipFacts c = transcribed("c1", "Talk", 30_000, 80);
            c.silencesLeft = 4; c.silenceLeftMs = 9_400;
            IdeaEngine.Offer o = IdeaEngine.offer(facts(c), IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("silences: opener", "I found 4 quiet stretches in “Talk” (9 s in all) that are still in.", o.opener);
            c.silencesLeft = 0; c.speechMs = 90_000;
            o = IdeaEngine.offer(facts(c), IdeaEngine.Room.STUDIO, FLOWS, NONE);
            check("no silences found yet, long talk: tighten offered", labels(o).contains("Tighten the pauses"), labels(o));
            int ti = indexOf(o, "Tighten the pauses");
            eq("tighten = detect then remove", 2, ti < 0 ? -1 : o.ideas.get(ti).steps.size());
        }

        // ── long talk: chapters; best-60 only online ─────────────────────
        {
            ProjectFacts.ClipFacts c = transcribed("c1", "Lecture", 600_000, 1500);
            ProjectFacts f = facts(c);
            IdeaEngine.Offer off = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            check("offline: chapters offered", labels(off).contains("Make chapters"), labels(off));
            check("offline: best 60 hidden (needs a key)", !labels(off).contains("best 60"), labels(off));
            eq("chapters opener", "“Lecture” runs 10 minutes. Want chapter markers?", off.opener);
            f.online = true;
            IdeaEngine.Offer on = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            check("online: best 60 offered", labels(on).contains("Find the best 60 seconds"), labels(on));
        }

        // ── quiet ────────────────────────────────────────────────────────
        {
            ProjectFacts f = facts(video("c1", "Take 2", 90_000));
            Set<String> quiet = new HashSet<>(Arrays.asList("transcribe:c1"));
            IdeaEngine.Offer o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, quiet);
            check("quiet id hides that idea", !labels(o).contains("Transcribe this"), labels(o));
            eq("next idea moves up and the opener follows it", null, o.opener);
            Set<String> quietKey = new HashSet<>(Arrays.asList("captions_new"));
            o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, quietKey);
            check("quiet key hides every target", !labels(o).contains("Add captions"), labels(o));
        }

        // ── title ────────────────────────────────────────────────────────
        {
            ProjectFacts.ClipFacts c = transcribed("c1", "Talk", 30_000, 80);
            ProjectFacts f = facts(c); f.titleAtStart = false;
            IdeaEngine.Offer o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("no title: offered first", "Make a title card", o.ideas.get(0).label);
            eq("no title: opener", "“Interview” starts without a title. Want me to make one?", o.opener);
            eq("title is the slide flow", "slide", o.ideas.get(0).flow);
            f.titleAtStart = true;
            o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            check("has title: not offered", !labels(o).contains("title"), labels(o));
        }

        // ── music project ────────────────────────────────────────────────
        {
            ProjectFacts f = new ProjectFacts();
            ProjectFacts.ClipFacts blank = video("b", "Blank (auto)", 200_000); blank.still = true;
            f.spine.add(blank); f.titleAtStart = true;
            ProjectFacts.ClipFacts song = video("s1", "My Song", 200_000); song.audioTrack = true;
            f.audio.add(song);
            IdeaEngine.Offer o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("audio only: visualizer first", "Make a visualizer", o.ideas.get(0).label);
            eq("visualizer step", "add_visualizer{audioClipId=s1}", o.ideas.get(0).steps.get(0));
            check("lyrics need a key", !labels(o).contains("Lyric"), labels(o));
            f.visualizers = 1;
            o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            check("has a visualizer: not offered again", !labels(o).contains("visualizer"), labels(o));
        }

        // ── transitions ──────────────────────────────────────────────────
        {
            IdeaEngine.Offer o = IdeaEngine.offer(facts(
                    transcribed("a", "A", 10_000, 20), transcribed("b", "B", 10_000, 20),
                    transcribed("c", "C", 10_000, 20), transcribed("d", "D", 10_000, 20)),
                    IdeaEngine.Room.STUDIO, FLOWS, NONE);
            int i = indexOf(o, "Add gentle transitions");
            check("4 clips, no transitions: offered", i >= 0, labels(o));
            if (i >= 0) {
                Idea t = o.ideas.get(i);
                eq("one crossfade per seam", 3, t.steps.size());
                eq("first seam", "add_transition{type=CROSS_DISSOLVE, durationMs=400, clipIndex=0}", t.steps.get(0));
                eq("last seam", "add_transition{type=CROSS_DISSOLVE, durationMs=400, clipIndex=2}", t.steps.get(2));
                eq("undo label", "Add 3 crossfades", t.undoLabel);
            }
        }

        // ── sprite sheets: SpriteLab puts them first ─────────────────────
        {
            ProjectFacts.ClipFacts c = transcribed("c1", "Talk", 30_000, 80); c.captionsOn = false;
            ProjectFacts f = facts(c); f.online = true;
            ProjectFacts.SheetFacts s = new ProjectFacts.SheetFacts();
            s.id = "sh1"; s.name = "Dino"; s.cells = 16;
            f.sheets.add(s);
            IdeaEngine.Offer studio = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            eq("studio: captions first", "Turn on captions", studio.ideas.get(0).label);
            IdeaEngine.Offer lab = IdeaEngine.offer(f, IdeaEngine.Room.SPRITELAB, FLOWS, NONE);
            eq("spritelab: name cells first", "Name the cells", lab.ideas.get(0).label);
            eq("spritelab opener", "“Dino” has 16 cells and none of them have names yet.", lab.opener);
            f.online = false;
            lab = IdeaEngine.offer(f, IdeaEngine.Room.SPRITELAB, FLOWS, NONE);
            check("offline: sheet ideas need a key, hidden", !labels(lab).contains("Name the cells"), labels(lab));
            // VisLab: ideas about the look on screen, never the talking-video tree; no green pulse opener.
            f.online = true;
            IdeaEngine.Offer vis = IdeaEngine.offer(f, IdeaEngine.Room.VISLAB, FLOWS, NONE);
            eq("vislab: kick idea first", "Hit harder on the kick", vis.ideas.get(0).label);
            check("vislab: no Studio ideas", !labels(vis).contains("Turn on captions"), labels(vis));
            check("vislab: no opener", vis.opener == null, String.valueOf(vis.opener));
            // JoyBrush (BOT-1, 10-28): the open drawing's ideas, never the lobby's "Start a project".
            IdeaEngine.Offer jb = IdeaEngine.offer(f, IdeaEngine.Room.JOYBRUSH, FLOWS, NONE);
            eq("joybrush: colour layer first", "A layer to colour on", jb.ideas.get(0).label);
            check("joybrush: no Studio ideas", !labels(jb).contains("Turn on captions"), labels(jb));
            check("joybrush: no lobby ideas", !labels(jb).contains("Start a project"), labels(jb));
            check("joybrush: no opener", jb.opener == null, String.valueOf(jb.opener));
        }

        // ── JoyBrush ideas that read the open drawing (BOT-A9, 11-13) ──
        {
            // Written exactly as JoybotHands.describe() writes it (pinned against the .kt below).
            String one = "Drawing “Cat”, 2048×1536 px.\nLayers, top to bottom (number 1 = bottom):\n"
                    + "★ 1. Sketch — shown, 100%\n★ = the layer being painted on.\n"
                    + "No boards (animation frames need an animation board).\n"
                    + "In hand: Pencil, size 6 px, 100% opacity, colour #000000.";
            com.fadcam.ui.faditor.ai.joybot.DrawingFacts d = com.fadcam.ui.faditor.ai.joybot.DrawingFacts.parse(one);
            check("drawing: parsed", d != null, "");
            eq("drawing: one layer", "[Sketch]", d == null ? null : d.layers.toString());
            check("drawing: no animation", d != null && !d.animation && d.boards == 0, "");
            ProjectFacts f = new ProjectFacts();
            f.online = true;
            f.drawing = d;
            IdeaEngine.Offer o = IdeaEngine.offer(f, IdeaEngine.Room.JOYBRUSH, FLOWS, NONE);
            eq("drawing: one layer, colour layer first", "A layer to colour on", o.ideas.get(0).label);
            check("drawing: the colour layer goes above the named sketch",
                    o.ideas.get(0).say != null && o.ideas.get(0).say.contains("“Sketch”"), String.valueOf(o.ideas.get(0).say));
            check("drawing: no animation yet offers one", labels(o).contains("Start an animation"), labels(o));
            check("drawing: no frames idea without frames", !labels(o).contains("Next frame from this one"), labels(o));

            String anim = "Drawing “Walk”, 1080×1080 px.\nLayers, top to bottom (number 1 = bottom):\n"
                    + "• 3. Colour — shown, 100%\n★ 2. Ink — shown, 100%\n• 1. Rough — hidden, 40%, locked\n"
                    + "★ = the layer being painted on.\n"
                    + "Board “Walk cycle” (animation): 8 frames, on frame 3, 12 fps ★\n"
                    + "In hand: Ink pen, size 4 px, 100% opacity, colour #222222.";
            d = com.fadcam.ui.faditor.ai.joybot.DrawingFacts.parse(anim);
            eq("drawing: layers bottom first", "[Rough, Ink, Colour]", d == null ? null : d.layers.toString());
            check("drawing: one hidden, an 8-frame animation on frame 3", d != null && d.hiddenLayers == 1
                    && d.animation && d.frames == 8 && d.onFrame == 3, "");
            f.drawing = d;
            o = IdeaEngine.offer(f, IdeaEngine.Room.JOYBRUSH, FLOWS, NONE);
            check("drawing: a colour layer exists, so no colour idea", !labels(o).contains("A layer to colour on"), labels(o));
            check("drawing: the hidden layer can come back", labels(o).contains("Show the hidden layer"), labels(o));
            check("drawing: an animation exists, so no Start an animation", !labels(o).contains("Start an animation"), labels(o));
            check("drawing: frames idea names the frame", labels(o).contains("Next frame from this one"), labels(o));

            String single = anim.replace("8 frames, on frame 3", "1 frame, on frame 1");
            f.drawing = com.fadcam.ui.faditor.ai.joybot.DrawingFacts.parse(single);
            o = IdeaEngine.offer(f, IdeaEngine.Room.JOYBRUSH, FLOWS, NONE);
            check("drawing: a one-frame animation gets more frames", labels(o).contains("Add 7 more frames"), labels(o));
            check("drawing: an error text is no drawing",
                    com.fadcam.ui.faditor.ai.joybot.DrawingFacts.parse("JoyBrush isn't open.") == null, "");
            // The format the parser reads, as JoybotHands.describe() builds it.
            try {
                String hands = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        "joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoybotHands.kt")), "UTF-8");
                for (String piece : new String[]{"sb.append(\"Drawing “\")", ".append(\". \").append(l.name)",
                        ".append(\" — \").append(if (l.visible) \"shown\" else \"hidden\")",
                        "sb.append(\"Board “\").append(b.name).append(\"” (\")",
                        ".append(\" frame\").append(if (b.frames.size == 1) \"\" else \"s\")",
                        ".append(\", on frame \").append(at)"}) {
                    check("drawing: JoybotHands still writes " + piece, hands.contains(piece), piece);
                }
            } catch (java.io.IOException ex) {
                check("drawing: JoybotHands readable", false, ex.toString());
            }
        }

        // ── never invents a number ───────────────────────────────────────
        // Every number in every opener across all of the scenarios above must be a number held in
        // the facts (or one rounding of a held duration). Run across a mixed project.
        {
            ProjectFacts.ClipFacts c = transcribed("c1", "Talk 7", 245_000, 600);
            c.fillersLeft = 11; c.silencesLeft = 3; c.silenceLeftMs = 4_200; c.captionsOn = false;
            ProjectFacts f = facts(c, video("c2", "Clip 9", 20_000));
            f.online = true; f.brollAssets = 5;
            // fillers 11, silences 3, 4.2 s -> "4", 245 s -> "4 minutes", b-roll 5. Nothing else.
            Set<String> held = new HashSet<>(Arrays.asList("11", "3", "4", "5"));
            // Every quiet combination, so every opener gets its turn as the first button.
            String[] keys = {"transcribe", "captions_new", "cut_ums", "captions_on", "silences", "chapters",
                    "best60", "broll", "tighten", "find_ums"};
            Set<String> quiet = new HashSet<>();
            boolean allHeld = true; String bad = "";
            for (String k : keys) {
                IdeaEngine.Offer o = IdeaEngine.offer(f, IdeaEngine.Room.STUDIO, FLOWS, quiet);
                for (Idea i : o.ideas) {
                    if (i.opener == null) continue;
                    Matcher m = Pattern.compile("\\d+").matcher(i.opener.replace("Talk 7", "").replace("Clip 9", ""));
                    while (m.find()) if (!held.contains(m.group())) { allHeld = false; bad = i.opener; }
                }
                quiet.add(k);
            }
            check("every opener number comes from the facts", allHeld, bad);
        }

        // ── Outcome: what changed, measured ──────────────────────────────
        {
            ProjectFacts b = facts(video("c1", "Take 2", 90_000)); b.totalMs = 90_000;
            ProjectFacts a = facts(transcribed("c1", "Take 2", 90_000, 312)); a.totalMs = 90_000;
            a.spine.get(0).captionsOn = false;
            eq("outcome: transcribed", "Transcribed “Take 2”: 312 words.", Outcome.describe(b, a));
            ProjectFacts a2 = facts(transcribed("c1", "Take 2", 90_000, 312)); a2.totalMs = 90_000;
            eq("outcome: transcribed + captions", "Transcribed “Take 2”: 312 words. Captions are on.", Outcome.describe(b, a2));

            ProjectFacts.ClipFacts c = transcribed("c1", "Talk", 120_000, 300); c.fillersLeft = 12;
            ProjectFacts fb = facts(c); fb.totalMs = 125_000;
            ProjectFacts.ClipFacts c2 = transcribed("c1", "Talk", 120_000, 300); c2.fillersLeft = 0;
            ProjectFacts fa = facts(c2); fa.totalMs = 119_000;
            eq("outcome: ums cut, shorter", "Cut 12 filler words. The video is 6 s shorter (2:05 → 1:59).", Outcome.describe(fb, fa));
            eq("outcome: nothing changed", null, Outcome.describe(fb, fb));

            ProjectFacts tb = facts(transcribed("a", "A", 1, 1)); ProjectFacts ta = facts(transcribed("a", "A", 1, 1));
            ta.transitions = 3;
            eq("outcome: transitions", "Added 3 transitions.", Outcome.describe(tb, ta));
            ProjectFacts sb = facts(transcribed("a", "A", 1, 1)); sb.totalMs = 196_000;
            ProjectFacts.ClipFacts slide = video("s", "Slide", 3500); slide.slide = true;
            ProjectFacts sa = facts(slide, transcribed("a", "A", 1, 1)); sa.totalMs = 200_000;
            ProjectFacts mb = facts(video("a", "Take 1", 1000), video("b", "Take 2", 1000));
            ProjectFacts ma = facts(transcribed("a", "Take 1", 1000, 312), transcribed("b", "Take 2", 1000, 85));
            for (ProjectFacts.ClipFacts mc : ma.spine) mc.captionsOn = false;
            eq("outcome: each transcribed clip named", "Transcribed 2 clips: “Take 1” 312 words, “Take 2” 85 words.", Outcome.describe(mb, ma));
            eq("outcome: a slide, not '1 clip'", "Added a slide. The video is 4 s longer (3:16 → 3:20).", Outcome.describe(sb, sa));
            eq("outcome: thousands", "Transcribed “X”: 1,204 words.", Outcome.describe(
                    facts(video("x", "X", 1)), facts(transcribed("x", "X", 1, 1204))).replace(" Captions are on.", ""));
        }

        // ── SlideFlow: the four questions ────────────────────────────────
        {
            com.fadcam.ui.faditor.ai.joybot.SlideFlow s =
                    new com.fadcam.ui.faditor.ai.joybot.SlideFlow("Beach Day", 4, 2);
            eq("slide: first question", "What's the slide for?", s.question().text);
            eq("slide: five kinds", 5, s.question().choices.size());
            check("slide: nonsense kind refused", !s.answer("banana"), "");
            check("slide: chapter accepted", s.answer("chapter"), "");
            eq("slide: asks the words", "What's this chapter called?", s.question().text);
            check("slide: words typed", s.answer("Part two: the waves"), "");
            eq("slide: asks the mood", "What mood?", s.question().text);
            check("slide: typed mood synonym", s.answer("something dramatic"), "");
            check("slide: asks where", s.question().text.startsWith("Where should it go?"), s.question().text);
            eq("slide: four places when the playhead is mid-project", 4, s.question().choices.size());
            check("slide: here accepted", s.answer("here"), "");
            eq("slide: done", com.fadcam.ui.faditor.ai.joybot.SlideFlow.Stage.DONE, s.stage());
            java.util.Map<String, Object> a = s.toolArgs();
            eq("slide: text", "Part two: the waves", a.get("title_or_text"));
            eq("slide: placement before the playhead clip", "{insertAtClipIndex=2}", a.get("placement"));
            eq("slide: chapter length", 2500L, a.get("duration_ms_hint"));
            check("slide: cinematic style", String.valueOf(a.get("style_hint")).contains("cinematic"), String.valueOf(a.get("style_hint")));
            eq("slide: summary", "Make a cinematic chapter slide before the clip under the playhead.", s.summary());

            com.fadcam.ui.faditor.ai.joybot.SlideFlow t =
                    new com.fadcam.ui.faditor.ai.joybot.SlideFlow("Beach Day", 4, -1).startingWith("title");
            eq("title: offers the project name", "Use “Beach Day”", t.question().choices.get(0).label);
            eq("title: prefill", "Beach Day", t.question().prefill);
            t.answer("use_name");
            t.answer("bold");
            eq("title: asks in line or on top", com.fadcam.ui.faditor.ai.joybot.SlideFlow.Stage.PLACE, t.stage());
            check("title: on-top choice offered", t.question().choices.get(t.question().choices.size() - 1).id.equals("over"), "");
            t.answer("start");
            eq("title: in line at index 0", "{insertAtClipIndex=0}", t.toolArgs().get("placement"));
            eq("title: in line is fullscreen", "fullscreen", t.toolArgs().get("mode"));
            eq("title: text is the name", "Beach Day", t.toolArgs().get("title_or_text"));

            com.fadcam.ui.faditor.ai.joybot.SlideFlow o =
                    new com.fadcam.ui.faditor.ai.joybot.SlideFlow("Beach Day", 4, 2, 12_500).startingWith("title");
            o.answer("Hello"); o.answer("clean"); o.answer("over");
            eq("on top: overlay mode", "overlay", o.toolArgs().get("mode"));
            eq("on top: at the playhead", "{startMs=12500}", o.toolArgs().get("placement"));
            eq("on top: summary", "Make a clean title slide on top of the video.", o.summary());
            String ov = com.fadcam.ui.faditor.slides.SlideDesigns.build(1280, 720, "title", "cinematic", "Hi", 3000, true);
            check("overlay design: see-through", ov.contains("background:transparent"), "");
            check("overlay design: no letterbox", ov.contains("MOOD.bars = false;"), "");
            check("overlay design: still valid", com.fadcam.ui.faditor.slides.SlideContract.validate(ov) == null, "");

            com.fadcam.ui.faditor.ai.joybot.SlideFlow e =
                    new com.fadcam.ui.faditor.ai.joybot.SlideFlow("", 4, -1).startingWith("credits");
            e.answer("Me\nMy dog"); e.answer("calm");
            eq("no playhead: start, end, on top", 3, e.question().choices.size());
            check("no playhead: 'here' refused", !e.answer("here"), "");
            e.answer("end");
            eq("credits at the end", "{insertAtClipIndex=4}", e.toolArgs().get("placement"));
        }

        // ── SlideDesigns: every built-in design obeys the slide contract ─
        {
            int ok = 0;
            String bad = "";
            for (String k : com.fadcam.ui.faditor.slides.SlideDesigns.KINDS) {
                for (String m : com.fadcam.ui.faditor.slides.SlideDesigns.MOODS) {
                    String html = com.fadcam.ui.faditor.slides.SlideDesigns.build(1080, 1920, k, m, "Line one\nLine two", 4000);
                    String v = com.fadcam.ui.faditor.slides.SlideContract.validate(html);
                    if (v == null) ok++; else bad = k + "/" + m + ": " + v;
                }
            }
            eq("designs: all 25 pass the contract", 25, ok);
            if (!bad.isEmpty()) check("designs: first failure", false, bad);
            String evil = com.fadcam.ui.faditor.slides.SlideDesigns.build(1280, 720, "title", "bold",
                    "</script><script>alert(1)</script>\"quote\" \\ back", 3000);
            check("designs: words cannot close the script", !evil.contains("</script><script>alert"), "");
            check("designs: unknown kind falls back, still valid", com.fadcam.ui.faditor.slides.SlideContract.validate(
                    com.fadcam.ui.faditor.slides.SlideDesigns.build(100, 100, "nope", "nope", "x", 100)) == null, "");
            check("designs: knows", com.fadcam.ui.faditor.slides.SlideDesigns.knows("Quote", "CALM"), "");
        }

        // ── HelpBook ─────────────────────────────────────────────────────
        {
            com.fadcam.ui.faditor.ai.joybot.HelpBook.Topic t;
            t = com.fadcam.ui.faditor.ai.joybot.HelpBook.find("How do I add captions?");
            eq("help: captions", "captions", t == null ? null : t.id);
            t = com.fadcam.ui.faditor.ai.joybot.HelpBook.find("where are my recordings");
            eq("help: recordings -> library", "library", t == null ? null : t.id);
            t = com.fadcam.ui.faditor.ai.joybot.HelpBook.find("how do i cut the ums");
            eq("help: ums", "ums", t == null ? null : t.id);
            t = com.fadcam.ui.faditor.ai.joybot.HelpBook.find("how do I add drums to my video");
            check("help: drums is not ums", t == null || !"ums".equals(t.id), t == null ? "" : t.id);
            t = com.fadcam.ui.faditor.ai.joybot.HelpBook.find("how do I make a title card");
            eq("help: slides", "slides", t == null ? null : t.id);
            eq("help: slides can be done", "slide", t == null ? null : t.doIt);
            t = com.fadcam.ui.faditor.ai.joybot.HelpBook.find("how do I paint");
            eq("help: joybrush route", "joybrush", t == null ? null : t.route);
            eq("help: unknown", null, com.fadcam.ui.faditor.ai.joybot.HelpBook.find("quantum flux capacitor"));
            // BOT-2 (11-04): JoyBrush, Sprite Lab and Library help, each reached by a natural question.
            String[][] bot2Help = {
                    {"How do I open a brush's settings? hold a brush", "joybrush_brushes"},
                    {"how do I choose editable lines per brush", "joybrush_lines"},
                    {"how do I select lines and nudge them", "joybrush_select"},
                    {"how do I change the paper surface", "joybrush_paper"},
                    {"how do I make a board", "joybrush_boards"},
                    {"how does the onion peg work", "joybrush_onion"},
                    {"how do I use play all on my clips", "joybrush_clips"},
                    {"how do I preview a repeating pattern", "joybrush_tile"},
                    {"how do I roll the cells", "joybrush_sprite_roll"},
                    {"how do I send to studio", "joybrush_export"},
                    {"how do I turn on symmetry", "joybrush_symmetry"},
                    {"how do I plant the feet on a frame", "sprite_align"},
                    {"how do I rename a sheet", "sprite_sheets"},
                    {"how do I reorder frames", "sprite_reorder"},
                    {"how do I filter my library", "library_find"},
                    {"how do I rename a project", "library_projects"},
                    {"how do I import files", "library_import"},
                    {"how do I open a look from fonts and supplies", "library_look"},
                    {"how do I find a setting", "settings_find"},
                    {"how do I put the location in my video", "settings_location"},
                    {"where is the tips row", "settings_tips"},
                    {"how do I watch my phone camera from my computer", "remote_lan"},
                    // BOT-A5 + Studio "Help: PENDING" rows (11-13)
                    {"how do I lock a layer", "joybrush_layers"},
                    {"how do I merge down", "joybrush_layers"},
                    {"how do I flatten my drawing", "joybrush_layers"},
                    {"how do I freeze a layer", "joybrush_layers"},
                    {"how do I flatten clips", "joybrush_clips"},
                    {"how do I drop a bookmark", "studio_marks"},
                    {"how do I loop a region", "studio_marks"},
                    {"how do I export just part of my video", "studio_marks"},
                    {"can I set an in point and out point", "studio_marks"},
                    {"how do I change the project title", "studio_title_rename"},
                    {"how do I rename a project", "library_projects"},
                    {"how do I arrange the tools", "studio_arrange_tools"},
                    {"can I pin a tool", "studio_arrange_tools"},
                    {"how do I cancel an export", "export_safety"},
                    {"my export is stuck", "export_safety"},
                    {"how do I export", "export"},
                    {"how do I get the drawer back", "studio_drawer_back"},
                    {"how do I cut the silence out of my song", "silence"},
                    // SHELL-04 (11-13): Remote and Smart Detection get a Take me there
                    {"where is smart detection", "smart_detection"},
                    {"how do I find people in my recordings", "smart_detection"},
                    {"where is the finder", "smart_detection"},
                    // BOT-A7 (11-13): STATE rows that had help pending
                    {"how do I save to gallery from the library", "library_item"},
                    {"can I choose several things at once", "library_item"},
                    {"how do I put my words in a look in vislab", "vislab_words_look"},
                    {"how do I switch back to the old watercolour", "joybrush_watercolour"},
                    {"my watercolour is slow", "joybrush_watercolour"},
                    {"how do I use palette knife 3", "joybrush_knife"},
                    {"how do I name a layer", "name_layers"},
                    {"how do I install a pack", "textlab_pack"},
                    {"where do the pack fonts go", "textlab_pack"},
                    {"how do I filter my library", "library_find"},
                    {"how do I rename a project", "library_projects"},
                    {"how do I change the brush settings", "joybrush_brushes"},
            };
            for (String[] c : bot2Help) {
                t = com.fadcam.ui.faditor.ai.joybot.HelpBook.find(c[0]);
                eq("help BOT-2: " + c[1], c[1], t == null ? null : t.id);
            }
            check("howto: how do I", com.fadcam.ui.faditor.ai.joybot.HelpBook.isHowTo("How do I export?"), "");
            check("howto: not a command", !com.fadcam.ui.faditor.ai.joybot.HelpBook.isHowTo("cut the ums"), "");
            check("help: every topic has an answer", com.fadcam.ui.faditor.ai.joybot.HelpBook.TOPICS.size() > 30, "");

            // G3 wing help: every new topic is reachable by a natural question, ids stay unique, and the
            // book only offers a doIt/route the app really has.
            check("help G3: 60+ topics", com.fadcam.ui.faditor.ai.joybot.HelpBook.TOPICS.size() >= 60,
                    String.valueOf(com.fadcam.ui.faditor.ai.joybot.HelpBook.TOPICS.size()));
            java.util.Set<String> helpIds = new HashSet<>();
            for (com.fadcam.ui.faditor.ai.joybot.HelpBook.Topic x : com.fadcam.ui.faditor.ai.joybot.HelpBook.TOPICS) {
                helpIds.add(x.id);
            }
            eq("help G3: no two topics share an id", com.fadcam.ui.faditor.ai.joybot.HelpBook.TOPICS.size(), helpIds.size());
            Set<String> helpRoutes = new HashSet<>(Arrays.asList(null, "record", "library", "studio", "setup", "vislab",
                    "joybrush", "spritelab", "avatar", "space", "trash", "bridge", "textlab", "remote", "finder"));
            for (com.fadcam.ui.faditor.ai.joybot.HelpBook.Topic x : com.fadcam.ui.faditor.ai.joybot.HelpBook.TOPICS) {
                check("help G3: route is a real one: " + x.id, helpRoutes.contains(x.route), String.valueOf(x.route));
                check("help G3: \"Or I can do it\" only with a doIt: " + x.id,
                        !x.answer.endsWith("Or I can do it for you.") || x.doIt != null, x.answer);
            }
            // Show me how: a topic's tool id must be a button the Studio's tool strip really has.
            // FaditorToolRegistry registers each one as add(t, "id", R.id.tool_...); read as text.
            int showMeTopics = 0;
            try {
                String reg = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        "app/src/main/java/com/fadcam/ui/faditor/tools/FaditorToolRegistry.java")), "UTF-8");
                for (com.fadcam.ui.faditor.ai.joybot.HelpBook.Topic x : com.fadcam.ui.faditor.ai.joybot.HelpBook.TOPICS) {
                    if (x.tool == null) continue;
                    showMeTopics++;
                    check("help show-me: " + x.id + " points at a registered tool: " + x.tool,
                            reg.contains("add(t, \"" + x.tool + "\", R.id."), x.tool);
                }
                check("help show-me: 20+ topics point at a tool", showMeTopics >= 20, String.valueOf(showMeTopics));
            } catch (java.io.IOException ex) {
                check("help show-me: registry readable", false, ex.toString());
            }
            helpIs("How do I make a sprite animation?", "sprite_animation");
            helpIs("How do I slice my sprite sheet?", "sprite_slice");
            helpIs("How do I name cells in my sprite sheet?", "sprite_names");
            helpIs("How do I turn on onion skin?", "sprite_onion");
            helpIs("How do I bake a clean sprite sheet?", "sprite_bake");
            helpIs("How do I export the sprite file?", "sprite_export");
            helpIs("How do I place a sprite on my video?", "sprite_place");
            helpIs("How do I open Sprite Lab?", "sprite_open");
            helpIs("How do I open my drawing?", "joybrush_open");
            helpIs("How do I record my screen?", "record_screen");
            helpIs("How do I add my webcam bubble while I record?", "record_webcam");
            helpIs("Can I record with the screen off?", "record_background");
            helpIs("How do I edit a recording in the Studio?", "library_open_studio");
            helpIs("Where did my export go?", "library_exports");
            helpIs("How do I restore a deleted video?", "trash_restore");
            helpIs("How do I use a green screen?", "chroma");
            helpIs("How do I put a mask on my video?", "mask");
            helpIs("How do I bend a picture?", "puppet");
            helpIs("How do I animate text?", "text_animation");
            helpIs("How do I change a text's font?", "text_font");
            helpIs("How do I record a voiceover?", "voiceover");
            helpIs("How do I extract audio from my clip?", "extract_audio");
            helpIs("How do I change the blend mode?", "blend");
            helpIs("How do I add an adjustment layer?", "fx_layer");
            helpIs("How do I add to the timeline after sharing?", "share_add");
        }

        // ── Fillers: the one definition the count and the cut share ──────
        {
            check("um is a filler", Fillers.isFiller("Um,"), "");
            check("UHH is a filler", Fillers.isFiller("UHH"), "");
            check("hmm... is a filler", Fillers.isFiller("hmm..."), "");
            check("like is not", !Fillers.isFiller("like"), "");
            check("umbrella is not", !Fillers.isFiller("umbrella"), "");
            java.util.List<long[]> cuts = new java.util.ArrayList<>();
            cuts.add(new long[]{1000, 2000});
            check("inside a cut", Fillers.isCut(1200, 1500, cuts), "");
            check("straddling a cut is not cut", !Fillers.isCut(1800, 2300, cuts), "");
        }

        // ── ToolRouter: a request carries only the tools it needs (Note 9 2026-10-16: tools were 83%) ──
        {
            java.util.Set<String> f;
            f = com.fadcam.ui.faditor.ai.joybot.ToolRouter.pick("how long is my video");
            check("router: vague request gets core + everyday", f.contains("core") && f.contains("clips")
                    && f.contains("text") && f.contains("audio") && f.size() == 4, f.toString());
            f = com.fadcam.ui.faditor.ai.joybot.ToolRouter.pick(
                    "rotate the first clip 90 degrees, make the visualizer pink, and make the FONT TEST text zoom in over 2 seconds");
            check("router: phone test #3 gets clips+audio+text+motion", f.contains("clips") && f.contains("audio")
                    && f.contains("text") && f.contains("motion"), f.toString());
            f = com.fadcam.ui.faditor.ai.joybot.ToolRouter.pick("put my Starguy sprite on the video at the start, small, bottom right");
            check("router: sprite request gets sprites", f.contains("sprites"), f.toString());
            f = com.fadcam.ui.faditor.ai.joybot.ToolRouter.pick("what screen recordings do I have");
            check("router: library question gets media + core", f.contains("media") && f.contains("core"), f.toString());
            f = com.fadcam.ui.faditor.ai.joybot.ToolRouter.pick("cut the ums");
            check("router: 'cut the ums' gets transcript", f.contains("transcript"), f.toString());
            check("router: 'mute' does not wake 'music'", !com.fadcam.ui.faditor.ai.joybot.ToolRouter.pick("mute it")
                    .contains("captions"), "");
            eq("router: legacy tool family", "transcript",
                    com.fadcam.ui.faditor.ai.joybot.ToolRouter.familyOf("cut_all_fillers", null));
            eq("router: typed tool family", "text",
                    com.fadcam.ui.faditor.ai.joybot.ToolRouter.familyOf("set_text_style", "text_look"));
            f = com.fadcam.ui.faditor.ai.joybot.ToolRouter.pick("make it brighter and pump the bloom on the kick", true);
            check("router: inside VisLab = core + vislab only", f.contains("vislab") && f.size() == 2, f.toString());
            f = com.fadcam.ui.faditor.ai.joybot.ToolRouter.pick("switch VisLab to Disco Ocean");
            check("router: 'VisLab' from elsewhere wakes vislab", f.contains("vislab"), f.toString());
            eq("router: vislab actions are the vislab family", "vislab",
                    com.fadcam.ui.faditor.ai.joybot.ToolRouter.familyOf("vislab_change", "vislab"));
            eq("router: unknown tool is core (always sent)", "core",
                    com.fadcam.ui.faditor.ai.joybot.ToolRouter.familyOf("brand_new_tool", null));
            check("router: more_tools definition names every family",
                    com.fadcam.ui.faditor.ai.joybot.ToolRouter.moreToolsJson().contains("\"sprites\"")
                            && com.fadcam.ui.faditor.ai.joybot.ToolRouter.moreToolsJson().contains("\"project\""), "");
            // Every legacy tool in the real tool text is placed in a family (none silently CORE by accident).
            try {
                String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        "app/src/main/java/com/fadcam/ui/faditor/ai/AIToolExecutor.java")), "UTF-8");
                int a0 = src.indexOf("public static String getToolDescriptions()");
                int s0 = src.indexOf("\"\"\"", a0) + 3;
                int e0 = src.indexOf("\"\"\"", s0);
                java.util.List<String> core = new java.util.ArrayList<>();
                java.util.Set<String> okCore = new java.util.HashSet<>(java.util.Arrays.asList(
                        "get_project_state", "rename_item", "set_item_time", "look"));
                for (com.fadcam.ui.faditor.ai.joybot.ToolDocs.Doc d
                        : com.fadcam.ui.faditor.ai.joybot.ToolDocs.parse(src.substring(s0, e0))) {
                    if ("core".equals(com.fadcam.ui.faditor.ai.joybot.ToolRouter.familyOf(d.name, null))
                            && !okCore.contains(d.name)) core.add(d.name);
                }
                check("router: every legacy tool has a family", core.isEmpty(), core.toString());
            } catch (Exception ex) {
                check("router: tool text readable", false, ex.toString());
            }
        }

        // ── AiProviders: a pasted key names its own service (owner, 2026-10-15: several subscriptions) ──
        {
            com.fadcam.ui.faditor.ai.joybot.AiProviders.Provider p;
            p = com.fadcam.ui.faditor.ai.joybot.AiProviders.fromKey("sk-proj-abc123");
            check("key: sk-proj- is OpenAI", p != null && p.name.equals("OpenAI") && p.base.equals("https://api.openai.com/v1"), "");
            p = com.fadcam.ui.faditor.ai.joybot.AiProviders.fromKey("  sk-or-v1-abc ");
            check("key: sk-or- is OpenRouter (default address)", p != null && p.isOpenRouter(), "");
            p = com.fadcam.ui.faditor.ai.joybot.AiProviders.fromKey("sk-ant-api03-x");
            check("key: sk-ant- is Anthropic", p != null && p.name.equals("Anthropic"), "");
            p = com.fadcam.ui.faditor.ai.joybot.AiProviders.fromKey("AIzaSyX");
            check("key: AIza is Google", p != null && p.name.startsWith("Google"), "");
            check("key: unknown shape says nothing", com.fadcam.ui.faditor.ai.joybot.AiProviders.fromKey("hello") == null, "");
            eq("base name: OpenAI", "OpenAI", com.fadcam.ui.faditor.ai.joybot.AiProviders.nameForBase("https://api.openai.com/v1/"));
            eq("base name: default", "OpenRouter", com.fadcam.ui.faditor.ai.joybot.AiProviders.nameForBase(""));
            eq("base name: on the phone", "On this phone", com.fadcam.ui.faditor.ai.joybot.AiProviders.nameForBase("http://localhost:11434/v1"));
            eq("base name: other host", "example.com", com.fadcam.ui.faditor.ai.joybot.AiProviders.nameForBase("https://example.com/v1"));
            check("chat model: gpt-4o-mini", com.fadcam.ui.faditor.ai.joybot.AiProviders.isChatModel("gpt-4o-mini"), "");
            check("not chat: embeddings", !com.fadcam.ui.faditor.ai.joybot.AiProviders.isChatModel("text-embedding-3-small"), "");
            check("not chat: whisper", !com.fadcam.ui.faditor.ai.joybot.AiProviders.isChatModel("whisper-1"), "");
            check("not chat: dall-e", !com.fadcam.ui.faditor.ai.joybot.AiProviders.isChatModel("dall-e-3"), "");
        }

        // ── ToolDocs: native tool definitions come from the same text as the JSON fallback (F1) ──
        {
            String docs = "Available tools:\n\n"
                    + "            1. get_project_state — Get full project info\n"
                    + "               args: {}\n"
                    + "            42g. look — SEE something in the project.\n"
                    + "                args: {\"id\":\"...\"}\n"
                    + "            42. fix_audio — One-tap Fix audio chain.\n"
                    + "                args: {\"audioClipId\":\"...\"}\n"
                    + "\n"
                    + "            EditScript ops for the above (use with apply_edit_script):\n"
                    + "              SPLIT_CLIP_AT_TIME {\"type\":\"SPLIT_CLIP_AT_TIME\"}\n"
                    + "            33c. set_sprite_grid — Set a sheet's grid.\n"
                    + "                args: {\"sheetId\":\"...\"}\n"
                    + "            33c. set_sprite_grid — a repeated name keeps the first.\n";
            java.util.List<com.fadcam.ui.faditor.ai.joybot.ToolDocs.Doc> d =
                    com.fadcam.ui.faditor.ai.joybot.ToolDocs.parse(docs);
            eq("tool docs: four tools", "4", String.valueOf(d.size()));
            eq("tool docs: names in order", "get_project_state look fix_audio set_sprite_grid",
                    d.get(0).name + " " + d.get(1).name + " " + d.get(2).name + " " + d.get(3).name);
            check("tool docs: args ride along", d.get(1).text.contains("args: {\"id\""), d.get(1).text);
            check("tool docs: the EditScript block stays out of fix_audio",
                    !d.get(2).text.contains("SPLIT_CLIP"), d.get(2).text);
            check("tool docs: repeated name keeps the first", d.get(3).text.startsWith("Set a sheet"), d.get(3).text);
            // The REAL tool text, read from the source (the harness can't load AIToolExecutor).
            try {
                String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        "app/src/main/java/com/fadcam/ui/faditor/ai/AIToolExecutor.java")), "UTF-8");
                int a = src.indexOf("public static String getToolDescriptions()");
                int s = src.indexOf("\"\"\"", a) + 3;
                int e = src.indexOf("\"\"\"", s);
                java.util.List<com.fadcam.ui.faditor.ai.joybot.ToolDocs.Doc> real =
                        com.fadcam.ui.faditor.ai.joybot.ToolDocs.parse(src.substring(s, e));
                java.util.Set<String> names = new java.util.HashSet<>();
                for (com.fadcam.ui.faditor.ai.joybot.ToolDocs.Doc x : real) names.add(x.name);
                check("real tool docs: 60+ tools", real.size() >= 60, String.valueOf(real.size()));
                for (String must : new String[]{"look", "label_sprite_cells", "set_fades", "auto_chapters",
                        "health_check", "export_project", "apply_edit_script"}) {
                    check("real tool docs: has " + must, names.contains(must), names.toString());
                }
                boolean allArgs = true;
                for (com.fadcam.ui.faditor.ai.joybot.ToolDocs.Doc x : real) {
                    if (x.name.matches("[a-z][a-z0-9_]{0,63}") && x.text.length() <= 1000) continue;
                    allArgs = false;
                }
                check("real tool docs: names and lengths fit every provider", allArgs, "");
            } catch (Exception ex) {
                check("real tool docs: readable", false, ex.toString());
            }
        }

        // ── Help for VisLab and Director (BOT-2, 10-28): real questions reach the right answer ──
        {
            String[][] qa = {
                    {"how do I use vislab", "vislab"},
                    {"can I build a look from scratch?", "vislab_build"},
                    {"how do I make the bars react to the kick", "vislab_music"},
                    {"how do I get words on a spiral", "vislab_words"},
                    {"can I play a midi file", "vislab_midi"},
                    {"how do I put a visualiser over other apps", "vislab_float"},
                    {"how do I add a VHS effect", "vislab_effects"},
                    {"can you do a big job, the whole video", "director"},
                    // the older Studio answers still win their own questions
                    {"how do I add a visualizer", "visualizer"},
                    {"how do I add captions", "captions"},
            };
            for (String[] x : qa) {
                com.fadcam.ui.faditor.ai.joybot.HelpBook.Topic t = com.fadcam.ui.faditor.ai.joybot.HelpBook.find(x[0]);
                eq("help BOT-2: " + x[0], x[1], t == null ? null : t.id);
            }
        }

        // ── HelpBook.saysItCant: an honest "no" is an answer, not a dodge (bench 10-24, BOT-1 10-28) ──
        {
            String[] honest = {
                    "I can't draw on the video itself in Studio yet, so I haven't changed anything.",
                    "There is no tool that draws on a clip, so I still haven\u2019t changed anything.",
                    "The title is locked, so I left it where it is. Want me to unlock it first?",
                    "That isn't possible here: nothing was changed."};
            for (String h : honest) {
                check("says it can't: " + h.substring(0, 24), com.fadcam.ui.faditor.ai.joybot.HelpBook.saysItCant(h), h);
            }
            String[] claims = {"Done! The title is now red.", "I've moved the logo to the corner.",
                    "I changed the font to Bebas.", "The first clip is called Intro now."};
            for (String c : claims) {
                check("a real claim is not a can't: " + c.substring(0, 18),
                        !com.fadcam.ui.faditor.ai.joybot.HelpBook.saysItCant(c), c);
            }
        }

        // ── TipBook: "Did you know?" on every empty open, offline too (owner, BOT-2 10-28) ──
        {
            List<com.fadcam.ui.faditor.ai.joybot.TipBook.Tip> tips = com.fadcam.ui.faditor.ai.joybot.TipBook.TIPS;
            check("tips: a big library (40+)", tips.size() >= 40, String.valueOf(tips.size()));
            Set<String> tipIds = new HashSet<>();
            Set<String> tipRoutes = new HashSet<>(Arrays.asList(null, "record", "library", "studio", "vislab",
                    "joybrush", "spritelab", "trash", "bridge", "textlab", "remote", "finder"));
            java.util.EnumSet<com.fadcam.ui.faditor.ai.joybot.TipBook.Wing> wings =
                    java.util.EnumSet.noneOf(com.fadcam.ui.faditor.ai.joybot.TipBook.Wing.class);
            for (com.fadcam.ui.faditor.ai.joybot.TipBook.Tip x : tips) {
                tipIds.add(x.id);
                wings.add(x.wing);
                check("tips: route is a real one: " + x.id, tipRoutes.contains(x.route), String.valueOf(x.route));
                check("tips: reads after 'Did you know ': " + x.id,
                        (Character.isLowerCase(x.text.charAt(0))
                                || x.text.matches("^(I|VisLab|SpriteLab|Capture|JoyBrush|Claude|TextLab)\\b.*"))
                                && x.text.endsWith("."), x.text);
                check("tips: short enough for one look: " + x.id, x.text.length() <= 160, String.valueOf(x.text.length()));
            }
            eq("tips: no two share an id", tips.size(), tipIds.size());
            eq("tips: every wing has some", com.fadcam.ui.faditor.ai.joybot.TipBook.Wing.values().length, wings.size());
            // SHELL-04 (11-13): every route a tip, a help topic or open_room names is a real case of the chat's
            // route() (read as text), so "Take me there" never silently does nothing.
            try {
                String chat = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        "app/src/main/java/com/fadcam/ui/faditor/ai/ChatAssistantActivity.java")), "UTF-8");
                String nav = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        "app/src/main/java/com/fadcam/ui/faditor/ai/actions/NavigateActions.java")), "UTF-8");
                Set<String> named = new java.util.TreeSet<>();
                for (com.fadcam.ui.faditor.ai.joybot.TipBook.Tip x : tips) if (x.route != null) named.add(x.route);
                for (com.fadcam.ui.faditor.ai.joybot.HelpBook.Topic x : com.fadcam.ui.faditor.ai.joybot.HelpBook.TOPICS) {
                    if (x.route != null) named.add(x.route);
                }
                java.util.regex.Matcher rooms = java.util.regex.Pattern.compile(
                        "ROOMS = Arrays.asList\\(([^;]*)\\);").matcher(nav);
                check("routes: open_room's list is readable", rooms.find(), "");
                java.util.regex.Matcher q = java.util.regex.Pattern.compile("\"([a-z_]+)\"").matcher(rooms.group(1));
                int navRooms = 0;
                while (q.find()) { named.add(q.group(1)); navRooms++; }
                check("routes: open_room has remote and finder", rooms.group(1).contains("\"remote\"")
                        && rooms.group(1).contains("\"finder\""), rooms.group(1));
                for (String r : named) {
                    boolean has = chat.contains("case \"" + r + "\":")
                            || ("record".equals(r) && chat.contains("case IdeaEngine.ROUTE_RECORD:"));
                    check("routes: route() has a case for " + r, has, r);
                }
                check("routes: 13+ routes checked", named.size() >= 13, String.valueOf(named.size()));
                int av = chat.indexOf("case \"avatar\":"), sp = chat.indexOf("case \"space\":");
                check("routes: Avatar opens standalone with no project (no Studio fallback)",
                        av > 0 && sp > av && !chat.substring(av, sp).contains("route(\"studio\")"), "");
                check("routes: open_room lists 13 rooms", navRooms == 13, String.valueOf(navRooms));
            } catch (java.io.IOException ex) {
                check("routes: sources readable", false, ex.toString());
            }

            // Someone who only draws: mostly JoyBrush, but other wings still turn up.
            java.util.Map<com.fadcam.ui.faditor.ai.joybot.TipBook.Wing, Integer> drawer = new java.util.EnumMap<>(
                    com.fadcam.ui.faditor.ai.joybot.TipBook.Wing.class);
            drawer.put(com.fadcam.ui.faditor.ai.joybot.TipBook.Wing.JOYBRUSH, 30);
            java.util.Random rnd = new java.util.Random(7);
            int jb = 0, other = 0, n = 4000;
            for (int i = 0; i < n; i++) {
                com.fadcam.ui.faditor.ai.joybot.TipBook.Tip x = com.fadcam.ui.faditor.ai.joybot.TipBook.pick(
                        drawer, new HashSet<String>(), rnd);
                if (x.wing == com.fadcam.ui.faditor.ai.joybot.TipBook.Wing.JOYBRUSH) jb++;
                else if (x.wing != com.fadcam.ui.faditor.ai.joybot.TipBook.Wing.JOYBOT) other++;
            }
            check("tips: a drawer gets mostly JoyBrush (> 30%)", jb > n * 0.30, jb + "/" + n);
            check("tips: ...and still some from other wings (> 15%)", other > n * 0.15, other + "/" + n);

            // Seen tips stay away until all are seen; then they come round again.
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < tips.size(); i++) {
                com.fadcam.ui.faditor.ai.joybot.TipBook.Tip x = com.fadcam.ui.faditor.ai.joybot.TipBook.pick(
                        new java.util.HashMap<com.fadcam.ui.faditor.ai.joybot.TipBook.Wing, Integer>(), seen, rnd);
                check("tips: no repeat before all are seen (" + i + ")", !seen.contains(x.id), x.id);
                seen.add(x.id);
            }
            check("tips: all seen, still one to show", com.fadcam.ui.faditor.ai.joybot.TipBook.pick(
                    new java.util.HashMap<com.fadcam.ui.faditor.ai.joybot.TipBook.Wing, Integer>(), seen, rnd) != null, "");

            // Offline ends on what still works, then a tip: never on the bad news.
            String off = com.fadcam.ui.faditor.ai.joybot.TipBook.offlineHope("Your phone isn't online.", tips.get(0));
            check("tips: offline starts with the problem", off.startsWith("Your phone isn't online."), off);
            check("tips: offline says I'm still here", off.contains("still right here"), off);
            check("tips: offline ends on a tip", off.endsWith(tips.get(0).text) && off.contains("Did you know "), off);
        }

        // ── KeyCatcher: a key typed in the chat never leaves the phone (BOT-6 10-28). FAKE keys only. ──
        {
            String or = "sk-or-v1-" + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
            String oa = "sk-proj-" + "AbCdEf0123456789AbCdEf0123456789_xyz-0123";
            String an = "sk-ant-api03-" + "AbCdEf0123456789AbCdEf0123456789";
            com.fadcam.ui.faditor.ai.joybot.KeyCatcher.Found f;

            f = com.fadcam.ui.faditor.ai.joybot.KeyCatcher.find(or, false);
            check("key: a pasted OpenRouter key is caught", f != null && f.key.equals(or), "");
            check("key: ...and named OpenRouter", f != null && f.provider == com.fadcam.ui.faditor.ai.joybot.AiProviders.OPENROUTER, "");
            check("key: ...and the chat keeps only its last four", f != null && !f.shown.contains(or.substring(0, 20))
                    && f.shown.endsWith(or.substring(or.length() - 4)), f == null ? "" : f.shown);

            f = com.fadcam.ui.faditor.ai.joybot.KeyCatcher.find("here's my key " + oa + " thanks", false);
            check("key: inside a sentence", f != null && f.key.equals(oa)
                    && f.provider == com.fadcam.ui.faditor.ai.joybot.AiProviders.OPENAI, "");
            check("key: ...the sentence is kept, the key masked", f != null && f.shown.startsWith("here's my key \uD83D\uDD11")
                    && f.shown.endsWith("thanks") && !f.shown.contains(oa), f == null ? "" : f.shown);

            String broken = an.substring(0, 18) + "\n" + an.substring(18);
            f = com.fadcam.ui.faditor.ai.joybot.KeyCatcher.find(broken, false);
            check("key: broken across lines by the copy, joined", f != null && f.key.equals(an)
                    && f.provider == com.fadcam.ui.faditor.ai.joybot.AiProviders.ANTHROPIC, f == null ? "null" : f.key);

            String plain = "Zk3Q9xLmPa7Rt2VwYb8Nc4Hd6Jf1Gs5";
            check("key: an unknown shape is not caught out of the blue",
                    com.fadcam.ui.faditor.ai.joybot.KeyCatcher.find(plain, false) == null, "");
            f = com.fadcam.ui.faditor.ai.joybot.KeyCatcher.find(plain, true);
            check("key: ...but is, right after Joybot asked for one", f != null && f.key.equals(plain) && f.provider == null, "");

            String[] notKeys = {"make the title say sk-8 skateboard", "skip the first 20 seconds",
                    "add a title that says ChatGPT", "rename it to my_holiday_video_2026_final_cut.mp4",
                    "open https://openrouter.ai/keys please", "use a key frame on the logo"};
            for (String n : notKeys) {
                check("key: not a key, even when expecting one: " + n,
                        com.fadcam.ui.faditor.ai.joybot.KeyCatcher.find(n, true) == null, n);
            }
        }

        // ── SetupTalk: connecting an AI by talking (owner, BOT-6 10-28) ──
        {
            String[] asks = {"connect an AI", "Connect AI", "how do I connect my ChatGPT?", "I have Claude Pro",
                    "i pay for chatgpt plus", "set up the AI", "add my api key", "I've got a Gemini subscription",
                    "can I hook up Claude Code", "api key"};
            for (String a : asks) check("connect: " + a, com.fadcam.ui.faditor.ai.joybot.SetupTalk.asksToConnect(a), a);
            String[] edits = {"add a title that says ChatGPT", "use a key frame on the logo",
                    "make a slide about Claude Monet", "connect the two clips", "add a link to the description",
                    "I have an AI generated picture", "set up a slide"};
            for (String e : edits) check("not connect: " + e, !com.fadcam.ui.faditor.ai.joybot.SetupTalk.asksToConnect(e), e);

            eq("check: 200 works", com.fadcam.ui.faditor.ai.joybot.SetupTalk.Check.WORKS,
                    com.fadcam.ui.faditor.ai.joybot.SetupTalk.checkOf(200));
            eq("check: 401 wrong key", com.fadcam.ui.faditor.ai.joybot.SetupTalk.Check.WRONG_KEY,
                    com.fadcam.ui.faditor.ai.joybot.SetupTalk.checkOf(401));
            eq("check: 402 no credit", com.fadcam.ui.faditor.ai.joybot.SetupTalk.Check.NO_CREDIT,
                    com.fadcam.ui.faditor.ai.joybot.SetupTalk.checkOf(402));
            eq("check: 500 service trouble", com.fadcam.ui.faditor.ai.joybot.SetupTalk.Check.SERVICE_TROUBLE,
                    com.fadcam.ui.faditor.ai.joybot.SetupTalk.checkOf(500));
            for (com.fadcam.ui.faditor.ai.joybot.SetupTalk.Check c : com.fadcam.ui.faditor.ai.joybot.SetupTalk.Check.values()) {
                String s = com.fadcam.ui.faditor.ai.joybot.SetupTalk.afterCheck(c, "OpenRouter");
                check("check words name the service: " + c, s.contains("OpenRouter"), s);
                check("check words: a failure says nothing was saved: " + c,
                        c == com.fadcam.ui.faditor.ai.joybot.SetupTalk.Check.WORKS || s.contains("Nothing was saved"), s);
            }
            check("plan: no computer door promised before it's built", !com.fadcam.ui.faditor.ai.joybot.SetupTalk.explain(
                    com.fadcam.ui.faditor.ai.joybot.SetupTalk.Path.CLAUDE, false).contains("Claude Code"), "");
            check("plan: the door, once built", com.fadcam.ui.faditor.ai.joybot.SetupTalk.explain(
                    com.fadcam.ui.faditor.ai.joybot.SetupTalk.Path.CHATGPT, true).contains("Codex"), "");
            check("free: promises the key stays on the phone", com.fadcam.ui.faditor.ai.joybot.SetupTalk.explain(
                    com.fadcam.ui.faditor.ai.joybot.SetupTalk.Path.FREE, false).contains("never goes to the AI"), "");
        }

        // ── WhatHere: "What can I do here?" lists only this room's real actions (BOT-2 10-28) ──
        {
            String[] asks = {"what can I do here?", "What can you do in here", "what can I make here",
                    "so what can we do now", "what else can you do here?"};
            for (String q : asks) check("what here: " + q, com.fadcam.ui.faditor.ai.joybot.WhatHere.asks(q), q);
            String[] not = {"what can I do about the noise", "make the title say what can I do here",
                    "what can you do"};
            for (String q : not) check("not what here: " + q, !com.fadcam.ui.faditor.ai.joybot.WhatHere.asks(q), q);
            java.util.List<com.fadcam.ui.faditor.ai.joybot.WhatHere.Action> acts = new java.util.ArrayList<>();
            acts.add(new com.fadcam.ui.faditor.ai.joybot.WhatHere.Action("set_text_style", "Change how a text looks", "STUDIO", false));
            acts.add(new com.fadcam.ui.faditor.ai.joybot.WhatHere.Action("list_items", "List what is on the timeline", "STUDIO", true));
            acts.add(new com.fadcam.ui.faditor.ai.joybot.WhatHere.Action("vislab_change", "Change a VisLab look", "APP", false));
            acts.add(new com.fadcam.ui.faditor.ai.joybot.WhatHere.Action("joybrush_layers", "Change layers", "APP", false));
            acts.add(new com.fadcam.ui.faditor.ai.joybot.WhatHere.Action("open_room", "Open a part of the app", "APP", false));
            String studio = com.fadcam.ui.faditor.ai.joybot.WhatHere.answer(IdeaEngine.Room.STUDIO, true, true, acts);
            check("what here: Studio lists Studio actions, changes first", studio.indexOf("Change how a text looks")
                    < studio.indexOf("List what is on the timeline") && !studio.contains("VisLab look"), studio);
            check("what here: Studio names what works with no AI", studio.contains("no AI"), studio);
            String vis = com.fadcam.ui.faditor.ai.joybot.WhatHere.answer(IdeaEngine.Room.VISLAB, false, false, acts);
            check("what here: VisLab lists only VisLab, says it needs an AI", vis.contains("Change a VisLab look")
                    && !vis.contains("Change layers") && vis.contains("With an AI connected"), vis);
            String jb = com.fadcam.ui.faditor.ai.joybot.WhatHere.answer(IdeaEngine.Room.JOYBRUSH, false, true, acts);
            check("what here: JoyBrush lists only JoyBrush", jb.contains("Change layers") && !jb.contains("text looks"), jb);
            String lobby = com.fadcam.ui.faditor.ai.joybot.WhatHere.answer(IdeaEngine.Room.LOBBY, false, true, acts);
            check("what here: the lobby lists app-wide ones", lobby.contains("Open a part of the app")
                    && !lobby.contains("VisLab look"), lobby);
            java.util.List<com.fadcam.ui.faditor.ai.joybot.WhatHere.Action> many = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) many.add(new com.fadcam.ui.faditor.ai.joybot.WhatHere.Action("t" + i, "Thing " + i, "STUDIO", false));
            check("what here: a long list ends with how many more",
                    com.fadcam.ui.faditor.ai.joybot.WhatHere.answer(IdeaEngine.Room.STUDIO, true, true, many).contains("…and 6 more"), "");
        }

        // ── HonestClaim: "done" checked against what really happened (BOT-A2, 11-13) ──
        {
            check("honest: the bench's rename claim is a claim",
                    com.fadcam.ui.faditor.ai.joybot.HonestClaim.claims("The first clip is called Intro now."), "");
            check("honest: the old tells still count",
                    com.fadcam.ui.faditor.ai.joybot.HonestClaim.claims("Done — the title is red now."), "");
            check("honest: a plain description is not a claim",
                    !com.fadcam.ui.faditor.ai.joybot.HonestClaim.claims("Your video is 20 seconds long."), "");
            com.fadcam.ui.faditor.ai.joybot.HonestClaim.Ledger l = new com.fadcam.ui.faditor.ai.joybot.HonestClaim.Ledger();
            l.ran("set_text_style", null, true);
            l.ran("rename_clip", "Error: no clip called c-nope.\nClips: c-beach, c-city", false);
            String reply = "Done: the title is red and the first clip is called Intro now.";
            String note = com.fadcam.ui.faditor.ai.joybot.HonestClaim.note(reply, l);
            check("honest: one of two failed, the claim gets a note", note != null, String.valueOf(note));
            check("honest: the note names the failed step and its reason", note != null
                    && note.contains("“rename clip” didn't work (no clip called c-nope)")
                    && note.contains("The other change did go through."), String.valueOf(note));
            check("honest: a reply that owns up gets no note", com.fadcam.ui.faditor.ai.joybot.HonestClaim.note(
                    "The title is red, but the rename didn't work: there's no such clip.", l) == null, "");
            check("honest: a question back gets no note", com.fadcam.ui.faditor.ai.joybot.HonestClaim.note(
                    "Which clip did you mean?", l) == null, "");
            com.fadcam.ui.faditor.ai.joybot.HonestClaim.Ledger retried = new com.fadcam.ui.faditor.ai.joybot.HonestClaim.Ledger();
            retried.ran("rename_clip", "Error: no clip called c-nope.", false);
            retried.ran("rename_clip", null, true);
            check("honest: a failed step made good on retry is no failure",
                    com.fadcam.ui.faditor.ai.joybot.HonestClaim.note(reply, retried) == null, "");
            com.fadcam.ui.faditor.ai.joybot.HonestClaim.Ledger none = new com.fadcam.ui.faditor.ai.joybot.HonestClaim.Ledger();
            none.ran("rename_clip", "Error: no clip called c-nope.", false);
            check("honest: nothing changed at all is the agent's own path, not a note",
                    com.fadcam.ui.faditor.ai.joybot.HonestClaim.note(reply, none) == null, "");
            com.fadcam.ui.faditor.ai.joybot.HonestClaim.Ledger three = new com.fadcam.ui.faditor.ai.joybot.HonestClaim.Ledger();
            three.ran("set_text_style", null, true);
            three.ran("add_text", null, true);
            three.ran("rename_clip", "Error: " + "x".repeat(200), false);
            String n3 = com.fadcam.ui.faditor.ai.joybot.HonestClaim.note("All set!", three);
            check("honest: counts the other changes, keeps a long error short", n3 != null
                    && n3.contains("The other 2 changes did go through.") && n3.length() < 200, String.valueOf(n3));
        }

        // ── JobKey: a killed VisLab / JoyBrush / TextLab job carries on where it ran (BOT-A16, 11-13) ──
        {
            check("job key: a Studio project's job is resumable",
                    com.fadcam.ui.faditor.ai.joybot.JobKey.resumable("p1", "STUDIO"), "");
            for (String r : new String[]{"VISLAB", "JOYBRUSH", "TEXTLAB"}) {
                check("job key: a " + r + " job with no project is resumable",
                        com.fadcam.ui.faditor.ai.joybot.JobKey.resumable(null, r), "");
            }
            check("job key: a lobby question is not kept", !com.fadcam.ui.faditor.ai.joybot.JobKey.resumable(null, "LOBBY"), "");
            eq("job key: spelled like the agent's conversation key (project)", "project:p1",
                    com.fadcam.ui.faditor.ai.joybot.JobKey.of("p1", "STUDIO"));
            eq("job key: spelled like the agent's conversation key (room)", "room:VISLAB",
                    com.fadcam.ui.faditor.ai.joybot.JobKey.of(null, "VISLAB"));
            eq("job key: a pre-11-13 file (project id only) is a Studio job", "project:p1",
                    com.fadcam.ui.faditor.ai.joybot.JobKey.fromSaved("", "p1"));
            eq("job key: a saved room key is kept", "room:JOYBRUSH",
                    com.fadcam.ui.faditor.ai.joybot.JobKey.fromSaved("room:JOYBRUSH", ""));
            eq("job key: a file naming nothing has nothing to carry on", null,
                    com.fadcam.ui.faditor.ai.joybot.JobKey.fromSaved("", ""));
            eq("job key: room of a project job", "STUDIO", com.fadcam.ui.faditor.ai.joybot.JobKey.roomOf("project:p1"));
            eq("job key: room of a VisLab job", "VISLAB", com.fadcam.ui.faditor.ai.joybot.JobKey.roomOf("room:VISLAB"));
            eq("job key: Studio's carry-on words unchanged", "Check the project first — some of it is already done.",
                    com.fadcam.ui.faditor.ai.joybot.JobKey.checkFirst("STUDIO"));
            check("job key: VisLab carry-on reads the look first, no repeats",
                    com.fadcam.ui.faditor.ai.joybot.JobKey.checkFirst("VISLAB").contains("don't repeat"), "");
            // The agent's own key must stay spelled the same way, read as text (JoybotAgent needs Android).
            try {
                String agent = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        "app/src/main/java/com/fadcam/ui/faditor/ai/joybot/JoybotAgent.java")), "UTF-8");
                check("job key: JoybotAgent.conversationKey has the same spelling",
                        agent.contains("return projectId != null ? \"project:\" + projectId : \"room:\" + room.name();"), "");
                check("job key: a finished request clears only its own job",
                        agent.contains("JobFile.clearIf(app, ask.key());") && !agent.contains("JobFile.clear(app);\n                AIJobService"), "");
                check("job key: every checkpoint goes through JobFile.save's own rule",
                        !agent.contains("if (ask.projectId != null) JobFile.save"), "");
            } catch (java.io.IOException ex) {
                check("job key: agent readable", false, ex.toString());
            }
        }

        // ── CellTags: Joybot's sprite cell tags, spelled as SpriteLab spells them (AN-05, 11-13) ──
        {
            java.util.List<String> parsed = com.fadcam.ui.faditor.ai.joybot.CellTags.parse(
                    Arrays.asList(" Mouth ", "mouth, OPEN", "#blink", ""));
            eq("cell tags: cleaned, split on commas, no repeats", "[mouth, open, blink]", parsed.toString());
            java.util.List<String> have = Arrays.asList("mouth", "closed");
            eq("cell tags: add keeps the old and adds the new", "[mouth, closed, open]",
                    com.fadcam.ui.faditor.ai.joybot.CellTags.apply(have, Arrays.asList("open", "mouth"),
                            com.fadcam.ui.faditor.ai.joybot.CellTags.Mode.ADD).toString());
            eq("cell tags: set replaces", "[open]", com.fadcam.ui.faditor.ai.joybot.CellTags.apply(have,
                    Arrays.asList("open"), com.fadcam.ui.faditor.ai.joybot.CellTags.Mode.SET).toString());
            eq("cell tags: remove takes off only those", "[closed]", com.fadcam.ui.faditor.ai.joybot.CellTags.apply(have,
                    Arrays.asList("mouth"), com.fadcam.ui.faditor.ai.joybot.CellTags.Mode.REMOVE).toString());
            eq("cell tags: remove with none given clears", "[]", com.fadcam.ui.faditor.ai.joybot.CellTags.apply(have,
                    new java.util.ArrayList<String>(), com.fadcam.ui.faditor.ai.joybot.CellTags.Mode.REMOVE).toString());
            eq("cell tags: unknown mode is add", com.fadcam.ui.faditor.ai.joybot.CellTags.Mode.ADD,
                    com.fadcam.ui.faditor.ai.joybot.CellTags.modeOf("whatever"));
            eq("cell tags: replace means set", com.fadcam.ui.faditor.ai.joybot.CellTags.Mode.SET,
                    com.fadcam.ui.faditor.ai.joybot.CellTags.modeOf(" Replace"));
            check("cell tags: 'lip sync' wakes the sprite tools", com.fadcam.ui.faditor.ai.joybot.ToolRouter.pick(
                    "tag the mouths for lip sync").contains("sprites"), "");
        }

        // ── HelpBook.wantsChange: a request that must end in a change (Note 9, 2026-10-15) ──
        {
            check("wants change: name every cell", com.fadcam.ui.faditor.ai.joybot.HelpBook.wantsChange(
                    "this is my Starguy sprite sheet of expressions. Look at it and name every cell with its expression"), "");
            check("wants change: fade the title", com.fadcam.ui.faditor.ai.joybot.HelpBook.wantsChange(
                    "make the hello 2 text fade in over 2 seconds"), "");
            check("not a change: look and tell me, do not change", !com.fadcam.ui.faditor.ai.joybot.HelpBook.wantsChange(
                    "look at my sprite sheet and tell me what each cell shows. Do not change anything."), "");
            check("not a change: a question", !com.fadcam.ui.faditor.ai.joybot.HelpBook.wantsChange(
                    "how do I add captions?"), "");
            check("not a change: describe", !com.fadcam.ui.faditor.ai.joybot.HelpBook.wantsChange(
                    "describe the second clip"), "");
            check("not a change: 'names' inside a word", !com.fadcam.ui.faditor.ai.joybot.HelpBook.wantsChange(
                    "nice cinematography"), "");
        }

        // ── SlideContract: a slide that would draw nothing is refused (Note 9, 2026-10-15) ──
        {
            String head = "<!doctype html><html><body>";
            String tail = "<script src=\"gsap.min.js\"></script><script src=\"faditor_runtime.js\"></script>";
            // The real one: bare stage, animates a #title it never wrote, loose gsap.to tweens.
            String empty = head + "<div id=\"stage\"></div>" + tail
                    + "<script>const tl = gsap.timeline({paused:true});"
                    + "const title = document.getElementById('title');"
                    + "if (title) { gsap.to(title, {opacity:1, duration:0.4}); }"
                    + "Faditor.register(tl, 3500);</script></body></html>";
            String why = com.fadcam.ui.faditor.slides.SlideContract.validate(empty);
            check("slide gate: the empty Note 9 slide is refused", why != null, String.valueOf(why));
            // The 10-14 overlay that was fine (a #bg and a #title, tl.fromTo, a colour '#FFD700' in CSS).
            String good = head + "<style>#title{color:#FFD700}</style><div id=\"stage\"><div id=\"bg\"></div>"
                    + "<h1 id=\"title\">Restore video test</h1></div>" + tail
                    + "<script>const tl = gsap.timeline({paused:true});"
                    + "tl.fromTo('#bg', {opacity:0}, {opacity:0.6, duration:1});"
                    + "tl.fromTo('#title', {opacity:0, color:'#FFD700'}, {opacity:1, duration:1.5}, '-=0.5');"
                    + "Faditor.register(tl, 3500);</script></body></html>";
            String ok = com.fadcam.ui.faditor.slides.SlideContract.validate(good);
            check("slide gate: a real slide (with a colour that looks like an id) passes", ok == null, String.valueOf(ok));
            String loose = good.replace("tl.fromTo(", "gsap.fromTo(");
            check("slide gate: loose gsap tweens are refused",
                    com.fadcam.ui.faditor.slides.SlideContract.validate(loose) != null, "");
            String built = head + "<div id=\"stage\"></div>" + tail
                    + "<script>const tl = gsap.timeline({paused:true});"
                    + "const t = document.createElement('div'); t.id = 'title'; t.textContent = 'Hi';"
                    + "document.getElementById('stage').appendChild(t);"
                    + "tl.from(t, {opacity:0}); Faditor.register(tl, 2000);</script></body></html>";
            check("slide gate: a script that builds its elements passes",
                    com.fadcam.ui.faditor.slides.SlideContract.validate(built) == null,
                    String.valueOf(com.fadcam.ui.faditor.slides.SlideContract.validate(built)));
            check("slide gate: the plain fallback passes", com.fadcam.ui.faditor.slides.SlideContract.validate(
                    com.fadcam.ui.faditor.slides.SlideContract.buildFallbackHtml(1080, 1920,
                            com.fadcam.ui.faditor.slides.SlideContract.MODE_OVERLAY, "Hi", 3000)) == null, "");
        }

        // ── Joybot's rooms: Sprite Lab and the Library ───────────────────
        {
            // A sheet-only project is not "empty" to Sprite Lab: its sheet ideas still show.
            ProjectFacts f = new ProjectFacts();
            f.online = true;
            ProjectFacts.SheetFacts s = new ProjectFacts.SheetFacts();
            s.id = "sh2"; s.name = "Cat"; s.cells = 8; s.namedCells = 8; s.animations = 0;
            f.sheets.add(s);
            IdeaEngine.Offer lab = IdeaEngine.offer(f, IdeaEngine.Room.SPRITELAB, FLOWS, NONE);
            check("spritelab: sheet-only project offers ideas", lab.hasIdea(), labels(lab));
            eq("spritelab: all named, no animation: make an animation", "Make an animation", lab.ideas.get(0).label);
            eq("spritelab: make an animation is ASK", Idea.Kind.ASK, lab.ideas.get(0).kind);
            eq("spritelab: the idea targets the sheet", "sh2", lab.ideas.get(0).target);
            check("spritelab: no name-the-cells when every cell is named",
                    !labels(lab).contains("Name the cells"), labels(lab));

            s.namedCells = 0; s.animations = 2;
            lab = IdeaEngine.offer(f, IdeaEngine.Room.SPRITELAB, FLOWS, NONE);
            eq("spritelab: unnamed cells: name the cells first", "Name the cells", lab.ideas.get(0).label);
            eq("spritelab: name the cells is ASK", Idea.Kind.ASK, lab.ideas.get(0).kind);
            check("spritelab: name the cells asks Joybot to look",
                    lab.ideas.get(0).say.startsWith("Look at my sprite sheet"), lab.ideas.get(0).say);
            check("spritelab: animations exist, no make-an-animation",
                    !labels(lab).contains("Make an animation"), labels(lab));

            // Sprite Lab never falls through into the talking-video ideas.
            f.spine.add(transcribed("c9", "Talk", 60_000, 50));
            lab = IdeaEngine.offer(f, IdeaEngine.Room.SPRITELAB, FLOWS, NONE);
            check("spritelab: no talking-video ideas", !labels(lab).contains("Transcribe")
                    && !labels(lab).contains("captions"), labels(lab));

            f.online = false;
            lab = IdeaEngine.offer(f, IdeaEngine.Room.SPRITELAB, FLOWS, NONE);
            check("spritelab offline: sheet ideas hidden", !lab.hasIdea(), labels(lab));

            // Library: the newest recording this project does not use, taken to the Library tab.
            ProjectFacts lib = new ProjectFacts();
            lib.newestRecordingNotInProject = "Sunset 3";
            IdeaEngine.Offer lo = IdeaEngine.offer(lib, IdeaEngine.Room.LIBRARY, FLOWS, NONE);
            eq("library: newest recording first", "Show my newest recording", lo.ideas.get(0).label);
            eq("library: it is a route", Idea.Kind.ROUTE, lo.ideas.get(0).kind);
            eq("library: route goes to the library tab", IdeaEngine.ROUTE_LIBRARY, lo.ideas.get(0).flow);
            eq("library opener", "“Sunset 3” is your newest recording, and this project doesn't use it yet.",
                    lo.opener);
            eq("library: two entries with Something else", 2, lo.ideas.size());
            lib.online = false;
            lo = IdeaEngine.offer(lib, IdeaEngine.Room.LIBRARY, FLOWS, NONE);
            check("library offline: route still offered (no model needed)", lo.hasIdea(), labels(lo));

            lib.newestRecordingNotInProject = "";
            lo = IdeaEngine.offer(lib, IdeaEngine.Room.LIBRARY, FLOWS, NONE);
            check("library: nothing unused, nothing offered", !lo.hasIdea(), labels(lo));

            // The Library room shows only its own idea, even for a project with clips.
            lib.newestRecordingNotInProject = "Sunset 3";
            lib.spine.add(transcribed("c10", "Talk", 60_000, 50));
            lo = IdeaEngine.offer(lib, IdeaEngine.Room.LIBRARY, FLOWS, NONE);
            eq("library: only the library idea beside Something else", 2, lo.ideas.size());
            IdeaEngine.Offer so = IdeaEngine.offer(lib, IdeaEngine.Room.STUDIO, FLOWS, NONE);
            check("studio: no library route", !labels(so).contains("Show my newest recording"), labels(so));
        }

        // Recipes (gap F7): a recipe replays offline only when no step names this project's things.
        {
            List<Idea.Step> portable = Arrays.asList(
                    Idea.Step.of("toggle_captions", "clipId", "all"),
                    Idea.Step.of("set_text_style", "text", "all", "font", "Mono"));
            check("recipe: all-clips steps are portable",
                    com.fadcam.ui.faditor.ai.joybot.Recipe.portable(portable), "");
            check("recipe: a clip id makes it not portable",
                    !com.fadcam.ui.faditor.ai.joybot.Recipe.portable(Arrays.asList(
                            Idea.Step.of("toggle_captions", "clipId", "3f2a9c1e-1b2c-4d5e-8f90-123456789abc"))), "");
            check("recipe: a named text makes it not portable",
                    !com.fadcam.ui.faditor.ai.joybot.Recipe.portable(Arrays.asList(
                            Idea.Step.of("set_text_style", "text", "hello 2"))), "");
            check("recipe: an empty recipe is not portable",
                    !com.fadcam.ui.faditor.ai.joybot.Recipe.portable(new java.util.ArrayList<Idea.Step>()), "");
            eq("recipe: slug drops punctuation", "clean-up-my-talk",
                    com.fadcam.ui.faditor.ai.joybot.Recipe.slug("Clean up my talk!"));
            com.fadcam.ui.faditor.ai.joybot.Recipe r =
                    new com.fadcam.ui.faditor.ai.joybot.Recipe("Tidy", "tidy up", portable);
            check("recipe: describe says offline when portable", r.describe().contains("offline"), r.describe());
        }

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static int indexOf(IdeaEngine.Offer o, String label) {
        List<Idea> l = o.ideas;
        for (int i = 0; i < l.size(); i++) if (l.get(i).label.equals(label)) return i;
        return -1;
    }
}
