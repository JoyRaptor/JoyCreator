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
            check("howto: how do I", com.fadcam.ui.faditor.ai.joybot.HelpBook.isHowTo("How do I export?"), "");
            check("howto: not a command", !com.fadcam.ui.faditor.ai.joybot.HelpBook.isHowTo("cut the ums"), "");
            check("help: every topic has an answer", com.fadcam.ui.faditor.ai.joybot.HelpBook.TOPICS.size() > 30, "");
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
