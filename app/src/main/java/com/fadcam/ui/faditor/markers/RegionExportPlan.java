package com.fadcam.ui.faditor.markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds the immutable, deterministic set of exports requested by saved regions. */
public final class RegionExportPlan {

    /** ExportManager rejects ranges shorter than 100 ms. */
    private static final long MIN_RANGE_MS = 100L;

    private final List<Job> jobs;

    private RegionExportPlan(List<Job> jobs) {
        this.jobs = Collections.unmodifiableList(new ArrayList<>(jobs));
    }

    /**
     * Creates jobs for enabled regions, clipping each interval to the project duration.
     * Output names are extension-free base names for ExportSettings.outputFileName.
     *
     * @param regions saved regions in their display/order sequence
     * @param projectDurationMs duration of the editor timeline
     * @param projectBaseName optional project name to prefix each output (may be blank)
     * @throws IllegalArgumentException if duration is invalid or an enabled region has no
     *                                  exportable overlap with the project
     */
    public static RegionExportPlan create(List<MarkerWorkspace.Region> regions,
                                          long projectDurationMs,
                                          String projectBaseName) {
        if (projectDurationMs <= 0L) {
            throw new IllegalArgumentException("Project duration must be positive");
        }
        List<Job> jobs = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        String prefix = sanitize(projectBaseName);
        if (regions != null) {
            for (MarkerWorkspace.Region region : regions) {
                if (region == null) {
                    throw new IllegalArgumentException("Region list contains null");
                }
                if (!region.exportEnabled) continue;

                long start = Math.max(0L, Math.min(region.inMs, region.outMs));
                long end = Math.min(projectDurationMs, Math.max(region.inMs, region.outMs));
                if (end - start < MIN_RANGE_MS) {
                    throw new IllegalArgumentException("Region '" + displayName(region)
                            + "' has no exportable range inside the project");
                }

                String regionName = sanitize(region.name);
                if (regionName.isEmpty()) regionName = "Region";
                String desiredName = prefix.isEmpty()
                        ? regionName : prefix + " - " + regionName;
                String outputName = uniqueName(desiredName, usedNames);
                jobs.add(new Job(region.copy(), start, end, outputName));
            }
        }
        return new RegionExportPlan(jobs);
    }

    public List<Job> getJobs() {
        return jobs;
    }

    /** Removes filesystem-invalid characters and trims surrounding whitespace. */
    public static String sanitize(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[/\\\\:*?\"<>|]", "").trim();
    }

    private static String uniqueName(String desired, Set<String> usedNames) {
        String candidate = desired;
        int suffix = 2;
        while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = desired + " (" + suffix++ + ")";
        }
        return candidate;
    }

    private static String displayName(MarkerWorkspace.Region region) {
        String name = sanitize(region.name);
        return name.isEmpty() ? "Region" : name;
    }

    /** One validated region export. Times are editor-timeline milliseconds, [start, end). */
    public static final class Job {
        private final MarkerWorkspace.Region region;
        private final long startMs;
        private final long endMs;
        private final String outputFileName;

        private Job(MarkerWorkspace.Region region, long startMs, long endMs,
                    String outputFileName) {
            this.region = region;
            this.startMs = startMs;
            this.endMs = endMs;
            this.outputFileName = outputFileName;
        }

        public MarkerWorkspace.Region getRegion() { return region.copy(); }
        public long getStartMs() { return startMs; }
        public long getEndMs() { return endMs; }
        public String getOutputFileName() { return outputFileName; }
    }
}
