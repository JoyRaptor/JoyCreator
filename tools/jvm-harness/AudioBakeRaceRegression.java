import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;

/** Exercises the production staging and publication helpers on a real filesystem. */
public final class AudioBakeRaceRegression {
    public static void main(String[] args) throws Exception {
        Class<?> cache = Class.forName("com.fadcam.ui.faditor.audio.BakedAudioCache");
        Method stage = cache.getDeclaredMethod("outTmp", File.class);
        Method publish = cache.getDeclaredMethod("commit", File.class, File.class);
        stage.setAccessible(true);
        publish.setAccessible(true);
        File dir = Files.createTempDirectory("audio-bake-race").toFile();
        File out = new File(dir, "mix.m4a");
        File a = (File) stage.invoke(null, out);
        File b = (File) stage.invoke(null, out);
        try {
            if (a.equals(b)) throw new AssertionError("two jobs share a writable temporary path");
            byte[] payload = {4, 3, 2, 1};
            Files.write(a.toPath(), payload);
            if (!(Boolean) publish.invoke(null,a,out)) throw new AssertionError("publish failed");
            if ((Boolean) publish.invoke(null,b,out)) throw new AssertionError("published missing file");
            if (!Arrays.equals(payload,Files.readAllBytes(out.toPath()))) throw new AssertionError("previous result disappeared");
            System.out.println("PASS: independent staging, failed publication preserves existing output");
        } finally {
            a.delete(); b.delete(); out.delete(); dir.delete();
        }
    }
}
