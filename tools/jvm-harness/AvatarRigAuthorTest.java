import com.fadcam.ui.faditor.avatar.AvatarRig;
import com.fadcam.ui.faditor.avatar.AvatarRigTemplates;
import com.fadcam.ui.faditor.avatar.AvatarRigValidator;

import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JVM harness for A5 AI rigging (PLAN_A5_AI_RIGGING): the pure, network-free pieces
 * of author_avatar_rig — the built-in biped TEMPLATE (parses + round-trips via
 * AvatarRig.fromJson) and the reject-with-reasons VALIDATOR (accept the template;
 * red with the right reason on unknown-part / missing-sheet / malformed-domain /
 * dup-id / empty / bad-parent rigs). Same shape as ReplayMappingTest: plain main(),
 * check() helper, "ALL GREEN (n/n)".
 */
public class AvatarRigAuthorTest {

    private static final String SHEET = "sheet-1";
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        templateStructure();
        templateRoundTrip();
        templateJsonRoundTrip();
        validatorAcceptsTemplate();
        rejectEmptyParts();
        rejectUnknownPart();
        rejectMissingSheet();
        rejectDuplicateId();
        rejectBadParent();
        rejectMalformedDomainGrid();
        rejectCellOutOfBounds();
        rejectPoseUnknownPart();
        sheetCheckSkippedWhenNull();
        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    static Set<String> sheets() {
        Set<String> s = new HashSet<>();
        s.add(SHEET);
        return s;
    }

    // ── Template ──────────────────────────────────────────────────────────

    static void templateStructure() {
        AvatarRig rig = AvatarRigTemplates.bipedTemplate("Biped", SHEET);
        check("template has 7 parts", rig.getParts().size() == 7);
        check("template has 4 domains", rig.getDomains().size() == 4);
        check("template part 'head' exists", rig.partById("head") != null);
        check("template part 'mouth' exists", rig.partById("mouth") != null);
        check("template body is root", rig.partById("body").parentId == null);
        check("template head parents body", "body".equals(rig.partById("head").parentId));
        check("template handL parents armL", "armL".equals(rig.partById("handL").parentId));
        AvatarRig.PoseDomain head = null;
        for (AvatarRig.PoseDomain d : rig.getDomains()) if (d.id.equals("head")) head = d;
        check("head domain is 3x3", head != null && head.cols == 3 && head.rows == 3);
        check("head domain 2-D (yaw×pitch)",
                head != null && "yaw".equals(head.driverX) && "pitch".equals(head.driverY));
        check("head domain has no authored cells", head != null && head.cells.isEmpty());
    }

    static void templateRoundTrip() {
        AvatarRig rig = AvatarRigTemplates.bipedTemplate("Dino", SHEET);
        JsonObject json = rig.toJson();
        AvatarRig back = AvatarRig.fromJson(json);
        check("round-trip keeps 7 parts", back.getParts().size() == 7);
        check("round-trip keeps 4 domains", back.getDomains().size() == 4);
        check("round-trip keeps name", "Dino".equals(back.getName()));
        check("round-trip keeps anchors",
                back.partById("body").anchorX != null
                        && Math.abs(back.partById("body").anchorX - 0.5f) < 1e-4);
    }

    static void templateJsonRoundTrip() {
        JsonObject tj = AvatarRigTemplates.templateJson();
        AvatarRig back = AvatarRig.fromJson(tj);
        check("templateJson() parses via fromJson", back.getParts().size() == 7);
    }

    // ── Validator ─────────────────────────────────────────────────────────

    static void validatorAcceptsTemplate() {
        AvatarRig rig = AvatarRigTemplates.bipedTemplate("Biped", SHEET);
        List<String> reasons = AvatarRigValidator.validate(rig, sheets());
        check("validator accepts the template (no reasons)", reasons.isEmpty(),
                reasons.toString());
    }

    static void rejectEmptyParts() {
        AvatarRig rig = AvatarRig.create("Empty");
        List<String> reasons = AvatarRigValidator.validate(rig, sheets());
        check("empty rig rejected", contains(reasons, "no parts"));
    }

