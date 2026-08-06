package com.fadcam.ui.faditor.sprite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Sequence DETECTION (SPEC_IMAGE_SEQUENCE §3a): given the file the user picked and the names of
 * its siblings, recognise the numbered run it belongs to.
 *
 * <p><b>The governing instruction is "OFFER, never assume."</b> The spec names the exact failure
 * this must not commit: {@code IMG_0001 … IMG_0400} in a holiday folder matches the pattern
 * perfectly and is not a sequence. So this class only ever produces a CANDIDATE with a count for
 * the user to accept or decline — there is no code path here that imports anything, and callers
 * are expected to show {@link Candidate#count} before proceeding.</p>
 *
 * <p>Pure and Android-free so the whole detection rule is JVM-testable; the caller supplies the
 * sibling names from whatever document API it has.</p>
 */
public final class SequenceDetector {

    private SequenceDetector() {}

    /**
     * Most frames a single detection will offer.
     *
     * <p>§3a says to cap what is offered. This is high enough for real rendered animation (240
     * frames is ten seconds at 24fps, the spec's own example) and low enough that pointing at a
     * camera roll produces a proposal the user can still reason about rather than a wall.</p>
     */
    public static final int MAX_OFFERED = 600;

    /** Below this, a "run" is just some files that happen to have numbers in them. */
    public static final int MIN_RUN = 2;

    /** A detected run, ready to be OFFERED. Never acted on without confirmation. */
    public static final class Candidate {
        /** Frame names in numeric order, including the picked one. */
        @NonNull public final List<String> names;
        /** Shared text before the number, e.g. {@code "shot_"}. */
        @NonNull public final String stem;
        /** Shared extension including the dot, e.g. {@code ".png"}, or empty. */
        @NonNull public final String extension;
        /** True when the run was truncated at {@link #MAX_OFFERED}. */
        public final boolean truncated;
        /** How many frames the folder actually holds, before the cap. */
        public final int totalFound;

        Candidate(@NonNull List<String> names, @NonNull String stem, @NonNull String extension,
                  boolean truncated, int totalFound) {
            this.names = names;
            this.stem = stem;
            this.extension = extension;
            this.truncated = truncated;
            this.totalFound = totalFound;
        }

        public int count() { return names.size(); }

        /** The wording §3a asks for: <i>"We found 240 images in this sequence"</i>. */
        @NonNull
        public String describe() {
            if (truncated) {
                return String.format(Locale.US,
                        "We found %d images in this sequence — offering the first %d",
                        totalFound, names.size());
            }
            return String.format(Locale.US, "We found %d images in this sequence", names.size());
        }
    }

    /** A filename split into the parts detection cares about. */
    static final class Parsed {
        @NonNull final String stem;
        final long number;
        final int digits;
        @NonNull final String extension;

        Parsed(@NonNull String stem, long number, int digits, @NonNull String extension) {
            this.stem = stem;
            this.number = number;
            this.digits = digits;
            this.extension = extension;
        }
    }

    /**
     * Split {@code name} into stem + trailing number + extension, or null when it has no trailing
     * number before the extension (a file that cannot be part of a run).
     */
    @Nullable
    static Parsed parse(@Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        int dot = name.lastIndexOf('.');
        // A leading dot is a hidden file, not an extension.
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int end = base.length();
        int i = end;
        while (i > 0 && Character.isDigit(base.charAt(i - 1))) i--;
        if (i == end) return null;              // no trailing digits
        String digits = base.substring(i);
        // A number long enough to overflow is not a frame index; treat it as un-numbered rather
        // than throwing on someone's oddly named file.
        if (digits.length() > 18) return null;
        return new Parsed(base.substring(0, i), Long.parseLong(digits), digits.length(), ext);
    }

    /**
     * Detect the run containing {@code pickedName} among {@code siblingNames}.
     *
     * @param pickedName   the file the user actually chose; always included in the result
     * @param siblingNames every name in the same folder (order irrelevant; may include the pick)
     * @return the candidate run, or null when there is no run to offer — which is the common and
     *         correct outcome for a single image, and means the caller should import one still.
     */
    @Nullable
    public static Candidate detect(@Nullable String pickedName,
                                   @Nullable List<String> siblingNames) {
        Parsed pick = parse(pickedName);
        if (pick == null || siblingNames == null) return null;

        List<Parsed> run = new ArrayList<>();
        List<String> runNames = new ArrayList<>();
        for (String n : siblingNames) {
            Parsed p = parse(n);
            if (p == null) continue;
            if (!p.stem.equals(pick.stem)) continue;
            // Extension must match: shot_001.png and shot_001.txt are not the same run, and a
            // folder holding both a PNG and a JSON sidecar per frame is a normal export layout.
            if (!p.extension.equalsIgnoreCase(pick.extension)) continue;
            run.add(p);
            runNames.add(n);
        }
        if (!runNames.contains(pickedName)) {
            run.add(pick);
            runNames.add(pickedName);
        }
        if (runNames.size() < MIN_RUN) return null;

        // Sort NUMERICALLY, not lexically: frame_9 must precede frame_10, and the whole point of
        // reading the trailing digits is to get that right.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < run.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> {
            int c = Long.compare(run.get(a).number, run.get(b).number);
            // Ties can only come from differently padded spellings of one number (frame_1 vs
            // frame_01). Order them by name so the result is at least deterministic.
            return c != 0 ? c : runNames.get(a).compareTo(runNames.get(b));
        });

        List<String> sorted = new ArrayList<>(order.size());
        for (Integer i : order) sorted.add(runNames.get(i));

        int totalFound = sorted.size();
        boolean truncated = totalFound > MAX_OFFERED;
        if (truncated) sorted = new ArrayList<>(sorted.subList(0, MAX_OFFERED));

        return new Candidate(sorted, pick.stem, pick.extension, truncated, totalFound);
    }

    /**
     * True when {@code name} ends in a number before its extension — i.e. it could be a frame of
     * some run. Callers use it to pick a SEED for {@link #detect} out of a folder listing.
     */
    public static boolean isNumbered(@Nullable String name) {
        return parse(name) != null;
    }

    /**
     * Image extensions a sequence frame may have. Used to filter a folder listing BEFORE
     * detection, so a stray {@code shot_003.txt} cannot join the run.
     */
    public static boolean isImageName(@Nullable String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".gif")
                || n.endsWith(".heic") || n.endsWith(".heif");
    }
}
