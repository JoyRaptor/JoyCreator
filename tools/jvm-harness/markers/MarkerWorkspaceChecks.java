package com.fadcam.ui.faditor.markers;

import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dependency-free focused checks; assertions are counted for a concise run summary. */
public final class MarkerWorkspaceChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        deleteTree(root);
        require(root.mkdirs(), "create temporary project root");
        legacyMigration(root);
        codecAndUnknownMetadata(root);
        invalidAndFutureFiles(root);
        snapshotUndoResurrection(root);
        loopPolicy();
        exportPlan();
        System.out.println("MARKER JVM CHECKS OK: " + checks + " assertions");
    }

    private static void legacyMigration(File root) {
        File project = new File(root, "legacy");
        MarkerWorkspace.State migrated = MarkerWorkspaceStore.load(project,
                Arrays.asList(1500L, 500L, 1500L, -1L, null));
        equal(2, migrated.points.size(), "legacy offsets deduplicated and invalid values ignored");
        equal(500L, migrated.sortedPoints().get(0).timeMs, "legacy offsets sorted by time");
        require(!MarkerWorkspaceStore.fileFor(project).exists(), "migration load does not write");
        require(MarkerWorkspaceStore.save(project, migrated), "save migrated legacy points");
        MarkerWorkspace.State reopened = MarkerWorkspaceStore.load(project, null);
        equal(2, reopened.points.size(), "saved migrated points reopen from sidecar");
        equal(migrated.sortedPoints().get(0).id, reopened.sortedPoints().get(0).id,
                "stable migrated ID roundtrip");
    }

    private static void codecAndUnknownMetadata(File root) throws Exception {
        File project = new File(root, "metadata");
        require(project.mkdirs(), "create metadata project");
        String seeded = "{\"schemaVersion\":1,\"futureRoot\":{\"x\":7},"
                + "\"points\":[{\"id\":\"p1\",\"name\":\"Cue\",\"timeMs\":1250,\"color\":2,\"vendor\":\"point-meta\"}],"
                + "\"regions\":[{\"id\":\"r1\",\"name\":\"Cut\",\"inMs\":1000,\"outMs\":4000,\"color\":3,\"exportEnabled\":true,\"vendor\":{\"tag\":\"region-meta\"}}],"
                + "\"activeRegionId\":\"r1\",\"loopEnabled\":true}";
        Files.write(MarkerWorkspaceStore.fileFor(project).toPath(), seeded.getBytes(StandardCharsets.UTF_8));
        MarkerWorkspace.State state = MarkerWorkspaceStore.load(project, null);
        equal("p1", state.points.get(0).id, "point identity loaded");
        equal("r1", state.regions.get(0).id, "region identity loaded");
        equal("Cue", state.points.get(0).name, "point name loaded");
        equal(1250L, state.points.get(0).timeMs, "point time loaded");
        equal(2, state.points.get(0).color, "point color loaded");
        equal(3, state.regions.get(0).color, "region color loaded");
        require(state.regions.get(0).exportEnabled, "region export flag loaded");
        equal("r1", state.activeRegionId, "active region loaded");
        require(state.loopEnabled, "loop setting loaded");
        state.points.get(0).name = "Renamed";
        require(MarkerWorkspaceStore.save(project, state), "save edited metadata state");
        JSONObject out = new JSONObject(new String(Files.readAllBytes(
                MarkerWorkspaceStore.fileFor(project).toPath()), StandardCharsets.UTF_8));
        equal(7, out.getJSONObject("futureRoot").getInt("x"), "unknown root field retained");
        equal("point-meta", out.getJSONArray("points").getJSONObject(0).getString("vendor"),
                "unknown point metadata retained");
        equal("region-meta", out.getJSONArray("regions").getJSONObject(0).getJSONObject("vendor").getString("tag"),
                "unknown region metadata retained");
        MarkerWorkspace.State reopened = MarkerWorkspaceStore.load(project, null);
        equal("Renamed", reopened.points.get(0).name, "edited name roundtrip");
        equal(4000L, reopened.regions.get(0).outMs, "region endpoint roundtrip");
    }

    private static void invalidAndFutureFiles(File root) throws Exception {
        File invalidProject = new File(root, "invalid");
        require(invalidProject.mkdirs(), "create invalid project");
        File invalid = MarkerWorkspaceStore.fileFor(invalidProject);
        String malformed = "{definitely-not-json";
        Files.write(invalid.toPath(), malformed.getBytes(StandardCharsets.UTF_8));
        MarkerWorkspace.State bad = MarkerWorkspaceStore.load(invalidProject, Arrays.asList(20L));
        require(bad.points.isEmpty(), "malformed file does not fall back to legacy data");
        require(!MarkerWorkspaceStore.save(invalidProject, bad), "malformed file state is read-only");
        equal(malformed, new String(Files.readAllBytes(invalid.toPath()), StandardCharsets.UTF_8),
                "malformed authoritative bytes preserved");

        File futureProject = new File(root, "future");
        require(futureProject.mkdirs(), "create future project");
        File future = MarkerWorkspaceStore.fileFor(futureProject);
        String futureJson = "{\"schemaVersion\":99,\"payload\":\"keep\"}";
        Files.write(future.toPath(), futureJson.getBytes(StandardCharsets.UTF_8));
        MarkerWorkspace.State newer = MarkerWorkspaceStore.load(futureProject, null);
        require(!MarkerWorkspaceStore.save(futureProject, newer), "future schema remains read-only");
        equal(futureJson, new String(Files.readAllBytes(future.toPath()), StandardCharsets.UTF_8),
                "future schema bytes preserved");

        File failureProject = new File(root, "failed-write");
        MarkerWorkspace.State baseline = new MarkerWorkspace.State();
        baseline.points.add(new MarkerWorkspace.Point("Saved", 222L, 1));
        require(MarkerWorkspaceStore.save(failureProject, baseline), "write baseline before injected failure");
        byte[] before = Files.readAllBytes(MarkerWorkspaceStore.fileFor(failureProject).toPath());
        baseline.points.get(0).name = "Must not commit";
        AtomicFile.failNextFinishWrite = true;
        require(!MarkerWorkspaceStore.save(failureProject, baseline), "injected atomic commit reports failure");
        require(Arrays.equals(before, Files.readAllBytes(MarkerWorkspaceStore.fileFor(failureProject).toPath())),
                "failed save restores previous complete bytes");
    }

    private static void snapshotUndoResurrection(File root) throws Exception {
        File project = new File(root, "undo");
        require(project.mkdirs(), "create undo project");
        Files.write(MarkerWorkspaceStore.fileFor(project).toPath(),
                ("{\"schemaVersion\":1,\"historyField\":\"keep\",\"points\":["
                        + "{\"id\":\"p-delete\",\"name\":\"Delete me\",\"timeMs\":80,\"color\":1,\"extra\":\"restore\"}],"
                        + "\"regions\":[],\"activeRegionId\":null,\"loopEnabled\":false}")
                        .getBytes(StandardCharsets.UTF_8));
        MarkerWorkspace.State original = MarkerWorkspaceStore.load(project, null);
        String snapshot = MarkerWorkspaceStore.toSnapshotJson(original);
        require(snapshot != null, "complete state gets undo snapshot");
        MarkerWorkspace.State deleted = original.copy();
        deleted.points.clear();
        require(MarkerWorkspaceStore.save(project, deleted), "persist deletion");
        MarkerWorkspace.State canonical = MarkerWorkspaceStore.load(project, null);
        MarkerWorkspace.State restored = MarkerWorkspaceStore.fromSnapshotJson(snapshot, canonical);
        require(restored != null, "valid undo snapshot restores");
        equal(1, restored.points.size(), "deleted point restored from snapshot");
        require(MarkerWorkspaceStore.save(project, restored), "save restored snapshot");
        JSONObject restoredJson = new JSONObject(new String(Files.readAllBytes(
                MarkerWorkspaceStore.fileFor(project).toPath()), StandardCharsets.UTF_8));
        equal("restore", restoredJson.getJSONArray("points").getJSONObject(0).getString("extra"),
                "deleted identity metadata restored too");
        equal("keep", restoredJson.getString("historyField"), "snapshot carries root metadata on resurrection");
        require(MarkerWorkspaceStore.fromSnapshotJson("not json", canonical) == null,
                "invalid undo payload rejected");
        MarkerWorkspace.State readOnly = new MarkerWorkspace.State();
        readOnly.storageReadOnly = true;
        require(MarkerWorkspaceStore.fromSnapshotJson(snapshot, readOnly) == null,
                "canonical read-only state blocks undo payload application");
    }

    private static void loopPolicy() {
        MarkerWorkspace.Region region = new MarkerWorkspace.Region("Loop", 100L, 500L, 0);
        MarkerWorkspace.State state = new MarkerWorkspace.State();
        state.regions.add(region);
        state.activeRegionId = region.id;
        state.loopEnabled = true;
        MarkerLoopPolicy.Range range = MarkerLoopPolicy.effectiveRange(state, 1000L);
        equal(400L, range.durationMs(), "effective range duration");
        require(range.contains(100L) && range.contains(499L), "loop includes In and excludes Out boundary");
        require(!range.contains(500L), "Out is exclusive");
        equal(100L, MarkerLoopPolicy.restartAt(state, 500L, 1000L, true, false), "playback wraps at Out");
        equal(MarkerLoopPolicy.NO_RESTART, MarkerLoopPolicy.restartAt(state, 500L, 1000L, false, false),
                "paused playback does not restart");
        equal(MarkerLoopPolicy.NO_RESTART, MarkerLoopPolicy.restartAt(state, 500L, 1000L, true, true),
                "active gesture suppresses loop restart");
        equal(100L, MarkerLoopPolicy.restartAt(state, 50L, 1000L, true, false), "before In wraps to In");
        equal(300L, MarkerLoopPolicy.effectiveRange(region, 300L).outMs, "range clips to shorter timeline");
        equal(null, MarkerLoopPolicy.effectiveRange(region, 90L), "region beyond timeline is unavailable");
        region.inMs = 500L;
        region.outMs = 100L;
        equal(null, MarkerLoopPolicy.effectiveRange(region, 1000L), "reversed region cannot loop");
        state.loopEnabled = false;
        equal(MarkerLoopPolicy.NO_RESTART, MarkerLoopPolicy.restartAt(state, 800L, 1000L, true, false),
                "disabled loop does not restart");
    }

    private static void exportPlan() {
        List<MarkerWorkspace.Region> regions = new ArrayList<>();
        MarkerWorkspace.Region first = new MarkerWorkspace.Region(" /Cut:* ", 100L, 600L, 1);
        first.exportEnabled = true;
        MarkerWorkspace.Region second = new MarkerWorkspace.Region("cut", 500L, 2000L, 2);
        second.exportEnabled = true;
        MarkerWorkspace.Region disabled = new MarkerWorkspace.Region("Skip", 0L, 800L, 0);
        regions.add(first);
        regions.add(second);
        regions.add(disabled);
        RegionExportPlan plan = RegionExportPlan.create(regions, 1200L, " Project: A ");
        equal(2, plan.getJobs().size(), "only enabled regions generate jobs");
        equal(100L, plan.getJobs().get(0).getStartMs(), "first range start preserved");
        equal(600L, plan.getJobs().get(0).getEndMs(), "first range end preserved");
        equal(500L, plan.getJobs().get(1).getStartMs(), "second range start preserved");
        equal(1200L, plan.getJobs().get(1).getEndMs(), "range clipped to project duration");
        equal("Project A - Cut", plan.getJobs().get(0).getOutputFileName(), "sanitized project/range name");
        equal("Project A - cut (2)", plan.getJobs().get(1).getOutputFileName(), "case-insensitive unique output name");
        equal(100L, first.inMs, "plan construction does not mutate source In");
        equal(600L, first.outMs, "plan construction does not mutate source Out");
        equal(2000L, second.outMs, "clipping does not mutate saved endpoint");
        require(second.exportEnabled, "plan does not mutate export selection");
        equal(0, RegionExportPlan.create(Arrays.asList(disabled), 1200L, "").getJobs().size(),
                "no selected regions yields no jobs");
        MarkerWorkspace.Region shortRange = new MarkerWorkspace.Region("Short", 0L, 99L, 0);
        shortRange.exportEnabled = true;
        expectIllegal(() -> RegionExportPlan.create(Arrays.asList(shortRange), 1000L, ""),
                "sub-100ms range rejected");
        expectIllegal(() -> RegionExportPlan.create(regions, 0L, ""), "nonpositive duration rejected");
    }

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }
    private static void expectIllegal(Runnable action, String message) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message + " (no IllegalArgumentException)");
    }
    private static void deleteTree(File file) throws Exception {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        Files.delete(file.toPath());
    }
}
