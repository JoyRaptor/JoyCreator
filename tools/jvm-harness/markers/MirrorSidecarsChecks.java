package com.fadcam.joyfolder.studio;

import com.fadcam.ui.faditor.markers.MarkerWorkspace;
import com.fadcam.ui.faditor.markers.MarkerWorkspaceStore;
import com.fadcam.ui.faditor.project.BookmarkStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * The Codex S2 round trip with the production classes: a project with marks, regions and a loop
 * goes into a .joyproject's block and comes back into a clean folder, with the same pretty printer
 * the mirror writes through. Run by run-mirror-sidecars.sh.
 */
public final class MirrorSidecarsChecks {
    private static final Gson MIRROR_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static int passes, fails;

    private static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    private static void write(File file, String text) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** The block as the folder copy would hold it: written out and read back through text. */
    private static JsonObject throughFile(File projectDir) {
        JsonObject root = new JsonObject();
        JsonObject block = new JsonObject();
        MirrorSidecars.embed(block, projectDir, () -> null);
        root.add("joyFolder", block);
        return JsonParser.parseString(MIRROR_GSON.toJson(root)).getAsJsonObject().getAsJsonObject("joyFolder");
    }

    /** As restoreMissing does: the folder first. */
    private static void restoreInto(JsonObject block, File dir) throws Exception {
        dir.mkdirs();
        MirrorSidecars.restore(block, dir);
    }

    private static MarkerWorkspace.State sample() {
        MarkerWorkspace.State state = new MarkerWorkspace.State();
        state.points.add(new MarkerWorkspace.Point("Chorus", 1500L, 2));
        state.points.add(new MarkerWorkspace.Point("joy:Clips/looks like a link.mp4", 4000L, 1));
        MarkerWorkspace.Region region = new MarkerWorkspace.Region();
        region.id = "region-one";
        region.name = "Intro";
        region.inMs = 1000L;
        region.outMs = 6000L;
        region.color = 3;
        region.exportEnabled = true;
        state.regions.add(region);
        state.activeRegionId = "region-one";
        state.loopEnabled = true;
        state.normalize(-1L);
        return state;
    }

    private static void roundTrip(File root) throws Exception {
        File source = new File(root, "source"), clean = new File(root, "clean");
        source.mkdirs();
        MarkerWorkspace.State state = sample();
        check(MarkerWorkspaceStore.save(source, state), "the marks saved");
        BookmarkStore.save(source, Arrays.asList(1500L, 4000L));
        restoreInto(throughFile(source), clean);
        check(MarkerWorkspaceStore.fileFor(clean).isFile(), "the sidecar is back in a clean folder");

        MarkerWorkspace.State back = MarkerWorkspaceStore.load(clean, BookmarkStore.load(clean));
        check(MarkerWorkspaceStore.toSnapshotJson(back) != null, "the restored marks are readable");
        check(back.points.size() == 2 && back.regions.size() == 1, "every mark and region came back");
        check(back.activeRegionId.equals("region-one") && back.loopEnabled, "the loop setting came back");
        MarkerWorkspace.Region r = back.regions.get(0);
        check(r.name.equals("Intro") && r.inMs == 1000L && r.outMs == 6000L && r.color == 3 && r.exportEnabled,
                "the region's name, span, colour and export pick came back");
        boolean names = false;
        for (MarkerWorkspace.Point p : back.points) names |= p.name.startsWith("joy:Clips/") && p.timeMs == 4000L;
        check(names, "a mark named like a file link keeps its name");
        check(BookmarkStore.load(clean).equals(Arrays.asList(1500L, 4000L)), "the plain bookmark list came back");
    }

    private static void newerFieldsSurvive(File root) throws Exception {
        File source = new File(root, "newer"), clean = new File(root, "newer-clean");
        write(MarkerWorkspaceStore.fileFor(source), "{\"schemaVersion\":9,\"points\":[],\"regions\":[],"
                + "\"loopEnabled\":false,\"activeRegionId\":null,\"fromTheFuture\":{\"keep\":[1,2.50,true]},"
                + "\"bigNumber\":12345678901234567890}");
        restoreInto(throughFile(source), clean);
        JsonObject back = JsonParser.parseString(read(MarkerWorkspaceStore.fileFor(clean))).getAsJsonObject();
        check(back.get("schemaVersion").getAsInt() == 9, "a newer file keeps its version");
        check(back.getAsJsonObject("fromTheFuture").getAsJsonArray("keep").get(1).getAsString().equals("2.50"),
                "unknown fields come back digit for digit");
        check(back.get("bigNumber").getAsString().equals("12345678901234567890"), "long numbers are not rounded");
        check(MarkerWorkspaceStore.toSnapshotJson(MarkerWorkspaceStore.load(clean, null)) == null,
                "a newer file stays read-only, as it was");
    }

