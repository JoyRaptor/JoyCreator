import com.fadcam.ui.faditor.ai.bridge.BridgeAuth;
import com.fadcam.ui.faditor.ai.bridge.McpCore;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The bring-your-own-AI bridge (BOT-6), off device: the MCP messages Claude Code and Codex send, and
 * who may use it. Run: bash tools/jvm-harness/run-bridge.sh
 */
public class BridgeTest {
    static int pass, fail;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS " + what); }
        else { fail++; System.out.println("FAIL " + what + "   " + detail); }
    }

    public static void main(String[] args) throws Exception {
        final String[] lastCall = new String[2];
        McpCore core = new McpCore(new McpCore.Tools() {
            @Override public JSONArray list() throws Exception {
                return new JSONArray().put(McpCore.toolOf(new JSONObject("{\"type\":\"function\",\"function\":"
                        + "{\"name\":\"set_title_text\",\"description\":\"Change a title\",\"parameters\":"
                        + "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}}}")));
            }
            @Override public McpCore.Result call(String name, JSONObject a) {
                lastCall[0] = name;
                lastCall[1] = a.toString();
                if (name.equals("look")) return new McpCore.Result("You are looking at the video", "AAAA", "image/jpeg", false);
                if (name.equals("boom")) throw new IllegalStateException("broke");
                return McpCore.Result.text(name.equals("bad") ? "Error: no such title" : "Done", name.equals("bad"));
            }
        }, "1.0", "Joy Creator's tools.");

        // initialize, as Claude Code sends it
        JSONObject r = new JSONObject(core.handle("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"2\"}}}"));
        check("initialize: answers with its id", r.getInt("id") == 0, r.toString());
        check("initialize: echoes a version it knows", "2025-06-18".equals(r.getJSONObject("result").getString("protocolVersion")), r.toString());
        check("initialize: offers tools", r.getJSONObject("result").getJSONObject("capabilities").has("tools"), r.toString());
        check("initialize: names itself", "joycreator".equals(r.getJSONObject("result").getJSONObject("serverInfo").getString("name")), "");
        check("initialize: remembers who connected", "claude-code".equals(core.clientName()), String.valueOf(core.clientName()));
        r = new JSONObject(core.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2099-01-01\"}}"));
        check("initialize: an unknown version gets our newest", McpCore.VERSIONS[0].equals(r.getJSONObject("result").getString("protocolVersion")), r.toString());

        check("notification: nothing owed", core.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}") == null, "");
        check("a response to us: nothing owed", core.handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{}}") == null, "");

        r = new JSONObject(core.handle("{\"jsonrpc\":\"2.0\",\"id\":\"p\",\"method\":\"ping\"}"));
        check("ping: empty result, string id kept", "p".equals(r.getString("id")) && r.getJSONObject("result").length() == 0, r.toString());

        r = new JSONObject(core.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        JSONObject tool = r.getJSONObject("result").getJSONArray("tools").getJSONObject(0);
        check("tools/list: MCP shape", "set_title_text".equals(tool.getString("name"))
                && tool.getJSONObject("inputSchema").getString("type").equals("object"), tool.toString());

        r = new JSONObject(core.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"set_title_text\",\"arguments\":{\"id\":\"t1\"}}}"));
        check("tools/call: runs with its arguments", "set_title_text".equals(lastCall[0]) && lastCall[1].contains("t1"), lastCall[1]);
        JSONObject res = r.getJSONObject("result");
        check("tools/call: text content", "Done".equals(res.getJSONArray("content").getJSONObject(0).getString("text")) && !res.getBoolean("isError"), res.toString());

        res = new JSONObject(core.handle("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"bad\"}}")).getJSONObject("result");
        check("tools/call: a refusal is isError, not a protocol error", res.getBoolean("isError"), res.toString());
        res = new JSONObject(core.handle("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"boom\"}}")).getJSONObject("result");
        check("tools/call: a crash comes back as an error result", res.getBoolean("isError")
                && res.getJSONArray("content").getJSONObject(0).getString("text").contains("broke"), res.toString());
        res = new JSONObject(core.handle("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"look\"}}")).getJSONObject("result");
        JSONObject img = res.getJSONArray("content").getJSONObject(1);
        check("tools/call: look sends the picture", "image".equals(img.getString("type")) && "AAAA".equals(img.getString("data"))
                && "image/jpeg".equals(img.getString("mimeType")), res.toString());

        r = new JSONObject(core.handle("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/list\"}"));
        check("unknown method: -32601", r.getJSONObject("error").getInt("code") == -32601, r.toString());
        r = new JSONObject(core.handle("{not json"));
        check("bad JSON: -32700", r.getJSONObject("error").getInt("code") == -32700, r.toString());
        JSONArray batch = new JSONArray(core.handle("[{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"));
        check("batch: one answer per request", batch.length() == 1 && batch.getJSONObject(0).getInt("id") == 9, batch.toString());

        // Who may use it
        BridgeAuth auth = new BridgeAuth("482913");
        check("auth: right code from the first computer", auth.check("Bearer 482913", null, "192.168.1.20") == BridgeAuth.Verdict.OK, "");
        check("auth: ...that computer is now the paired one", "192.168.1.20".equals(auth.pairedAddress()), "");
        check("auth: the code typed with a space still works", auth.check("bearer 482 913", null, "192.168.1.20") == BridgeAuth.Verdict.OK, "");
        check("auth: right code, other computer: refused", auth.check("Bearer 482913", null, "192.168.1.99") == BridgeAuth.Verdict.OTHER_COMPUTER, "");
        check("auth: a web page (Origin header) is refused", auth.check("Bearer 482913", "http://evil.example", "192.168.1.20") == BridgeAuth.Verdict.BROWSER, "");
        check("auth: no header", auth.check(null, null, "192.168.1.20") == BridgeAuth.Verdict.WRONG_CODE, "");
        BridgeAuth guessed = new BridgeAuth("482913");
        BridgeAuth.Verdict last = null;
        for (int i = 0; i < BridgeAuth.MAX_WRONG; i++) last = guessed.check("Bearer " + String.format("%06d", i), null, "10.0.0.5");
        check("auth: guessing locks it", last == BridgeAuth.Verdict.LOCKED_OUT, String.valueOf(last));
        check("auth: ...even the right code, once locked", guessed.check("Bearer 482913", null, "10.0.0.5") == BridgeAuth.Verdict.LOCKED_OUT, "");
        String code = BridgeAuth.newCode(new java.security.SecureRandom());
        check("auth: codes are six digits", code.matches("\\d{6}"), code);
        check("auth: shown as 482 913", "482 913".equals(BridgeAuth.spaced("482913")), BridgeAuth.spaced("482913"));

        // What the pairing screen says (BridgeWords)
        String url = "http://192.168.1.23:7314/mcp";
        String say = com.fadcam.ui.faditor.ai.bridge.BridgeWords.sayThis(url, "482913");
        check("words: the sentence carries the address and the code", say.contains(url) && say.contains("Bearer 482913"), say);
        java.util.List<String[]> cmds = com.fadcam.ui.faditor.ai.bridge.BridgeWords.commands(url, 7314, "482913");
        check("words: Claude Code's command", cmds.get(0)[1].equals("claude mcp add --transport http joycreator "
                + url + " --header \"Authorization: Bearer 482913\""), cmds.get(0)[1]);
        check("words: Codex's config", cmds.get(1)[1].contains("[mcp_servers.joycreator]") && cmds.get(1)[1].contains(url)
                && cmds.get(1)[0].contains("JOYCREATOR_CODE=482913"), cmds.get(1)[1]);
        check("words: the USB way", cmds.get(3)[1].startsWith("adb reverse tcp:7314 tcp:7314"), cmds.get(3)[1]);

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
