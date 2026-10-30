package com.fadcam.ui.faditor.markers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Editable project bookmarks and named regions, independent of timeline/view lifetime. */
public final class MarkerWorkspace {
    private MarkerWorkspace() { }

    public static final class Point {
        public String id = UUID.randomUUID().toString();
        public String name = "Bookmark";
        public long timeMs;
        /** Palette index, shared with the playhead gradients (0..3). */
        public int color;

        public Point() { }
        public Point(String name, long timeMs, int color) {
            this.name = name;
            this.timeMs = timeMs;
            this.color = color;
        }
        public Point copy() {
            Point result = new Point(name, timeMs, color);
            result.id = id;
            return result;
        }
    }

    public static final class Region {
        public String id = UUID.randomUUID().toString();
        public String name = "Region";
        public long inMs;
        public long outMs;
        public int color;
        /** Export selection is independent of which region is active for looping. */
        public boolean exportEnabled;

        public Region() { }
        public Region(String name, long inMs, long outMs, int color) {
            this.name = name;
            this.inMs = inMs;
            this.outMs = outMs;
            this.color = color;
        }
        public Region copy() {
            Region result = new Region(name, inMs, outMs, color);
            result.id = id;
            result.exportEnabled = exportEnabled;
            return result;
        }
        public long durationMs() { return outMs > inMs ? outMs - inMs : 0L; }
    }

    public static final class State {
        public final List<Point> points = new ArrayList<>();
        public final List<Region> regions = new ArrayList<>();
        public String activeRegionId;
        public boolean loopEnabled;

        // Carry persistence safety through undo copies. The UI must not turn an unreadable or
        // future-format sidecar into a new empty v1 document merely by opening/closing the popup.
        boolean storageReadOnly;
        String originalJson;

        public State copy() {
            State result = new State();
            for (Point point : points) if (point != null) result.points.add(point.copy());
            for (Region region : regions) if (region != null) result.regions.add(region.copy());
            result.activeRegionId = activeRegionId;
            result.loopEnabled = loopEnabled;
            result.storageReadOnly = storageReadOnly;
            result.originalJson = originalJson;
            return result;
        }

        /**
         * Repair editable values without discarding marks after a timeline becomes shorter.
         * Positive offsets beyond {@code durationMs} deliberately remain stored: deleting a clip
         * and undoing it must not erase the owner's marks. Views clamp seeks/draw positions, and
         * playback/export must intersect regions with the current project duration.
         * Zero-duration regions are allowed while setting In/Out, but cannot enable looping.
         */
        public void normalize(long durationMs) {
            Set<String> ids = new HashSet<>();
            points.removeAll(java.util.Collections.singleton(null));
            regions.removeAll(java.util.Collections.singleton(null));
            for (Point point : points) {
                point.id = uniqueId(point.id, ids);
                point.name = point.name == null ? "Bookmark" : point.name;
                point.timeMs = Math.max(0L, point.timeMs);
                point.color = validColor(point.color);
            }
            for (Region region : regions) {
                region.id = uniqueId(region.id, ids);
                region.name = region.name == null ? "Region" : region.name;
                region.inMs = Math.max(0L, region.inMs);
                region.outMs = Math.max(0L, region.outMs);
                if (region.outMs < region.inMs) {
                    long previousIn = region.inMs;
                    region.inMs = region.outMs;
                    region.outMs = previousIn;
                }
                region.color = validColor(region.color);
            }
            Region active = activeRegion();
            if (active == null) activeRegionId = null;
            if (active == null || active.durationMs() == 0L
                    || (durationMs >= 0L && active.inMs >= durationMs)) {
                loopEnabled = false;
            }
        }

        public Region activeRegion() {
            if (activeRegionId == null) return null;
            for (Region region : regions) {
                if (region != null && activeRegionId.equals(region.id)) return region;
            }
            return null;
        }

        /** Sorted deep copies; reordering or editing a displayed snapshot cannot mutate state. */
        public List<Point> sortedPoints() {
            List<Point> result = new ArrayList<>();
            for (Point point : points) if (point != null) result.add(point.copy());
            result.sort(Comparator.comparingLong((Point point) -> point.timeMs));
            return result;
        }

        public List<Region> sortedRegions() {
            List<Region> result = new ArrayList<>();
            for (Region region : regions) if (region != null) result.add(region.copy());
            result.sort(Comparator.comparingLong((Region region) -> region.inMs)
                    .thenComparingLong(region -> region.outMs));
            return result;
        }
    }

    private static int validColor(int color) { return color >= 0 && color <= 3 ? color : 0; }
    private static String uniqueId(String proposed, Set<String> ids) {
        if (proposed == null || proposed.trim().isEmpty() || !ids.add(proposed)) {
            do { proposed = UUID.randomUUID().toString(); } while (!ids.add(proposed));
        }
        return proposed;
    }
}