    private static void oldProjectsAndOrder(File root) throws Exception {
        File plain = new File(root, "plain"), plainClean = new File(root, "plain-clean");
        plain.mkdirs();
        JsonObject none = throughFile(plain);
        check(!none.has(MirrorSidecars.MARKS) && !none.has(MirrorSidecars.BOOKMARKS), "no sidecars add nothing");
        restoreInto(none, plainClean);
        MirrorSidecars.restore(null, plainClean);
        check(!MarkerWorkspaceStore.fileFor(plainClean).exists(), "an older .joyproject restores as before");

        File legacy = new File(root, "legacy"), legacyClean = new File(root, "legacy-clean");
        BookmarkStore.save(legacy, Arrays.asList(700L));
        restoreInto(throughFile(legacy), legacyClean);
        check(BookmarkStore.load(legacyClean).equals(Arrays.asList(700L)), "a bookmarks-only project keeps its list");

        File joybot = new File(root, "joybot"), joybotClean = new File(root, "joybot-clean");
        joybot.mkdirs();
        MarkerWorkspaceStore.save(joybot, sample());
        BookmarkStore.save(joybot, Arrays.asList(1500L, 4000L, 9000L));
        MarkerWorkspaceStore.fileFor(joybot).setLastModified(1_000_000_000_000L);
        BookmarkStore.fileFor(joybot).setLastModified(1_000_000_060_000L);
        restoreInto(throughFile(joybot), joybotClean);
        check(BookmarkStore.fileFor(joybotClean).lastModified() > MarkerWorkspaceStore.fileFor(joybotClean).lastModified(),
                "a list written after the marks stays the newer one, so the editor still reconciles it");

        File marksLast = new File(root, "marks-last"), marksClean = new File(root, "marks-clean");
        marksLast.mkdirs();
        BookmarkStore.save(marksLast, Arrays.asList(1500L));
        MarkerWorkspaceStore.save(marksLast, sample());
        BookmarkStore.fileFor(marksLast).setLastModified(1_000_000_000_000L);
        MarkerWorkspaceStore.fileFor(marksLast).setLastModified(1_000_000_060_000L);
        restoreInto(throughFile(marksLast), marksClean);
        check(BookmarkStore.fileFor(marksClean).lastModified() <= MarkerWorkspaceStore.fileFor(marksClean).lastModified(),
                "marks written last stay in charge");
    }

    private static void damagedFilesKeepTheBackup(File root) throws Exception {
        File dir = new File(root, "damaged");
        dir.mkdirs();
        MarkerWorkspaceStore.save(dir, sample());
        JsonObject good = throughFile(dir);

        write(MarkerWorkspaceStore.fileFor(dir), "{ this is not json");
        JsonObject next = new JsonObject();
        MirrorSidecars.embed(next, dir, () -> good);
        check(next.getAsJsonObject(MirrorSidecars.MARKS) != null
                && next.getAsJsonObject(MirrorSidecars.MARKS).getAsJsonArray("regions").size() == 1,
                "a damaged sidecar never replaces the good backup of it");
        check(read(MarkerWorkspaceStore.fileFor(dir)).startsWith("{ this"), "the damaged file itself stays untouched");

        JsonObject nothing = new JsonObject();
        MirrorSidecars.embed(nothing, dir, () -> null);
        check(!nothing.has(MirrorSidecars.MARKS), "damaged with no backup yet: nothing is invented");

        write(MarkerWorkspaceStore.fileFor(dir), "[1,2,3]");
        JsonObject notAnObject = new JsonObject();
        MirrorSidecars.embed(notAnObject, dir, () -> good);
        check(notAnObject.has(MirrorSidecars.MARKS), "valid JSON of the wrong shape counts as damaged");
    }

    private static void restoreStopsBeforeTheProjectExists(File root) throws Exception {
        File source = new File(root, "stop"), blocked = new File(root, "stop-target");
        source.mkdirs();
        MarkerWorkspaceStore.save(source, sample());
        write(blocked, "a file where the project's folder should be");
        boolean threw = false;
        try {
            MirrorSidecars.restore(throughFile(source), blocked);
        } catch (java.io.IOException expected) {
            threw = true;
        }
        check(threw, "a sidecar that cannot be written stops the restore, so project.json is never committed");
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("mirror-sidecars").toFile();
        roundTrip(root);
        newerFieldsSurvive(root);
        oldProjectsAndOrder(root);
        damagedFilesKeepTheBackup(root);
        restoreStopsBeforeTheProjectExists(root);
        System.out.println("MirrorSidecarsChecks: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
