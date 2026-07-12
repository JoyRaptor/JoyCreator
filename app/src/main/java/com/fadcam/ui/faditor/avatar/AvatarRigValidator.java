package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A5 AI rigging: the tolerant, reject-WITH-REASONS validator for a model-authored
 * {@link AvatarRig} against the biped contract ({@link AvatarRigTemplates}). Runs
 * AFTER {@link AvatarRig#fromJson} (which already drops malformed elements) as the
 * SEMANTIC gate before {@code author_avatar_rig} proposes the insert.
 *
 * <p>Pure — no Android deps, so it is JVM-harness testable. Returns a list of
 * human-readable reasons (empty = valid) the AI can read back and fix, rather than
 * throwing: a bad rig should bounce back to the model, never crash the tool.</p>
 */
public final class AvatarRigValidator {

    private AvatarRigValidator() {}

    /**
     * @param rig           the parsed rig to check
     * @param knownSheetIds the project's SpriteSheet ids; when null the sheet-
     *                      existence check is skipped (harness / sheet-agnostic use),
     *                      but the empty-sheetId check still runs.
     * @return reasons the rig is invalid; empty list = accept.
     */
    @NonNull
    public static List<String> validate(@NonNull AvatarRig rig,
                                        @Nullable Set<String> knownSheetIds) {
        List<String> reasons = new ArrayList<>();

        List<AvatarRig.Part> parts = rig.getParts();
        if (parts.isEmpty()) {
            reasons.add("rig has no parts (expected biped parts like head/body/armL/…)");
            return reasons; // nothing else is checkable
        }

        Set<String> seenIds = new HashSet<>();
        for (AvatarRig.Part p : parts) {
            if (!seenIds.add(p.id)) {
                reasons.add("duplicate part id '" + p.id + "'");
            }
            if (!AvatarRigTemplates.CANONICAL_PART_IDS.contains(p.id)) {
                reasons.add("unknown part id '" + p.id + "' (expected one of "
                        + AvatarRigTemplates.CANONICAL_PART_IDS + ")");
            }
            if (p.sheetId == null || p.sheetId.isEmpty()) {
                reasons.add("part '" + p.id + "' has no sheetId");
            } else if (knownSheetIds != null && !knownSheetIds.contains(p.sheetId)) {
                reasons.add("part '" + p.id + "' references unknown sheet '" + p.sheetId
                        + "' (not in this project)");
            }
        }

        // Parent references + cycle guard (walk each part's parent chain; a chain
        // longer than the part count means a loop).
        for (AvatarRig.Part p : parts) {
            if (p.parentId == null) continue;
            if (rig.partById(p.parentId) == null) {
                reasons.add("part '" + p.id + "' has parent '" + p.parentId
                        + "' which does not exist");
                continue;
            }
            int hops = 0;
            String cur = p.parentId;
            while (cur != null && hops <= parts.size()) {
                AvatarRig.Part pa = rig.partById(cur);
                cur = pa == null ? null : pa.parentId;
                hops++;
            }
            if (hops > parts.size()) {
                reasons.add("part '" + p.id + "' is in a parent cycle");
            }
        }

        // Domains: grid sanity + in-bounds cells + poses referencing real parts.
        for (AvatarRig.PoseDomain d : rig.getDomains()) {
            if (d.cols < 1 || d.rows < 1) {
                reasons.add("domain '" + d.id + "' has invalid grid " + d.cols + "×" + d.rows);
                continue; // bounds checks below would be meaningless
            }
            for (AvatarRig.Cell c : d.cells) {
                if (c.col < 0 || c.col >= d.cols || c.row < 0 || c.row >= d.rows) {
                    reasons.add("domain '" + d.id + "' cell (" + c.col + "," + c.row
                            + ") is outside the " + d.cols + "×" + d.rows + " grid");
                }
                for (AvatarRig.PartPose pp : c.poses) {
                    if (rig.partById(pp.partId) == null) {
                        reasons.add("domain '" + d.id + "' cell (" + c.col + "," + c.row
                                + ") poses unknown part '" + pp.partId + "'");
                    }
                }
            }
        }

        return reasons;
    }
}
