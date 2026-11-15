import com.fadcam.ui.lobby.arrival.Flight;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joybot's arrival flight (BOT-5, owner's picks 15 Nov 2026), off device: every packed path with every
 * final approach, plus the hop, on a phone-sized and a tablet-sized window.
 *
 * <p>Checks what the owner ruled: he ends on his seat, upright, facing front, unsquashed; the reverse-
 * thrust burst never happens on a skim and is over before touchdown; no step jumps (a broken path or a
 * NaN); the flight takes a sensible time.</p>
 *
 * <p>Usage: {@code bash tools/jvm-harness/run-joybot-flight.sh}</p>
 */
public class JoybotFlightTest {

    private static int fails = 0;

    private static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        String j = new String(Files.readAllBytes(Paths.get("app/src/main/assets/joybot/paths.json")), StandardCharsets.UTF_8);
        List<float[]> paths = new ArrayList<>();
        int at = 0;
        while ((at = j.indexOf("\"uv\"", at)) >= 0) {
            int open = j.indexOf('[', at), depth = 0, end = open;
            for (int k = open; k < j.length(); k++) {
                char ch = j.charAt(k);
                if (ch == '[') depth++;
                else if (ch == ']' && --depth == 0) { end = k; break; }
            }
            Matcher n = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(j.substring(open, end + 1));
            List<Float> v = new ArrayList<>();
            while (n.find()) v.add(Float.parseFloat(n.group()));
            float[] p = new float[v.size()];
            for (int i = 0; i < p.length; i++) p[i] = v.get(i);
            paths.add(p);
            at = end;
        }
        check(paths.size() == 7, "seven paths (the owner's six and the dive and skim), got " + paths.size());
        // {button x, button y, seat x, seat floor y}, dp: a phone full sheet and a tablet card
        float[][] windows = {{330, 52, 42, 684}, {1450, 60, 1110, 900}};
        float peg = 32f;
        int flights = 0;
        for (float[] w : windows) {
            float ex = w[2], ey = w[3] - 4f - peg;
            for (int i = 0; i <= paths.size(); i++) {
                for (Flight.Approach ap : Flight.Approach.values()) {
                    float[] uv = i < paths.size() ? paths.get(i) : null;
                    String name = (uv == null ? "hop" : "path " + i) + " " + ap + " @" + (int) w[0];
                    for (boolean shorter : new boolean[]{false, true}) {
                        Flight f = new Flight(w[0], w[1], ex, ey, peg, uv, ap, Flight.Landing.DOUBLE, shorter);
                        Flight.Player pl = new Flight.Player(f);
                        Flight.Pose o = new Flight.Pose();
                        float px = Float.NaN, py = 0f;
                        long lastBurst = -1;
                        boolean nan = false;
                        float maxStep = 0f;
                        for (long t = 0; t <= f.tSettle() + 200; t += 8) {
                            pl.pose(t, o);
                            if (!o.visible) continue;
                            if (Float.isNaN(o.x) || Float.isNaN(o.y) || Float.isNaN(o.rot) || Float.isNaN(o.sx)) nan = true;
                            if (o.mode == Flight.Mode.BURST) lastBurst = t;
                            if (!Float.isNaN(px)) maxStep = Math.max(maxStep, (float) Math.hypot(o.x - px, o.y - py));
                            px = o.x;
                            py = o.y;
                        }
                        check(!nan, name + ": a NaN in his pose");
                        check(maxStep < 40f, name + ": jumps " + maxStep + " dp in 8 ms");
                        check(f.dur >= 400 && f.dur <= 4000, name + ": flight " + f.dur + " ms");
                        if (ap == Flight.Approach.SCOOT || uv == null) check(lastBurst < 0, name + ": a reverse-thrust burst on a skim");
                        if (lastBurst >= 0) check(lastBurst < f.tLand(), name + ": the burst runs to touchdown");
                        pl.pose(f.tSettle(), o);
                        check(Math.abs(o.x - ex) < .01f && Math.abs(o.y - ey) < .01f, name + ": ends at " + o.x + "," + o.y);
                        check(Math.abs(o.rot) < .01f, name + ": ends turned " + o.rot);
                        check(o.face == 1, name + ": ends facing away from the front");
                        check(Math.abs(o.sx - 1f) < .001f && Math.abs(o.sy - 1f) < .001f, name + ": ends squashed");
                        check(o.down, name + ": not down at settle");
                        flights++;
                    }
                    if (uv == null) break;
                }
            }
        }
        System.out.println(flights + " flights, " + (fails == 0 ? "all pass" : fails + " FAILED"));
        if (fails > 0) System.exit(1);
    }
}
