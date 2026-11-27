import cc.joycreator.avatar.draw.CharacterGlSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * X3 (AVA-1): the phone draws a character with the SAME shader text as the web pilot. Reads tools/avatarlab/al-gl.js, joins the
 * quoted pieces of VS, FS and FS2, and checks they equal CharacterGlSource.VS, FS and FS_TONE character for character. Then a
 * planted fault (one character changed) must be caught. Run: bash tools/jvm-harness/run-avatar-draw.sh
 */
public class CharacterGlSourceTest {
    public static void main(String[] a) throws Exception {
        String js = new String(Files.readAllBytes(Paths.get("tools/avatarlab/al-gl.js")), StandardCharsets.UTF_8);
        int pass = 0, fail = 0;
        String[][] pairs = {{"VS", CharacterGlSource.VS}, {"FS", CharacterGlSource.FS}, {"FS2", CharacterGlSource.FS_TONE}};
        for (String[] p : pairs) {
            String pilot = piece(js, p[0]);
            boolean same = pilot != null && pilot.equals(p[1]);
            System.out.println((same ? "ok   " : "FAIL ") + p[0] + ": " + (pilot == null ? "not found in al-gl.js" : same ? pilot.length() + " characters, identical" : "differs at " + firstDiff(pilot, p[1])));
            if (same) pass++; else fail++;
        }
        /* planted fault: the mask gain changed in one place */
        String bad = CharacterGlSource.FS.replace("*2.2", "*2.0");
        boolean caught = !bad.equals(piece(js, "FS"));
        System.out.println((caught ? "ok   " : "FAIL ") + "planted fault caught: the mask's soft edge gain changed (2.2 to 2.0)");
        if (caught) pass++; else fail++;
        System.out.println((fail == 0 ? "PASSED " : "FAILED ") + pass + " checks, " + fail + " failures");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** The value of {@code var NAME = '...' + '...';} in the pilot (single-quoted pieces, no escapes in shader text). */
    static String piece(String js, String name) {
        Matcher m = Pattern.compile("var " + name + " = ((?:'[^']*'\\s*\\+?\\s*)+);").matcher(js);
        if (!m.find()) return null;
        StringBuilder b = new StringBuilder();
        Matcher q = Pattern.compile("'([^']*)'").matcher(m.group(1));
        while (q.find()) b.append(q.group(1));
        return b.toString();
    }

    static int firstDiff(String x, String y) { int n = Math.min(x.length(), y.length()); for (int i = 0; i < n; i++) if (x.charAt(i) != y.charAt(i)) return i; return n; }
}
