import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.bridge.BridgeAuth;
import com.fadcam.ui.faditor.ai.bridge.BridgeServer;
import com.fadcam.ui.faditor.ai.joybot.IdeaEngine;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The bring-your-own-AI bridge end to end on the PC (BOT-6): the REAL BridgeServer and Joybot's REAL
 * tools, spoken to over plain HTTP the way Claude Code does it. Run:
 *   bash tools/jvm-harness/run-joybot-bench.sh --bridge
 */
public final class BridgeE2E {
    static int pass, fail;
    static String base;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS " + what); }
        else { fail++; System.out.println("FAIL " + what + "   " + detail); }
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");   // so the test may send Origin
        File dir = new File("tools/jvm-harness/out-joybot-runs/bridge");
        deleteAll(dir);
        android.content.Context ctx = new android.content.Context(new File(dir, "files"),
                new File(System.getProperty("bench.assets")));
        ProjectStorage st = new ProjectStorage(ctx);
        String pid = load(st, "studio-basic");

        BridgeServer server = new BridgeServer(ctx, 0, new BridgeAuth("482913"), pid, IdeaEngine.Room.STUDIO, "test",
                (line, connected) -> System.out.println("  [phone] " + line));
        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        base = "http://127.0.0.1:" + server.getListeningPort() + "/mcp";

        // Who may come in
        check("no code: 401", post(null, null, "{}")[0].equals("401"), "");
        check("wrong code: 401", post("Bearer 111111", null, "{}")[0].equals("401"), "");
        check("a web page: 403", post("Bearer 482913", "http://evil.example", "{}")[0].equals("403"), "");
        String[] g = get("Bearer 482913");
        check("GET: 405 (no stream)", g[0].equals("405"), g[0] + " " + g[1]);

        // As Claude Code does it
        String[] r = rpc("initialize", new JSONObject().put("protocolVersion", "2025-06-18")
                .put("capabilities", new JSONObject()).put("clientInfo", new JSONObject().put("name", "claude-code").put("version", "2")));
        JSONObject init = new JSONObject(r[1]).getJSONObject("result");
        check("initialize over HTTP", "joycreator".equals(init.getJSONObject("serverInfo").getString("name")), r[1]);
        check("the phone knows who connected", "Claude Code".equals(server.who()), server.who());
        String[] n = post("Bearer 482913", null, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        check("notification: 202", n[0].equals("202"), n[0]);

        JSONArray tools = new JSONObject(rpc("tools/list", new JSONObject())[1]).getJSONObject("result").getJSONArray("tools");
        boolean where = false, title = false, plan = false;
        for (int i = 0; i < tools.length(); i++) {
            String name = tools.getJSONObject(i).getString("name");
            if (name.equals("where_am_i")) where = true;
            if (name.equals("set_text_style")) title = true;
            if (name.equals("propose_plan")) plan = true;
        }
        check("tools/list: Joybot's tools (" + tools.length() + ")", tools.length() > 50 && title, "");
        check("tools/list: where_am_i is there", where, "");
        check("tools/list: Joybot's own plan card is not", !plan, "");

        JSONObject w = call("where_am_i", new JSONObject());
        check("where_am_i reads the project", text(w).contains("t-title"), text(w));

        JSONObject red = call("set_text_style", new JSONObject().put("text", "t-title").put("color", "#FF0000"));
        check("set_text_style ran", !red.getBoolean("isError"), text(red));
        String json = st.toJson(st.load(pid));
        check("...and the project really changed", json.contains("-65536") || json.toUpperCase().contains("FF0000"), "");
        JSONObject big = call("set_text_style", new JSONObject().put("text", "t-title").put("size", 0.4));
        check("a second change in the same burst", !big.getBoolean("isError"), text(big));
        check("one burst = one undo step", AIChatState.stepCount(pid) == 1, String.valueOf(AIChatState.stepCount(pid)));

        JSONObject bad = call("set_text_style", new JSONObject().put("text", "no-such-title").put("color", "#00FF00"));
        check("a refusal comes back as isError", bad.getBoolean("isError"), text(bad));

        // Locks: nothing the computer sends counts as the person's yes (this fixture has the same id)
        String lockedPid = load(st, "studio-locked");
        BridgeServer locked = new BridgeServer(ctx, 0, new BridgeAuth("482913"), lockedPid, IdeaEngine.Room.STUDIO, "test",
                (line, connected) -> { });
        locked.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        String keep = base;
        base = "http://127.0.0.1:" + locked.getListeningPort() + "/mcp";
        rpc("initialize", new JSONObject().put("protocolVersion", "2025-06-18").put("clientInfo", new JSONObject().put("name", "codex")));
        String before = st.toJson(st.load(lockedPid));
        JSONObject move = call("set_layer_transform", new JSONObject().put("layer", "t-title").put("y", 0.1));
        check("a locked title: refused", move.getBoolean("isError"), text(move));
        JSONObject unlock = call("set_layer_flags", new JSONObject().put("layer", "t-title").put("locked", false));
        check("the computer can't unlock it either", unlock.getBoolean("isError"), text(unlock));
        String after = st.toJson(st.load(lockedPid));
        java.nio.file.Files.write(new File(dir, "locked-before.json").toPath(), before.getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.write(new File(dir, "locked-after.json").toPath(), after.getBytes(StandardCharsets.UTF_8));
        int at = 0;
        while (at < Math.min(before.length(), after.length()) && before.charAt(at) == after.charAt(at)) at++;
        // The save time may move (a load-and-save), the content may not.
        String stamp = "\"lastModified\"\\s*:\\s*\\d+";
        check("...so the locked project is untouched", before.replaceAll(stamp, "").equals(after.replaceAll(stamp, "")),
                "lengths " + before.length() + "/" + after.length() + ", differs at " + at + ": " + before.substring(Math.max(0, at - 60), Math.min(before.length(), at + 60))
                        + "  VS  " + after.substring(Math.max(0, at - 60), Math.min(after.length(), at + 60)));
        base = keep;

        server.stop();
        locked.stop();
        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail > 0 ? 1 : 0);
    }

    static String load(ProjectStorage st, String fixture) throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(new File(JoybotBench.HOME, "fixtures/" + fixture + ".project.json").toPath()),
                StandardCharsets.UTF_8);
        FaditorProject p = st.fromJson(json);
        if (p == null || !st.save(p)) throw new IllegalStateException("fixture " + fixture);
        return p.getId();
    }

    static int id = 1;

    static String[] rpc(String method, JSONObject params) throws Exception {
        return post("Bearer 482913", null, new JSONObject().put("jsonrpc", "2.0").put("id", id++)
                .put("method", method).put("params", params).toString());
    }

    static JSONObject call(String name, JSONObject args) throws Exception {
        String[] r = rpc("tools/call", new JSONObject().put("name", name).put("arguments", args));
        return new JSONObject(r[1]).getJSONObject("result");
    }

    static String text(JSONObject result) {
        return result.optJSONArray("content").optJSONObject(0).optString("text");
    }

    static String[] post(String auth, String origin, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        if (auth != null) c.setRequestProperty("Authorization", auth);
        if (origin != null) c.setRequestProperty("Origin", origin);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = c.getOutputStream()) { o.write(b); }
        return answer(c);
    }

    static String[] get(String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base).openConnection();
        c.setRequestProperty("Authorization", auth);
        return answer(c);
    }

    static String[] answer(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
        }
        return new String[]{String.valueOf(code), new String(out.toByteArray(), StandardCharsets.UTF_8)};
    }

    static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
