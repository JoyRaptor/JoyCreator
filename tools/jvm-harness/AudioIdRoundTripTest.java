import com.fadcam.ui.faditor.layers.LinkGroup;
import com.fadcam.ui.faditor.layers.LinkMember;
import com.fadcam.ui.faditor.layers.LinkedProperty;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.List;

/**
 * Audio clip ids survive save -> load, off device.
 *
 * <p><b>Why this exists.</b> 2026-09-23, project a32d24e2 on the Note 20: two saves minutes
 * apart with no edits differed ONLY in the three audio clip ids (and the layer items that
 * mirror them). ProjectStorage wrote {@code id} but the loader never read it, so every load —
 * and every undo, which restores through the same deserializer — minted fresh UUIDs. Anything
 * that names an audio clip by id then pointed at nothing: a music visualizer's
 * {@code audioSourceRef} went blank, and a link group holding an audio clip was pruned.
 *
 * <p>Runs the REAL {@code ProjectStorage.toJson} / {@code fromJson} (the undo-snapshot pair,
 * which is the same Gson adapter {@code load()} uses) on a real {@code ProjectStorage} built
 * over a stub Context rooted in a temp dir. The headline check is the owner's exact symptom:
 * save, load, save again — the two files must be identical.
 */
public class AudioIdRoundTripTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static AudioClip audio(String name, long offsetMs) {
        AudioClip ac = new AudioClip(android.net.Uri.parse("file:///sdcard/Music/" + name), 60_000L);
        ac.setOffsetMs(offsetMs);
        return ac;
    }

    static List<AudioClip> audios(FaditorProject p) { return p.getTimeline().getAudioClips(); }

    static boolean hasAudio(FaditorProject p, String id) {
        for (AudioClip a : audios(p)) if (a.getId().equals(id)) return true;
        return false;
    }

    static String withoutClock(String json) {
        return json.replaceAll("\"lastModified\"\\s*:\\s*-?\\d+", "");
    }

    /** Every point where the two saves differ, with a little context — so a failure names the field. */
    static void printDivergence(String a, String b) {
        int i = 0, shown = 0;
        while (i < Math.min(a.length(), b.length()) && shown < 8) {
            if (a.charAt(i) == b.charAt(i)) { i++; continue; }
            int from = Math.max(0, i - 60);
            System.out.println("      save1: ..." + a.substring(from, Math.min(a.length(), i + 60)));
            System.out.println("      save2: ..." + b.substring(from, Math.min(b.length(), i + 60)));
            shown++;
            // Resync past the differing token (ids are fixed-width, so skipping works).
            while (i < Math.min(a.length(), b.length()) && a.charAt(i) != b.charAt(i)) i++;
        }
        if (a.length() != b.length()) System.out.println("      lengths " + a.length() + " vs " + b.length());
    }

    public static void main(String[] args) throws Exception {
        final File root = new File(System.getProperty("java.io.tmpdir"), "audioid-test");
        root.mkdirs();
        ProjectStorage ps = new ProjectStorage(new android.content.Context() {
            @Override public java.io.File getFilesDir() { return root; }
        });

        // ── Fixture: three audio clips, a visualizer on the middle one, a link group ──────
        FaditorProject p = new FaditorProject("Round trip");
        AudioClip a0 = audio("one.mp3", 0);
        AudioClip a1 = audio("two.mp3", 60_000);
        AudioClip a2 = audio("three.mp3", 120_000);
        p.getTimeline().addAudioClip(a0, false);
        p.getTimeline().addAudioClip(a1, false);
        p.getTimeline().addAudioClip(a2, false);
        WaveformOverlayInstance viz = new WaveformOverlayInstance("neon_bars");
        viz.setAudioSourceRef(a1.getId());
        viz.setTimeRange(60_000, 120_000);
        p.getTimeline().addWaveformOverlay(viz);
        LinkGroup g = new LinkGroup("group-audio");
        g.properties.add(LinkedProperty.TIME);
        g.members.add(new LinkMember("audioClip", a0.getId(), false));
        g.members.add(new LinkMember("audioClip", a2.getId(), false));
        p.getTimeline().addLinkGroup(g);

        // ── 1. The owner's symptom: save -> load -> save is byte-identical ────────────────
        String save1 = ps.toJson(p);
        FaditorProject q = ps.fromJson(save1);
        check(q != null, "project reloads");
        if (q == null) { System.out.println("\n1 FAILED (cannot continue)"); System.exit(1); }
        String save2 = ps.toJson(q);
        // lastModified is a clock reading every save rewrites (the loader touches it too), so
        // it is stripped exactly as ExportManager.projectContentKey strips it. NOTHING else is.
        String body1 = withoutClock(save1), body2 = withoutClock(save2);
        check(body1.equals(body2), "save -> load -> save writes the identical file");
        if (!body1.equals(body2)) printDivergence(body1, body2);

        // ── 2. Each audio id survives, in order ──────────────────────────────────────────
        check(audios(q).size() == 3, "all three audio clips reload");
        boolean sameIds = audios(q).size() == 3
                && audios(q).get(0).getId().equals(a0.getId())
                && audios(q).get(1).getId().equals(a1.getId())
                && audios(q).get(2).getId().equals(a2.getId());
        check(sameIds, "every audio clip keeps its id across the reload");

        // ── 3. The mirrored layer items (layers.audioTracks[].items[]) keep id+payloadId ─
        JsonObject tl1 = JsonParser.parseString(save1).getAsJsonObject().getAsJsonObject("timeline");
        JsonObject tl2 = JsonParser.parseString(save2).getAsJsonObject().getAsJsonObject("timeline");
        JsonElement items1 = tl1.getAsJsonObject("layers").get("audioTracks");
        JsonElement items2 = tl2.getAsJsonObject("layers").get("audioTracks");
        check(items1 != null && items1.equals(items2),
                "layers.audioTracks items (id + payloadId) are unchanged");
        boolean payloadsResolve = true;
        int payloadCount = 0;
        for (JsonElement t : items2.getAsJsonArray()) {
            JsonObject tj = t.getAsJsonObject();
            if (!tj.has("items")) continue;
            for (JsonElement it : tj.getAsJsonArray("items")) {
                JsonObject ij = it.getAsJsonObject();
                if (!ij.has("payloadId")) continue;
                payloadCount++;
                if (!hasAudio(q, ij.get("payloadId").getAsString())) payloadsResolve = false;
            }
        }
        check(payloadCount == 3 && payloadsResolve,
                "every audio layer item's payloadId names a live audio clip");

        // ── 4. References by id still resolve after the reload ───────────────────────────
        WaveformOverlayInstance qViz = q.getTimeline().getWaveformOverlays().get(0);
        check(a1.getId().equals(qViz.getAudioSourceRef()) && hasAudio(q, qViz.getAudioSourceRef()),
                "visualizer's audioSourceRef still names its audio clip");
        q.getTimeline().pruneLinkGroups();   // what every sync / load / export runs
        List<LinkGroup> qGroups = q.getTimeline().getLinkGroups();
        boolean groupAlive = qGroups.size() == 1 && qGroups.get(0).members.size() == 2
                && hasAudio(q, qGroups.get(0).members.get(0).id)
                && hasAudio(q, qGroups.get(0).members.get(1).id);
        check(groupAlive, "link group of two audio clips survives prune after reload");

        // ── 5. Old / malformed files: no id -> fresh id; repeated id -> not shared ────────
        JsonObject legacy = JsonParser.parseString(save1).getAsJsonObject();
        JsonArray arr = legacy.getAsJsonObject("timeline").getAsJsonArray("audioClips");
        arr.get(0).getAsJsonObject().remove("id");
        arr.get(2).getAsJsonObject().addProperty("id", a1.getId());
        FaditorProject r = ps.fromJson(legacy.toString());
        check(r != null && audios(r).size() == 3, "file with missing / repeated ids still loads all clips");
        if (r != null && audios(r).size() == 3) {
            String r0 = audios(r).get(0).getId(), r1 = audios(r).get(1).getId(),
                    r2 = audios(r).get(2).getId();
            check(r0 != null && !r0.isEmpty() && !r0.equals(a0.getId()),
                    "clip with no saved id gets a fresh one");
            check(r1.equals(a1.getId()), "first holder of a repeated id keeps it");
            check(!r2.equals(r1) && !r2.equals(r0), "second holder of a repeated id gets its own");
        }

        // ── 6. Fresh clips and copies still mint new ids (split relies on this) ──────────
        AudioClip copy = new AudioClip(a0);
        check(!copy.getId().equals(a0.getId()), "copy constructor still mints a fresh id");
        check(!audio("x.mp3", 0).getId().equals(audio("x.mp3", 0).getId()),
                "two new clips never share an id");

        System.out.println(fails == 0 ? "\nALL PASS" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
