package com.fadcam.ui.faditor.ai.bridge;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.ai.joybot.IdeaEngine;
import com.fadcam.ui.faditor.ai.joybot.JoybotAgent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * The bridge's door on the phone (BOT-6): MCP over plain HTTP ("streamable HTTP", JSON answers, no
 * stream), at {@code /mcp} on the local network only while the person has it switched on. Only the
 * pairing code and the first computer that uses it get in ({@link BridgeAuth}); a web page never does.
 * Every tool runs through Joybot's own host ({@link JoybotAgent.Bridged}).
 */
public final class BridgeServer extends NanoHTTPD {
    private static final String TAG = "BridgeServer";
    private static final int MAX_BODY = 4 * 1024 * 1024;

    /** What the pairing screen hears. Any thread. */
    public interface Events {
        void onEvent(@NonNull String line, boolean connected);
    }

    private final Context app;
    private final BridgeAuth auth;
    private final McpCore core;
    private final Events events;
    /**
     * The server that owns the shared state (the MCP session, Joybot's host, "has said hello").
     * The phone may listen on two addresses at once (its Wi-Fi address and its own loopback for the
     * cable); both are the SAME door, so they share one session. For a lone server this is itself.
     */
    private final BridgeServer root;
    @Nullable private final String projectId;
    @NonNull private final IdeaEngine.Room room;
    @Nullable private volatile JoybotAgent.Bridged bridged;
    private volatile boolean greeted;

    /** Listens on the phone's own loopback only (what the cable and the tests use). */
    public BridgeServer(@NonNull Context context, int port, @NonNull BridgeAuth auth, @Nullable String projectId,
                        @NonNull IdeaEngine.Room room, @NonNull String version, @NonNull Events events) {
        this(context, "127.0.0.1", port, auth, projectId, room, version, events);
    }

    /**
     * Review D1 (2026-11-12): the server used to bind every network interface (cellular, a
     * hotspot, a VPN, IPv6), far wider than the one Wi-Fi address the screen shows. Now it binds
     * only {@code host}: the Wi-Fi address shown to the person, or the loopback for the cable.
     */
    public BridgeServer(@NonNull Context context, @NonNull String host, int port, @NonNull BridgeAuth auth,
                        @Nullable String projectId, @NonNull IdeaEngine.Room room, @NonNull String version,
                        @NonNull Events events) {
        super(host, port);
        this.root = this;
        this.app = context.getApplicationContext();
        this.auth = auth;
        this.projectId = projectId;
        this.room = room;
        this.events = events;
        this.core = new McpCore(new McpCore.Tools() {
            @NonNull @Override public JSONArray list() throws Exception {
                JSONArray out = new JSONArray();
                JSONArray defs = JoybotAgent.Bridged.tools();
                for (int i = 0; i < defs.length(); i++) {
                    JSONObject t = McpCore.toolOf(defs.getJSONObject(i));
                    if (t != null) out.put(t);
                }
                return out;
            }

            @NonNull @Override public McpCore.Result call(@NonNull String name, @NonNull JSONObject args) throws Exception {
                events.onEvent(who() + ": " + name.replace('_', ' '), true);
                JoybotAgent.Bridged.Answer a = bridged().call(name, args);
                return new McpCore.Result(a.text, a.imageBase64, a.imageMime, a.failed);
            }
        }, version, INSTRUCTIONS);
    }

    /** A second address for the same door: shares {@code first}'s session, code and pairing. */
    public BridgeServer(@NonNull BridgeServer first, @NonNull String host, int port) {
        super(host, port);
        this.root = first.root;
        this.app = first.app;
        this.auth = first.auth;
        this.projectId = first.projectId;
        this.room = first.room;
        this.events = first.events;
        this.core = first.core;
    }

    /** "Claude Code", "Codex"…: who is on the other end, in the person's words. */
    @NonNull
    public String who() {
        return nameOf(root.core.clientName());
    }

    @NonNull
    static String nameOf(@Nullable String client) {
        if (client == null || client.isEmpty()) return "Your computer";
        String c = client.toLowerCase(java.util.Locale.US);
        if (c.contains("claude-code") || c.contains("claude code")) return "Claude Code";
        if (c.contains("claude")) return "Claude";
        if (c.contains("codex")) return "Codex";
        if (c.contains("opencode")) return "OpenCode";
        if (c.contains("hermes")) return "Hermes";
        if (c.contains("chatgpt") || c.contains("openai")) return "ChatGPT";
        return client;
    }

