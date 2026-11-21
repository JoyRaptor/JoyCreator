import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.fadcam.ui.faditor.audio.StudioVoiceRender;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;

/** Drives the production file-URI render method, with desktop FFmpeg behind the Android bridge. */
public class VoiceRenderCacheTest {
    static final Context app = new Context();
    static final File project = new File("project");
    static final StudioVoiceRender.Listener listener = new StudioVoiceRender.Listener() {
        public void progress(int percent) {}
        public void finished(File file, Exception error) {}
    };

    static File render(File src, long start, long end) throws Exception {
        Method m = StudioVoiceRender.class.getDeclaredMethod("render", Context.class, File.class,
                Uri.class, long.class, long.class, CancellationSignal.class, StudioVoiceRender.Listener.class);
        m.setAccessible(true);
        return (File)m.invoke(null, app, project, new Uri(src), start, end, new CancellationSignal(), listener);
    }

    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        File quiet = new File("quiet.wav"), noisy = new File("noisy.wav");
        if (args.length > 0 && args[0].equals("reopen")) {
            check(render(quiet, 0, 16000) == null, "reopened quiet selection");
            check(FFmpegKitConfig.decodes == 0 && FFprobeKit.calls == 0, "receipt across process restart");
            return;
        }
        check(render(quiet, 0, 16000) == null, "quiet input should keep its source");
        int cold = FFmpegKitConfig.decodes, probes = FFprobeKit.calls;
        check(cold == 4, "three samples and whole-selection confirmation, got " + cold);
        check(render(quiet, 0, 16000) == null, "repeat should keep source");
        int warm = FFmpegKitConfig.decodes - cold;
        System.out.println("quiet selection: " + cold + " cold decodes, " + warm + " repeat decodes");
        check(warm == 0 && FFprobeKit.calls == probes, "repeat launched a media job");
        File[] receipts = app.getCacheDir().listFiles((dir,name) -> name.endsWith(".quiet"));
        check(receipts != null && receipts.length == 1, "one receipt, no audio copy");
        Files.write(receipts[0].toPath(), new byte[] {'X'});
        check(render(quiet, 0, 16000) == null && FFmpegKitConfig.decodes == cold * 2, "damaged receipt reanalyzed");
        cold = FFmpegKitConfig.decodes;
        check(render(quiet, 1000, 15000) == null && FFmpegKitConfig.decodes > cold, "trim invalidation");
        int last = FFmpegKitConfig.decodes;
        check(quiet.setLastModified(quiet.lastModified()+60000), "change source revision");
        check(render(quiet, 0, 16000) == null && FFmpegKitConfig.decodes > last, "revision invalidation");
        File unknown = new File("undated.wav");
        Files.copy(quiet.toPath(), unknown.toPath());
        check(unknown.setLastModified(0), "clear revision");
        render(unknown,0,16000);
        last = FFmpegKitConfig.decodes;
        render(unknown,0,16000);
        check(FFmpegKitConfig.decodes > last, "unknown revision must be analyzed again");
        if (noisy.isFile()) {
            File clean = render(noisy,0,8000);
            check(clean != null && clean.isFile(), "rain should produce processed audio");
            last = FFmpegKitConfig.decodes;
            check(clean.equals(render(noisy,0,8000)) && FFmpegKitConfig.decodes == last, "reuse encoded rain");
            System.out.println("rain: processed eight seconds and reused the audio cache on repeat");
        }
    }
}
