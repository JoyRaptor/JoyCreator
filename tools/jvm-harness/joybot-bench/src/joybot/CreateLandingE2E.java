package com.fadcam.ui.faditor.ai.joybot;

import cc.joycreator.joybrush.android.JoyBrushLaunch;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * BOT-7 create-tree landings, off device (queue job 10, the hover-1 lane 11-24): for every bubble that
 * opens JoyBrush, the answers a person gives walk to a landing, {@link CreateLanding#preset} turns it
 * into the launch preset, and the preset is what JoyBrush can actually use (nothing quietly dropped).
 * The bubbles that go to another room carry the right room and no JoyBrush board. Run:
 *   bash tools/jvm-harness/run-joybot-bench.sh --landings
 */
public final class CreateLandingE2E {
    static int pass, fail;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS " + what); }
        else { fail++; System.out.println("FAIL " + what + "   " + detail); }
    }

    /** Walks the tree for {@code kindId}, answering each question from {@code answers} (by Ask name). */
    static CreateTree.Landing walk(String kindId, Map<String, String> answers) {
        CreateTree.Flow f = new CreateTree.Flow(Collections.<String, Integer>emptyMap(),
                Collections.<CreateTree.Choice>emptyList());
        f.startingWith(CreateTree.kind(kindId), null);
        int guard = 0;
        while (!f.done() && guard++ < 8) {
            CreateTree.Question q = f.question();
            String a = answers.get(q.ask.name());
            if (a == null || !f.answer(a)) throw new IllegalStateException(kindId + ": no answer for " + q.ask);
        }
        return f.landing();
    }

    static Map<String, String> m(String... kv) {
        Map<String, String> r = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put(kv[i], kv[i + 1]);
        return r;
    }

    static void preset(String name, CreateTree.Landing l, String board, String aspect, Integer fps, Integer cols,
                       Integer rows, String guide, Integer cellPx, Integer tilePx) {
        JoyBrushLaunch.Preset p = CreateLanding.preset(l);
        JoyBrushLaunch.Preset u = p.usable();
        check(name + ": board " + board, board.equals(u.getBoard()), "got " + u.getBoard());
        check(name + ": shape " + aspect, aspect == null ? u.getAspect() == null : aspect.equals(u.getAspect()),
                "got " + u.getAspect() + " (asked " + p.getAspect() + ")");
        check(name + ": fps " + fps, fps == null ? u.getFps() == null : fps.equals(u.getFps()), "got " + u.getFps());
        check(name + ": grid " + cols + "x" + rows,
                (cols == null ? u.getColumns() == null : cols.equals(u.getColumns()))
                        && (rows == null ? u.getRows() == null : rows.equals(u.getRows())),
                "got " + u.getColumns() + "x" + u.getRows());
        check(name + ": guide " + guide, guide == null ? u.getGuide() == null : guide.equals(u.getGuide()),
                "got " + u.getGuide());
        check(name + ": cell px " + cellPx, cellPx == null ? u.getCellPx() == null : cellPx.equals(u.getCellPx()),
                "got " + u.getCellPx());
        check(name + ": tile px " + tilePx, tilePx == null ? u.getTilePx() == null : tilePx.equals(u.getTilePx()),
                "got " + u.getTilePx());
        check(name + ": JoyBrush finds nothing wrong with it", p.problem() == null, String.valueOf(p.problem()));
        check(name + ": nothing the person chose is dropped", p.equals(u), p + " vs " + u);
    }

    static void other(String name, String kindId, String room, Map<String, String> answers) {
        CreateTree.Landing l = walk(kindId, answers);
        check(name + ": room " + room, room.equals(l.kind.room), l.kind.room);
        JoyBrushLaunch.Preset p = CreateLanding.preset(l);
        check(name + ": no JoyBrush board is asked for", p.getBoard() == null && p.getGuide() == null,
                p.getBoard() + "/" + p.getGuide());
    }

    public static void main(String[] a) {
        preset("image 16:9", walk("paint", m("SHAPE", "16:9")), "image", "16:9", null, null, null, null, null, null);
        preset("image 3:1 typed", walk("paint", m("SHAPE", "3:1")), "image", "3:1", null, null, null, null, null, null);
        preset("perspective 2-point", walk("perspective", m("PERSPECTIVE", "2", "SHAPE", "4:3")),
                "image", "4:3", null, null, null, "perspective-2", null, null);
        preset("perspective 3-point", walk("perspective", m("PERSPECTIVE", "3", "SHAPE", "9:16")),
                "image", "9:16", null, null, null, "perspective-3", null, null);
        preset("animation 9:16 at 24", walk("animation", m("SHAPE", "9:16", "FPS", "24")),
                "animation", "9:16", 24, null, null, null, null, null);
        preset("tile 512", walk("tile", m("TILE", "512")), "tile", null, null, null, null, null, null, 512);
        // A non-square tile keeps its SHAPE (300x200 is 3:2): JoyBrush only takes small ratios.
        preset("tile 300x200", walk("tile", m("TILE", "300x200")), "tile", "3:2", null, null, null, null, null, null);
        preset("sprite 4x4 at 64", walk("sprite_new", m("GRID", "4x4@64")), "sprite", null, null, 4, 4, null, 64, null);
        preset("sprite 6x2 at 64 typed", walk("sprite_new", m("GRID", "6x2 64")), "sprite", null, null, 6, 2, null, 64, null);

        other("edit a sprite", "sprite_edit", "spritelab", m("SHEET", "all_sheets"));
        other("find", "find", "library", m("FIND", "recent"));
        for (String id : new String[]{"video", "audio", "music_visuals", "titles", "character"}) {
            CreateTree.Kind k = CreateTree.kind(id);
            check(id + ": a room is named", k != null && k.room != null && !k.room.isEmpty(), id);
        }
        check("every JoyBrush bubble says its room", "joybrush".equals(CreateTree.kind("paint").room)
                && "joybrush".equals(CreateTree.kind("perspective").room)
                && "joybrush".equals(CreateTree.kind("animation").room)
                && "joybrush".equals(CreateTree.kind("tile").room)
                && "joybrush".equals(CreateTree.kind("sprite_new").room), "");

        System.out.println("create landings: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
