package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;

import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A5 AI rigging (PLAN_AVATAR_STUDIO §"AI rigging"): the BUILT-IN GENERALIZED BIPED
 * TEMPLATE the model emits rig JSON against, and the canonical part-name vocabulary
 * {@link AvatarRigValidator} checks against.
 *
 * <p>Canonical biped part ids — {@code head / body / armL / armR / handL / handR /
 * mouth} — with a body-rooted hierarchy, default anchors, a 3×3 head pose grid
 * (yaw×pitch), and empty 1-D limb strips (the plan's "1D_ANGLE" domains). The grid
 * and strips define the DOMAINS; their extreme cells are left UNAUTHORED (empty-cell
 * inheritance, A1) — the AI lands the structure, the human arms the extremes in
 * Avatar Studio.</p>
 *
 * <p>Pure builder over {@link AvatarRig} (no Android deps); {@link #templateJson()}
 * is just the builder's {@code toJson()}, so the template and the runtime schema can
 * never drift.</p>
 */
public final class AvatarRigTemplates {

    private AvatarRigTemplates() {}

    /** The biped part vocabulary the validator accepts (order = draw/read order). */
    public static final Set<String> CANONICAL_PART_IDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "body", "head", "armL", "armR", "handL", "handR", "mouth")));

    /**
     * Build the canonical biped rig. All parts draw from {@code sheetId} by default
     * (the model may re-point individual parts to other project sheets). The head
     * gets a 3×3 yaw×pitch grid; each arm and the body get an empty 5-cell angle
     * strip. No extreme cells are authored — the domains exist, the poses are the
     * user's to set.
     */
    @NonNull
    public static AvatarRig bipedTemplate(@NonNull String name, @NonNull String sheetId) {
        AvatarRig rig = AvatarRig.create(name);

        // ── Parts (body is root; everything hangs off it) ──
        rig.getParts().add(part("body", sheetId, null, 0.5f, 0.85f, 0));
        rig.getParts().add(part("head", sheetId, "body", 0.5f, 0.9f, 2));
        rig.getParts().add(part("mouth", sheetId, "head", 0.5f, 0.5f, 3));
        rig.getParts().add(part("armL", sheetId, "body", 0.85f, 0.15f, 1));
        rig.getParts().add(part("armR", sheetId, "body", 0.15f, 0.15f, 1));
        rig.getParts().add(part("handL", sheetId, "armL", 0.5f, 0.1f, 1));
        rig.getParts().add(part("handR", sheetId, "armR", 0.5f, 0.1f, 1));

        // ── Head: 2-D yaw×pitch extreme grid (3×3), cells unauthored ──
        AvatarRig.PoseDomain head = new AvatarRig.PoseDomain("head");
        head.driverX = "yaw";
        head.driverY = "pitch";
        head.cols = 3;
        head.rows = 3;
        rig.getDomains().add(head);

        // ── Limb + body: 1-D 5-cell angle strips, cells unauthored ──
        rig.getDomains().add(strip("armL", "angle"));
        rig.getDomains().add(strip("armR", "angle"));
        rig.getDomains().add(strip("body", "angle"));

        return rig;
    }

    /** The template rig JSON the model authors against (schema == runtime schema). */
    @NonNull
    public static JsonObject templateJson() {
        return bipedTemplate("Biped", "REPLACE_WITH_A_PROJECT_SHEET_ID").toJson();
    }

    // ── Builders ──────────────────────────────────────────────────────────

    private static AvatarRig.Part part(@NonNull String id, @NonNull String sheetId,
                                       String parentId, float ax, float ay, int z) {
        AvatarRig.Part p = new AvatarRig.Part(id, sheetId);
        p.parentId = parentId;
        p.anchorX = ax;
        p.anchorY = ay;
        p.z = z;
        return p;
    }

    private static AvatarRig.PoseDomain strip(@NonNull String id, @NonNull String driver) {
        AvatarRig.PoseDomain d = new AvatarRig.PoseDomain(id);
        d.driverX = driver;
        d.driverY = null;
        d.cols = 5;
        d.rows = 1;
        return d;
    }
}
