import com.fadcam.ui.faditor.avatar.AvatarRig;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Real disk save/load regression: imported libraries need no timeline placement. */
public class ProjectAssetLibraryTest {
    private static int failures;

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "PASS  " : "FAIL  ") + message);
        if (!condition) failures++;
    }

    public static void main(String[] args) throws Exception {
        final Path root = Files.createTempDirectory("project-asset-library-");
        try {
            ProjectStorage storage = new ProjectStorage(new android.content.Context() {
                @Override public File getFilesDir() { return root.toFile(); }
            });
            FaditorProject project = new FaditorProject("Imported Joy Brush board");
            SpriteSheet sheet = new SpriteSheet("board-sheet", "Board cells", "");
            File projectDir = new File(root.toFile(), "faditor/projects/" + project.getId());
            if (!projectDir.mkdirs()) throw new java.io.IOException("Cannot create fixture directory");
            File asset = new File(projectDir, "board.png");
            Files.write(asset.toPath(), new byte[] {1, 2, 3});
            sheet.setSheetUri(android.net.Uri.fromFile(asset).toString());
            sheet.setGrid(2, 2);
            project.getSpriteSheets().add(sheet);
            check(project.getTimeline().isEmpty(), "fixture has no timeline placement");
            check(storage.save(project), "sheet-only project saves to disk");
            FaditorProject loaded = storage.load(project.getId());
            check(loaded != null && loaded.getTimeline().isEmpty(),
                    "sheet-only project reloads without inventing a timeline item");
            SpriteSheet restored = loaded == null ? null : loaded.spriteSheetById("board-sheet");
            check(restored != null && restored.getCols() == 2 && restored.getRows() == 2
                    && restored.getSheetUri().equals(sheet.getSheetUri()),
                    "sheet identity, grid, and durable asset URI survive reload");
            check(loaded != null && storage.save(loaded) && storage.load(project.getId()) != null,
                    "sheet library remains loadable after another save");

            // A damaged/empty main file must still recover the previously saved library.
            File main = new File(projectDir, "project.json");
            File backup = new File(main.getParentFile(), "project.json.bak");
            Files.copy(main.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(main.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
            FaditorProject recovered = storage.load(project.getId());
            check(recovered != null && recovered.spriteSheetById("board-sheet") != null,
                    "empty main file still falls back to the saved library backup");

            FaditorProject rigProject = new FaditorProject("Unplaced avatar rig");
            rigProject.getAvatarRigs().add(new AvatarRig("draft-rig", "Draft"));
            check(storage.save(rigProject), "rig-only project saves to disk");
            FaditorProject loadedRig = storage.load(rigProject.getId());
            check(loadedRig != null && loadedRig.getTimeline().isEmpty()
                    && loadedRig.avatarRigById("draft-rig") != null,
                    "unplaced rig library reloads");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(path);
                }
            }
        }
        if (failures != 0) throw new AssertionError(failures + " failed checks");
    }
}
