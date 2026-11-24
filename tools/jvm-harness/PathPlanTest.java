import com.fadcam.ui.faditor.model.path.PathItem;
import com.fadcam.ui.faditor.model.path.PathPlan;
import com.fadcam.ui.faditor.model.path.StudioPath;

/**
 * STU-5 primitives step 3 (hidden): the preview and the export draw a path from ONE plan, so the
 * look they agree on is pinned here. The preview frame and the export frame differ only in size; the
 * plan at each size must be the same drawing, scaled. Run: {@code bash tools/jvm-harness/run-pathplan.sh}
 */
public class PathPlanTest {

    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] args) {
        PathItem badge = new PathItem(StudioPath.circle(0.5f, 0.5f, 0.2f, 0.2f), 0, 5000);
        badge.setStrokeWidth(0.01f);
        badge.setStrokeColor(0xFFFFFFFF);
        badge.setFillColor(0x80F43F8E);

        PathPlan preview = PathPlan.of(badge, 720, 1280);    // the editor's preview surface
        PathPlan export = PathPlan.of(badge, 1080, 1920);    // a 1080p export of the same 9:16 canvas
        PathPlan big = PathPlan.of(badge, 2160, 3840);       // 4K

        check("the same number of outline points at every size", preview.points.length == export.points.length
                && export.points.length == big.points.length && preview.points.length > 100);
        boolean scaled = true;
        for (int i = 0; i < preview.points.length; i++) {
            float k = 1080f / 720f;
            if (Math.abs(preview.points[i] * k - export.points[i]) > 0.01f) { scaled = false; break; }
        }
        check("the export draws the preview's outline, scaled point for point", scaled);
        check("the line width follows the frame height (12.8 px in the preview, 19.2 px in the export)",
                Math.abs(preview.strokePx - 12.8f) < 1e-3f && Math.abs(export.strokePx - 19.2f) < 1e-3f
                        && Math.abs(big.strokePx - 38.4f) < 1e-3f);
        check("the same colours on both", preview.strokeColor == export.strokeColor && preview.fillColor == export.fillColor
                && export.fillColor == 0x80F43F8E);
        check("a closed path is closed in both", preview.closed && export.closed);

        PathItem arc = new PathItem(StudioPath.arc(0.5f, 0.5f, 0.3f, 180f, 180f), 0, 5000);
        arc.setFillColor(0xFF000000);
        check("an open path is never filled, even with a fill colour set", PathPlan.of(arc, 1080, 1920).fillColor == 0);
        PathItem invisible = new PathItem(StudioPath.line(0.1f, 0.1f, 0.9f, 0.9f), 0, 1000);
        invisible.setStrokeWidth(0f);
        check("no line and no fill draws nothing", PathPlan.of(invisible, 1080, 1920).isEmpty());
        check("an empty path draws nothing", PathPlan.of(new PathItem(new StudioPath(), 0, 1000), 1080, 1920).isEmpty());
        PathPlan tl = PathPlan.of(new PathItem(StudioPath.line(0f, 0f, 1f, 1f), 0, 1000), 1080, 1920);
        check("canvas fractions map to pixels: (0,0) top left, (1,1) bottom right",
                tl.points[0] == 0f && tl.points[1] == 0f
                        && Math.abs(tl.points[tl.points.length - 2] - 1080f) < 1e-3f
                        && Math.abs(tl.points[tl.points.length - 1] - 1920f) < 1e-3f);
        check("an item is on screen from its start, not at its end", badge.activeAt(0) && badge.activeAt(4999) && !badge.activeAt(5000));

        // Both surfaces must go through the one routine once they are wired (still hidden today).
        String draw = "";
        try {
            draw = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "app/src/main/java/com/fadcam/ui/faditor/overlay/PathDraw.java")), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) { }
        check("the one draw routine reads the plan, and nothing else decides the look",
                draw.contains("PathPlan.of(item, frameW, frameH)"));
        check("paths stay hidden", !PathItem.ENABLED);

        System.out.println("PathPlanTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
