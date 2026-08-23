import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * C2.E — BakedAudioCache.loudnorm parsing against REAL ffmpeg pass-1 output.
 *
 * <p>The captured log files are produced on this machine by desktop ffmpeg 7.0.2 running
 * the exact filter strings BakedAudioCache issues (see tasks/SPEC row C2.E evidence).
 * parseMeasured is private static, so reflection reaches it without touching production
 * visibility; BakedAudioCache never constructs a Context here.</p>
 */
public class BakedAudioParseTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    public static void main(String[] args) throws Exception {
        Class<?> cache = Class.forName("com.fadcam.ui.faditor.audio.BakedAudioCache");
        Method parse = cache.getDeclaredMethod("parseMeasured", String.class);
        parse.setAccessible(true);
        Class<?> measured = Class.forName(
                "com.fadcam.ui.faditor.audio.BakedAudioCache$Measured");
        Method apply = measured.getDeclaredMethod("asApplyParams");
        apply.setAccessible(true);
        String[] fields = {"inputI", "inputTp", "inputLra", "inputThresh", "targetOffset"};

        // ── Real pass-1 log from a noisy tone ─────────────────────────────────────────
        String log = new String(Files.readAllBytes(Paths.get(args[0])),
                java.nio.charset.StandardCharsets.UTF_8);
        Object m = parse.invoke(null, log);
        check(m != null, "parseMeasured finds the JSON block in a real session log");
        if (m != null) {
            String[] want = {"-17.84", "-10.07", "0.00", "-27.84", "-0.01"};
            for (int i = 0; i < fields.length; i++) {
                java.lang.reflect.Field f = measured.getDeclaredField(fields[i]);
                f.setAccessible(true);
                check(want[i].equals(String.valueOf(f.get(m))),
                        fields[i] + " == " + want[i]);
            }
            String params = (String) apply.invoke(m);
            check(params.contains("measured_I=-17.84") && params.contains("linear=true")
                            && params.contains("offset=-0.01"),
                    "pass-2 param string carries measured_* + linear=true");
        }

        // ── Real pass-1 log over DIGITAL SILENCE (-inf must round-trip as a string) ───
        String slog = new String(Files.readAllBytes(Paths.get(args[1])),
                java.nio.charset.StandardCharsets.UTF_8);
        Object sm = parse.invoke(null, slog);
        check(sm != null, "silence log still yields a full measurement");
        if (sm != null) {
            java.lang.reflect.Field fi = measured.getDeclaredField("inputI");
            fi.setAccessible(true);
            check("-inf".equals(fi.get(sm)), "input_i -inf survives as a STRING");
            java.lang.reflect.Field fo = measured.getDeclaredField("targetOffset");
            fo.setAccessible(true);
            check("inf".equals(fo.get(sm)),
                    "UNSIGNED inf (silence target_offset) parses — the C2.E proof find");
            String sparams = (String) apply.invoke(sm);
            check(sparams.contains("measured_I=-inf") && sparams.contains("offset=inf"),
                    "infinities flow verbatim into pass-2 params (no double-parse)");
        }

        // ── Garbage must yield null, not an exception ─────────────────────────────────
        check(parse.invoke(null, "no json here at all") == null,
                "log without a JSON block -> null (caller fails loudly, no silent bake)");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
