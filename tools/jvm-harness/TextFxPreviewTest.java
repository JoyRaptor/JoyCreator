import java.nio.file.Files;
import java.nio.file.Path;

/**
 * STU-C6 (11-28, Note 9): a text with an object effect (Brightness / Exposure -3) showed no effect in the preview,
 * paused or playing, while the export darkened it. Every effect on a text: the composite hid the text's plain view
 * (it asks wantsGlExport, as the export does) but TextOverlayLayer.fxPipFor answered nothing for a text, so the words
 * were drawn plain. The preview must composite the text's own picture with its effects, and that picture must be the
 * export's own drawing, not a second one.
 *
 * Usage: java TextFxPreviewTest [source root]   (default: the working tree; give an old checkout to see it fail)
 */
public class TextFxPreviewTest {
    static int failed = 0, ran = 0;
    static void check(boolean ok, String what) {
        ran++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failed++;
    }

    static String body(String src, String name) {
        int at = src.indexOf(" " + name + "(");
        while (at >= 0) {
            int lineStart = src.lastIndexOf('\n', at) + 1;
            String head = src.substring(lineStart, at);
            if (head.contains("private") || head.contains("public")) break;
            at = src.indexOf(" " + name + "(", at + 1);
        }
        if (at < 0) return null;
        int open = src.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        String layer = Files.readString(root.resolve("app/src/main/java/com/fadcam/ui/faditor/overlay/TextOverlayLayer.java"));
        String effect = Files.readString(root.resolve("app/src/main/java/com/fadcam/ui/faditor/export/TextFxGlEffect.java"));
        Path rasterPath = root.resolve("app/src/main/java/com/fadcam/ui/faditor/export/TextFxRaster.java");

        String fx = body(layer, "fxPipFor");
        check(fx != null && !fx.contains("|| !o.isImage()) return null"),
                "the preview does not turn a text away from the GL composite (it hid its plain view and drew nothing)");
        String textPath = body(layer, "textFxPipFor");
        check(textPath != null && textPath.contains("hasActiveFx") && textPath.contains("o.getFx()"),
                "a text with effects is composited with its own effect stack");
        check(textPath != null && textPath.contains("TextFxRaster"), "the preview's picture of a text is the export's drawing");
        check(Files.exists(rasterPath), "the text's full-frame drawing lives in one place (TextFxRaster)");
        String getBitmap = body(effect, "getBitmap");
        check(getBitmap != null && getBitmap.contains("TextFxRaster.draw") && !getBitmap.contains("TextOverlayRenderer.render"),
                "the export draws a text with effects through the same TextFxRaster");
        System.out.println("TextFxPreview: " + (ran - failed) + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
