import com.fadcam.ui.faditor.model.*;
import com.fadcam.ui.faditor.project.ProjectStorage;
import java.nio.file.Files;

public class SavedTimelineContentTest extends WorkLossStorageTest {
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("saved-timeline").toFile();
        for (String mode : new String[]{"PiP", "adjustment", "blank", "legacy PiP", "legacy adjustment"}) {
            ProjectStorage disk = storage();
            FaditorProject project = video("Older cut");
            disk.save(project);
            Clip clip = project.getTimeline().getClip(0);
            project.getTimeline().removeClip(0);
            if (mode.endsWith("PiP")) project.getTimeline().addOverlayClip(clip);
            if (mode.endsWith("adjustment")) project.getTimeline().addAdjustmentLayer(new AdjustmentLayer());
            project.setName("New cut");
            disk.saveAsync(project);
            disk.flushPendingWrites();
            if (mode.startsWith("legacy")) {
                // toJson produces interchange data, without the disk writer's empty-save marker.
                Files.write(new java.io.File(projectDir(project.getId()), "project.json").toPath(),
                        disk.toJson(project).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            FaditorProject back = storage().load(project.getId());
            check(back != null && "New cut".equals(back.getName()) && back.getTimeline().getClipCount() == 0,
                    mode + " reopens the newest edit");
        }
        System.out.println("SavedTimelineContentTest: " + passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