    static void rejectUnknownPart() {
        AvatarRig rig = AvatarRig.create("Weird");
        rig.getParts().add(new AvatarRig.Part("tentacle", SHEET));
        List<String> reasons = AvatarRigValidator.validate(rig, sheets());
        check("unknown part id rejected", contains(reasons, "unknown part id 'tentacle'"));
    }

    static void rejectMissingSheet() {
        AvatarRig rig = AvatarRig.create("NoSheet");
        rig.getParts().add(new AvatarRig.Part("head", "ghost-sheet"));
        List<String> reasons = AvatarRigValidator.validate(rig, sheets());
        check("unknown sheet rejected", contains(reasons, "unknown sheet 'ghost-sheet'"));
    }

    static void rejectDuplicateId() {
        AvatarRig rig = AvatarRig.create("Dup");
        rig.getParts().add(new AvatarRig.Part("head", SHEET));
        rig.getParts().add(new AvatarRig.Part("head", SHEET));
        List<String> reasons = AvatarRigValidator.validate(rig, sheets());
        check("duplicate part id rejected", contains(reasons, "duplicate part id 'head'"));
    }

    static void rejectBadParent() {
        AvatarRig rig = AvatarRig.create("Orphan");
        AvatarRig.Part head = new AvatarRig.Part("head", SHEET);
        head.parentId = "nobody";
        rig.getParts().add(head);
        List<String> reasons = AvatarRigValidator.validate(rig, sheets());
        check("nonexistent parent rejected", contains(reasons, "parent 'nobody'"));
    }

    static void rejectMalformedDomainGrid() {
        AvatarRig rig = AvatarRigTemplates.bipedTemplate("Bad", SHEET);
        AvatarRig.PoseDomain d = new AvatarRig.PoseDomain("armL2");
        d.cols = 0;
        d.rows = 1;
        rig.getDomains().add(d);
        List<String> reasons = AvatarRigValidator.validate(rig, sheets());
        check("invalid grid rejected", contains(reasons, "invalid grid"));
    }

    static void rejectCellOutOfBounds() {
        AvatarRig rig = AvatarRigTemplates.bipedTemplate("OOB", SHEET);
        AvatarRig.PoseDomain head = null;
        for (AvatarRig.PoseDomain d : rig.getDomains()) if (d.id.equals("head")) head = d;
        AvatarRig.Cell c = new AvatarRig.Cell();
        c.col = 5; // 3×3 grid → col 5 is out of bounds
        c.row = 0;
        head.cells.add(c);
        List<String> reasons = AvatarRigValidator.validate(rig, sheets());
        check("out-of-bounds cell rejected", contains(reasons, "is outside the"));
    }

    static void rejectPoseUnknownPart() {
        AvatarRig rig = AvatarRigTemplates.bipedTemplate("BadPose", SHEET);
        AvatarRig.PoseDomain head = null;
        for (AvatarRig.PoseDomain d : rig.getDomains()) if (d.id.equals("head")) head = d;
        AvatarRig.Cell c = new AvatarRig.Cell();
        c.col = 0;
        c.row = 0;
        c.poses.add(new AvatarRig.PartPose("ghostpart"));
        head.cells.add(c);
        List<String> reasons = AvatarRigValidator.validate(rig, sheets());
        check("pose referencing unknown part rejected",
                contains(reasons, "unknown part 'ghostpart'"));
    }

    static void sheetCheckSkippedWhenNull() {
        AvatarRig rig = AvatarRigTemplates.bipedTemplate("NoSheetCheck", "any-sheet");
        List<String> reasons = AvatarRigValidator.validate(rig, null);
        check("null knownSheetIds skips sheet-existence check", reasons.isEmpty(),
                reasons.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    static boolean contains(List<String> reasons, String needle) {
        for (String r : reasons) if (r.contains(needle)) return true;
        return false;
    }

    static void check(String label, boolean cond) { check(label, cond, ""); }

    static void check(String label, boolean cond, String extra) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + label + (extra.isEmpty() ? "" : " — " + extra));
        }
    }
}
