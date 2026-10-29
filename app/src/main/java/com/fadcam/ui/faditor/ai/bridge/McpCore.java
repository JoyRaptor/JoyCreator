package com.fadcam.ui.faditor.ai.bridge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Model Context Protocol, as Joy Creator's bridge speaks it (BOT-6): JSON-RPC 2.0 messages from
 * an AI harness on the person's computer (Claude Code, Codex, OpenCode, Hermes, any MCP client), one
 * answer each. Tools only: initialize, ping, tools/list, tools/call. Nothing Android here, so the
 * desktop apps can reuse it; tested off-device (BridgeTest).
 */
public final class McpCore {

    /** Versions this core answers to, newest first (the client's is echoed when it's one of these). */
    public static final String[] VERSIONS = {"2025-06-18", "2025-03-26", "2024-11-05"};

    /** One tool's answer: words, and maybe a picture. */
    public static final class Result {
        @NonNull public final String text;
        /** PNG or JPEG bytes as base64, or null. */
        @Nullable public final String imageBase64;
        @Nullable public final String imageMime;
        public final boolean isError;

        public Result(@NonNull String text, @Nullable String imageBase64, @Nullable String imageMime, boolean isError) {
            this.text = text;
            this.imageBase64 = imageBase64;
            this.imageMime = imageMime;
            this.isError = isError;
        }

        @NonNull
        public static Result text(@NonNull String text, boolean isError) {
            return new Result(text, null, null, isError);
        }
    }

    /** Where the tools come from (Joybot's own, on the phone). */
    public interface Tools {
        /** MCP tool definitions: {name, description, inputSchema}. */
        @NonNull JSONArray list() throws Exception;
        @NonNull Result call(@NonNull String name, @NonNull JSONObject args) throws Exception;
    }

    private final Tools tools;
    private final String serverVersion;
    private final String instructions;
    /** The client's name from initialize ("claude-code", "codex-mcp-client"…), for the chat. */
    @Nullable private volatile String clientName;

    public McpCore(@NonNull Tools tools, @NonNull String serverVersion, @NonNull String instructions) {
        this.tools = tools;
        this.serverVersion = serverVersion;
        this.instructions = instructions;
    }

    @Nullable
    public String clientName() {
        return clientName;
    }

    /**
     * One HTTP body in, the body to send back, or null when nothing is owed (only notifications or
     * answers came in: the transport replies 202 Accepted).
     */
    @Nullable
    public String handle(@NonNull String body) {
        String t = body.trim();
        try {
            if (t.startsWith("[")) {
                JSONArray in = new JSONArray(t);
                JSONArray out = new JSONArray();
                for (int i = 0; i < in.length(); i++) {
                    JSONObject m = in.optJSONObject(i);
                    JSONObject r = m == null ? error(null, -32600, "Invalid Request") : one(m);
                    if (r != null) out.put(r);
                }
                return out.length() == 0 ? null : out.toString();
            }
            JSONObject r = one(new JSONObject(t));
            return r == null ? null : r.toString();
        } catch (Exception e) {
            return error(null, -32700, "Parse error").toString();
        }
    }

    @Nullable
    private JSONObject one(@NonNull JSONObject m) throws Exception {
        if (!m.has("method")) return null;   // a response to us, or junk: nothing owed
        String method = m.optString("method");
        Object id = m.opt("id");
        boolean notification = !m.has("id");
        JSONObject params = m.optJSONObject("params");
        if (params == null) params = new JSONObject();
        if (notification) return null;      // notifications/initialized, cancelled…: nothing to say
        switch (method) {
            case "initialize": {
                JSONObject client = params.optJSONObject("clientInfo");
                if (client != null) clientName = client.optString("name", null);
                String asked = params.optString("protocolVersion", VERSIONS[0]);
                String v = VERSIONS[0];
                for (String s : VERSIONS) if (s.equals(asked)) v = s;
                JSONObject result = new JSONObject()
                        .put("protocolVersion", v)
                        .put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", false)))
                        .put("serverInfo", new JSONObject().put("name", "joycreator").put("title", "Joy Creator")
                                .put("version", serverVersion))
                        .put("instructions", instructions);
                return ok(id, result);
            }
            case "ping":
                return ok(id, new JSONObject());
            case "tools/list":
                return ok(id, new JSONObject().put("tools", tools.list()));
            case "tools/call": {
                String name = params.optString("name", "");
                if (name.isEmpty()) return error(id, -32602, "Missing tool name");
                JSONObject args = params.optJSONObject("arguments");
                if (args == null) args = new JSONObject();
                Result r;
                try {
                    r = tools.call(name, args);
                } catch (Exception e) {
                    r = Result.text("Error: " + e.getMessage(), true);
                }
                return ok(id, content(r));
            }
            default:
                return error(id, -32601, "Method not found: " + method);
        }
    }

    /** A tool result as MCP content. */
    @NonNull
    static JSONObject content(@NonNull Result r) throws Exception {
        JSONArray c = new JSONArray().put(new JSONObject().put("type", "text").put("text", r.text));
        if (r.imageBase64 != null) {
            c.put(new JSONObject().put("type", "image").put("data", r.imageBase64)
                    .put("mimeType", r.imageMime == null ? "image/jpeg" : r.imageMime));
        }
        return new JSONObject().put("content", c).put("isError", r.isError);
    }

    /** An OpenAI-style function definition ({type, function: {name, description, parameters}}) as an MCP tool. */
    @Nullable
    public static JSONObject toolOf(@NonNull JSONObject def) throws Exception {
        JSONObject fn = def.optJSONObject("function");
        if (fn == null) return null;
        String name = fn.optString("name", "");
        if (name.isEmpty()) return null;
        JSONObject schema = fn.optJSONObject("parameters");
        if (schema == null) schema = new JSONObject().put("type", "object").put("properties", new JSONObject());
        if (!schema.has("type")) schema.put("type", "object");
        return new JSONObject().put("name", name).put("description", fn.optString("description", ""))
                .put("inputSchema", schema);
    }

    @NonNull
    private static JSONObject ok(@Nullable Object id, @NonNull JSONObject result) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id).put("result", result);
    }

    @NonNull
    private static JSONObject error(@Nullable Object id, int code, @NonNull String message) {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id)
                    .put("error", new JSONObject().put("code", code).put("message", message));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
