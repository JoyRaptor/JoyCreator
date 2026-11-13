package com.fadcam.ui.faditor.ai.joybot;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A Joybot job the phone killed mid-way carries on where it ran (BOT-A16): the REAL JobFile and
 * JoybotAgent restore on the bench's Android shim (real files, real AtomicFile). It lives in
 * Joybot's package because JobFile's save/find are package-private. Run:
 *   bash tools/jvm-harness/run-joybot-bench.sh --resume
 */
public final class JobResumeE2E {
    static int pass, fail;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS " + what); }
        else { fail++; System.out.println("FAIL " + what + "   " + detail); }
    }

    static JoybotAgent.Ask ask(String projectId, IdeaEngine.Room room, String text) {
        JoybotAgent.Situation s = new JoybotAgent.Situation();
        s.room = room;
        return new JoybotAgent.Ask(projectId, text, "test/model", "", "Joybot: " + text, s);
    }

    static List<JSONObject> convo(String said) throws Exception {
        List<JSONObject> h = new ArrayList<>();
        h.add(new JSONObject().put("role", "system").put("content", "you are joybot"));
        h.add(new JSONObject().put("role", "user").put("content", said));
        h.add(new JSONObject().put("role", "user").put("content", "Tool result:\nTurned bloom up to 0.8."));
        return h;
    }

    static void wipe(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File x : in) wipe(x);
        f.delete();
    }

    public static void main(String[] a) throws Exception {
        File dir = new File("tools/jvm-harness/out-joybot-runs/resume");
        wipe(dir);
        android.content.Context ctx = new android.content.Context(new File(dir, "files"),
                new File(System.getProperty("bench.assets")));

        // 1. A VisLab job (no project) is checkpointed and found again after a "kill".
        JoybotAgent.Ask vis = ask(null, IdeaEngine.Room.VISLAB, "make it pump harder on the kick");
        JobFile.save(ctx, vis, 2, convo(vis.text));
        JobFile.Interrupted j = JobFile.find(ctx);
        check("VisLab job saved and found", j != null, "");
        check("its key is the VisLab room's conversation", j != null && "room:VISLAB".equals(j.key), j == null ? "" : j.key);
        check("its room is VISLAB, no project", j != null && "VISLAB".equals(j.room) && j.projectId == null, "");
        check("its rounds and words kept", j != null && j.round == 2 && vis.text.equals(j.text), "");

        // 2. A quick question in another room must not wipe it; its own end does.
        JobFile.clearIf(ctx, JoybotAgent.conversationKey("p-other", IdeaEngine.Room.STUDIO));
        check("another conversation's end leaves it", JobFile.find(ctx) != null, "");
        JobFile.clearIf(ctx, vis.key());
        check("its own end clears it", JobFile.find(ctx) == null, "");

        // 3. Carry on puts the conversation back into the VisLab room's history.
        JobFile.save(ctx, vis, 3, convo(vis.text));
        j = JobFile.find(ctx);
        List<JSONObject> h = JoybotAgent.history(JoybotAgent.conversationKey(null, IdeaEngine.Room.VISLAB));
        synchronized (h) { h.clear(); }
        JoybotAgent.restore(ctx, j);
        h = JoybotAgent.history(JoybotAgent.conversationKey(null, IdeaEngine.Room.VISLAB));
        check("restore fills the VisLab conversation", h.size() == 3
                && h.get(2).optString("content").contains("bloom"), String.valueOf(h.size()));
        check("restore forgets the file", JobFile.find(ctx) == null, "");
        check("carry-on reads the look first", JobKey.checkFirst(j.room).startsWith("Read the look first"), "");

        // 4. JoyBrush and TextLab too; a lobby question is not kept.
        JobFile.save(ctx, ask(null, IdeaEngine.Room.JOYBRUSH, "add a layer for colour"), 1, convo("add a layer"));
        j = JobFile.find(ctx);
        check("JoyBrush job kept", j != null && "room:JOYBRUSH".equals(j.key), j == null ? "" : j.key);
        JobFile.clear(ctx);
        JobFile.save(ctx, ask(null, IdeaEngine.Room.TEXTLAB, "make it gold"), 1, convo("make it gold"));
        j = JobFile.find(ctx);
        check("TextLab job kept", j != null && "room:TEXTLAB".equals(j.key), j == null ? "" : j.key);
        JobFile.clear(ctx);
        JobFile.save(ctx, ask(null, IdeaEngine.Room.LOBBY, "what can you do"), 1, convo("what can you do"));
        check("a lobby question is not kept", JobFile.find(ctx) == null, "");

        // 5. A Studio job still works, and a job file from before 11-13 (project id only) still opens.
        JobFile.save(ctx, ask("bench-proj", IdeaEngine.Room.STUDIO, "make the title red"), 1, convo("red"));
        j = JobFile.find(ctx);
        check("Studio job kept with its project", j != null && "bench-proj".equals(j.projectId)
                && "project:bench-proj".equals(j.key) && "STUDIO".equals(j.room), j == null ? "" : j.key);
        check("Studio carry-on words unchanged", "Check the project first — some of it is already done."
                .equals(JobKey.checkFirst(j.room)), "");
        File jf = new File(new File(ctx.getFilesDir(), "joybot"), "job.json");
        JSONObject old = new JSONObject(JobFile.read(jf));
        old.remove("key");
        java.nio.file.Files.write(jf.toPath(), old.toString().getBytes("UTF-8"));
        j = JobFile.find(ctx);
        check("an old file (project id only) opens as a Studio job", j != null && "project:bench-proj".equals(j.key), "");

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
