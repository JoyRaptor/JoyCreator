import android.net.Uri;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Transition;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The starting projects, built with the app's own model classes and saved as the app saves them
 * (ProjectStorage.toJson), with readable ids ("t-title") in place of random ones so a task can name
 * its target. Run with --make-fixtures to rewrite fixtures/*.project.json; the runs only read them.
 */
final class Fixtures {

    private Fixtures() {}

    /** Writes every fixture into {@code dir}. */
    static void writeAll(android.content.Context ctx, File dir) throws Exception {
        dir.mkdirs();
        write(ctx, dir, "studio-basic", basic(false, false));
        write(ctx, dir, "studio-locked", basic(true, false));
        write(ctx, dir, "studio-two-logos", basic(false, true));
    }

    private static final class Built {
        final FaditorProject p;
        final Map<String, String> ids = new LinkedHashMap<>();
        Built(FaditorProject p) { this.p = p; }
    }

    /**
     * Two 10 s clips (beach, city) with a cross-dissolve on the cut; a "Title" text over the first
     * clip and a "draft" note over the whole video; a picture (logo); background music. Optionally
     * the title locked, or a second logo for the ambiguous-reference tasks.
     */
    private static Built basic(boolean lockTitle, boolean twoLogos) {
        FaditorProject p = new FaditorProject("Bench Trip");
        Built b = new Built(p);
        b.ids.put(p.getId(), "bench-proj");
        Clip beach = new Clip(Uri.parse("file:///bench/media/beach.mp4"), 10_000);
        beach.setDisplayName("beach.mp4");
        Clip city = new Clip(Uri.parse("file:///bench/media/city.mp4"), 10_000);
        city.setDisplayName("city.mp4");
        p.getTimeline().addClip(beach);
        p.getTimeline().addClip(city);
        b.ids.put(beach.getId(), "c-beach");
        b.ids.put(city.getId(), "c-city");
        p.getTimeline().addTransition(new Transition(Transition.Type.CROSS_DISSOLVE, 500, 0));

        TextOverlayItem title = new TextOverlayItem("Title", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        title.setTimeRange(0, 10_000);
        title.setLocked(lockTitle);
        p.getTimeline().addTextOverlay(title);
        b.ids.put(title.getId(), "t-title");

        TextOverlayItem note = new TextOverlayItem("draft", 0xFFFFFF00, 0.5f, 0.85f, 0.06f, 0f);
        note.setTimeRange(0, 20_000);
        p.getTimeline().addTextOverlay(note);
        b.ids.put(note.getId(), "t-draft");

        TextOverlayItem logo = TextOverlayItem.createImage(twoLogos ? "file:///bench/media/logo-light.png"
                : "file:///bench/media/logo.png", 0.85f, 0.15f, 0.2f);
        logo.setTimeRange(0, 20_000);
        p.getTimeline().addTextOverlay(logo);
        b.ids.put(logo.getId(), "i-logo");
        if (twoLogos) {
            TextOverlayItem dark = TextOverlayItem.createImage("file:///bench/media/logo-dark.png", 0.15f, 0.15f, 0.2f);
            dark.setTimeRange(0, 20_000);
            p.getTimeline().addTextOverlay(dark);
            b.ids.put(dark.getId(), "i-logo-dark");
        }

        AudioClip music = new AudioClip(Uri.parse("file:///bench/media/music.mp3"), 20_000);
        music.setLabel("music.mp3");
        p.getTimeline().addAudioClip(music);
        b.ids.put(music.getId(), "a-music");
        return b;
    }

    private static void write(android.content.Context ctx, File dir, String name, Built b) throws Exception {
        String json = new ProjectStorage(ctx).toJson(b.p);
        for (Map.Entry<String, String> e : b.ids.entrySet()) json = json.replace(e.getKey(), e.getValue());
        // Readable ids only: any other random id left in the file would differ on each rebuild.
        Files.write(new File(dir, name + ".project.json").toPath(), pretty(json).getBytes(StandardCharsets.UTF_8));
    }

    private static String pretty(String json) {
        com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(json);
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(e) + "\n";
    }
}