    @NonNull
    private JoybotAgent.Bridged bridged() {
        synchronized (root) {
            JoybotAgent.Bridged b = root.bridged;
            if (b == null) {
                b = new JoybotAgent.Bridged(app, projectId, room, who());
                root.bridged = b;
            }
            return b;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> h = session.getHeaders();   // keys arrive lower-case
        String from = session.getRemoteIpAddress();
        // Read the body first, even for a request about to be refused: left unread on a kept-alive
        // connection, it was taken as the start of the next request ("{}GET", BridgeE2E 10-28).
        String body;
        try {
            body = readBody(session, h);
        } catch (Exception e) {
            Response r = plain(Response.Status.BAD_REQUEST, "Couldn't read the request.");
            r.closeConnection(true);
            return r;
        }
        if (!"/mcp".equals(uri)) {
            return plain(Response.Status.NOT_FOUND, "Joy Creator's bridge lives at /mcp.");
        }
        BridgeAuth.Verdict v = auth.check(h.get("authorization"), h.get("origin"), from == null ? "" : from);
        switch (v) {
            case OK:
                break;
            case BROWSER:
                return plain(Response.Status.FORBIDDEN, "Web pages can't use the bridge.");
            case OTHER_COMPUTER:
                events.onEvent("Refused " + from + ": only the computer that paired first can use it.", false);
                return plain(Response.Status.FORBIDDEN, "Another computer is already paired.");
            case LOCKED_OUT:
                events.onEvent("Too many wrong codes, so the bridge stopped letting anyone in. Switch it off and on for a new code.", false);
                return plain(Response.Status.FORBIDDEN, "Locked: too many wrong codes.");
            case WRONG_CODE:
            default:
                events.onEvent("Someone at " + from + " tried a wrong code.", false);
                return plain(Response.Status.UNAUTHORIZED, "Wrong or missing code (Authorization: Bearer <code>).");
        }
        Method m = session.getMethod();
        if (m == Method.DELETE) return plain(Response.Status.OK, "");
        if (m != Method.POST) {
            // No server-to-client stream: every answer comes back on its own POST.
            return plain(Response.Status.METHOD_NOT_ALLOWED, "POST only.");
        }
        String answer = core.handle(body);
        if (!root.greeted && core.clientName() != null) {
            root.greeted = true;
            events.onEvent(who() + " connected from " + from + ".", true);
        }
        if (answer == null) return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "");
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", answer);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    @NonNull
    private static String readBody(@NonNull IHTTPSession s, @NonNull Map<String, String> h) throws Exception {
        String len = h.get("content-length");
        int n = len == null ? 0 : Integer.parseInt(len.trim());
        if (n < 0 || n > MAX_BODY) throw new IllegalArgumentException("body size " + n);
        InputStream in = s.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(n, 16));
        byte[] buf = new byte[8192];
        while (out.size() < n) {
            int k = in.read(buf, 0, Math.min(buf.length, n - out.size()));
            if (k < 0) break;
            out.write(buf, 0, k);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @NonNull
    private static Response plain(@NonNull Response.Status st, @NonNull String text) {
        return newFixedLengthResponse(st, "text/plain; charset=utf-8", text);
    }

    @Override
    public void stop() {
        super.stop();
        FLog.i(TAG, "bridge stopped");
    }

    /** What the computer's AI is told when it connects (MCP "instructions"). */
    static final String INSTRUCTIONS = "You are connected to Joy Creator, a creative studio on the person's"
            + " phone (video editor Studio, music visualizer VisLab, painting and animation JoyBrush, SpriteLab)."
            + " These tools are Joybot's own: Joy Creator's helper. Call where_am_i first to see the open project"
            + " or screen, and use look to see the finished video before and after changes. The phone keeps the"
            + " rules: each burst of changes is one Undo on the phone; anything the person locked stays locked;"
            + " deleting or reverting puts a card on the phone that only the person can tap. When a tool says it"
            + " did not happen, tell the person why instead of working around it. Talk to the person in short,"
            + " plain words: they are an artist, often on their phone.";
}
