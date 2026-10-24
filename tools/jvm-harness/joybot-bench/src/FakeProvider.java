import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A fake OpenAI-compatible chat service on 127.0.0.1: the agent talks real HTTP to it, so its own
 * streaming reader, cut-off detection and tool-call assembly are what get tested. Each request is
 * answered by the next scripted turn; hooks run at set moments (before a request is answered, part
 * way through a streamed reply, after it).
 *
 * <p>A turn (JSON): {@code text}, {@code calls: [{name, args | rawArgs}]}, {@code finish}
 * ("stop" | "tool_calls" | "length" | "none" = no finish_reason), {@code done} (false = no
 * [DONE] line), {@code chunkDelayMs}, {@code delayMs}, {@code stream} (false = one JSON body),
 * {@code status} + {@code errorBody} (refuse the request with that HTTP error).</p>
 */
final class FakeProvider {

    interface Hooks {
        /** Before request {@code n} (1-based) is answered. */
        void beforeAnswer(int n, JsonObject body);
        /** Part-way through reply {@code n}, after streamed chunk {@code chunk} (1-based). */
        void duringAnswer(int n, int chunk);
        /** Reply {@code n} has been sent in full. */
        void afterAnswer(int n);
    }

    static final class Seen {
        final int n;
        final long atNanos = System.nanoTime();
        final int chars;
        final String lastUser;
        final JsonObject body;
        Seen(int n, int chars, String lastUser, JsonObject body) {
            this.n = n; this.chars = chars; this.lastUser = lastUser; this.body = body;
        }
    }

    private final List<JsonObject> turns;
    private final Hooks hooks;
    private final HttpServer server;
    final List<Seen> seen = new ArrayList<>();
    volatile boolean exhausted;
    private int next;

    FakeProvider(List<JsonObject> turns, Hooks hooks) throws IOException {
        this.turns = turns;
        this.hooks = hooks;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-provider");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

    void stop() { server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        byte[] in;
        try (InputStream is = ex.getRequestBody()) { in = is.readAllBytes(); }
        String raw = new String(in, StandardCharsets.UTF_8);
        JsonObject body = JsonParser.parseString(raw).getAsJsonObject();
        int n;
        JsonObject turn;
        synchronized (this) {
            n = seen.size() + 1;
            seen.add(new Seen(n, raw.length(), lastUser(body), body));
            if (next < turns.size()) {
                turn = turns.get(next++);
            } else {
                exhausted = true;
                turn = new JsonObject();
                turn.addProperty("text", "(the benchmark's script for this task has no more turns)");
            }
        }
        hooks.beforeAnswer(n, body);
        if (turn.has("delayMs")) sleep(turn.get("delayMs").getAsLong());
        boolean stream = !turn.has("stream") ? body.has("stream") && body.get("stream").getAsBoolean()
                : turn.get("stream").getAsBoolean();
        int outChars = 0;
        try {
            if (turn.has("status")) {
                // The service refuses the request (a setting it doesn't take, busy, no credit…).
                byte[] err = (turn.has("errorBody") ? turn.get("errorBody").getAsString() : "{}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(turn.get("status").getAsInt(), err.length);
                ex.getResponseBody().write(err);
            } else if (stream) outChars = sendStream(ex, n, turn, raw.length());
            else outChars = sendWhole(ex, turn, raw.length());
        } catch (IOException cut) {
            // The agent hung up (a Stop): nothing more to send.
        } finally {
            ex.close();
        }
        hooks.afterAnswer(n);
    }

    @SuppressWarnings("unused")
    private static String lastUser(JsonObject body) {
        JsonArray msgs = body.has("messages") ? body.getAsJsonArray("messages") : new JsonArray();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            JsonObject m = msgs.get(i).getAsJsonObject();
            if ("user".equals(str(m, "role")) && m.has("content") && m.get("content").isJsonPrimitive()) {
                return m.get("content").getAsString();
            }
        }
        return "";
    }

    static boolean mentions(JsonObject body, String words) {
        JsonArray msgs = body.has("messages") ? body.getAsJsonArray("messages") : new JsonArray();
        for (JsonElement e : msgs) {
            JsonObject m = e.getAsJsonObject();
            if ("user".equals(str(m, "role")) && m.has("content") && m.get("content").toString().contains(words)) return true;
        }
        return false;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static JsonObject usage(int promptChars, int outChars) {
        JsonObject u = new JsonObject();
        u.addProperty("prompt_tokens", promptChars / 4);
        u.addProperty("completion_tokens", Math.max(1, outChars / 4));
        u.addProperty("total_tokens", promptChars / 4 + Math.max(1, outChars / 4));
        return u;
    }

    private int sendStream(HttpExchange ex, int n, JsonObject turn, int promptChars) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        long gap = turn.has("chunkDelayMs") ? turn.get("chunkDelayMs").getAsLong() : 0;
        List<JsonObject> chunks = new ArrayList<>();
        int outChars = 0;
        if (turn.has("text")) {
            String t = turn.get("text").getAsString();
            int half = t.length() / 2;
            for (String piece : new String[]{t.substring(0, half), t.substring(half)}) {
                if (piece.isEmpty()) continue;
                JsonObject d = new JsonObject();
                d.addProperty("content", piece);
                chunks.add(choice(d, null));
                outChars += piece.length();
            }
        }
        JsonArray calls = turn.has("calls") ? turn.getAsJsonArray("calls") : new JsonArray();
        for (int i = 0; i < calls.size(); i++) {
            JsonObject c = calls.get(i).getAsJsonObject();
            String args = c.has("rawArgs") ? c.get("rawArgs").getAsString()
                    : c.has("args") ? c.get("args").toString() : "{}";
            outChars += args.length() + c.get("name").getAsString().length();
            // The id and name once, then the arguments in two pieces, as real services stream them.
            JsonObject first = new JsonObject();
            first.addProperty("index", i);
            first.addProperty("id", "call_" + n + "_" + i);
            first.addProperty("type", "function");
            JsonObject fn = new JsonObject();
            fn.addProperty("name", c.get("name").getAsString());
            fn.addProperty("arguments", "");
            first.add("function", fn);
            chunks.add(choice(toolDelta(first), null));
            int half = args.length() / 2;
            for (String piece : new String[]{args.substring(0, half), args.substring(half)}) {
                JsonObject more = new JsonObject();
                more.addProperty("index", i);
                JsonObject f2 = new JsonObject();
                f2.addProperty("arguments", piece);
                more.add("function", f2);
                chunks.add(choice(toolDelta(more), null));
            }
        }
        String finish = turn.has("finish") ? turn.get("finish").getAsString() : (calls.size() > 0 ? "tool_calls" : "stop");
        if (!"none".equals(finish)) {
            JsonObject last = choice(new JsonObject(), finish);
            last.add("usage", usage(promptChars, outChars));
            chunks.add(last);
        }
        int k = 0;
        for (JsonObject c : chunks) {
            c.addProperty("model", "bench/fake");
            os.write(("data: " + c + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            k++;
            hooks.duringAnswer(n, k);
            if (gap > 0) sleep(gap);
        }
        if (!turn.has("done") || turn.get("done").getAsBoolean()) {
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        }
        os.flush();
        return outChars;
    }

    private int sendWhole(HttpExchange ex, JsonObject turn, int promptChars) throws IOException {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        String text = turn.has("text") ? turn.get("text").getAsString() : null;
        if (text != null) msg.addProperty("content", text);
        else msg.add("content", com.google.gson.JsonNull.INSTANCE);
        int outChars = text == null ? 0 : text.length();
        JsonArray calls = turn.has("calls") ? turn.getAsJsonArray("calls") : new JsonArray();
        if (calls.size() > 0) {
            JsonArray tc = new JsonArray();
            for (int i = 0; i < calls.size(); i++) {
                JsonObject c = calls.get(i).getAsJsonObject();
                String args = c.has("rawArgs") ? c.get("rawArgs").getAsString()
                        : c.has("args") ? c.get("args").toString() : "{}";
                outChars += args.length();
                JsonObject k = new JsonObject();
                k.addProperty("id", "call_w_" + i);
                k.addProperty("type", "function");
                JsonObject fn = new JsonObject();
                fn.addProperty("name", c.get("name").getAsString());
                fn.addProperty("arguments", args);
                k.add("function", fn);
                tc.add(k);
            }
            msg.add("tool_calls", tc);
        }
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", msg);
        choice.addProperty("finish_reason", turn.has("finish") ? turn.get("finish").getAsString()
                : calls.size() > 0 ? "tool_calls" : "stop");
        JsonObject out = new JsonObject();
        out.addProperty("model", "bench/fake");
        JsonArray choices = new JsonArray();
        choices.add(choice);
        out.add("choices", choices);
        out.add("usage", usage(promptChars, outChars));
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        return outChars;
    }

    private static JsonObject toolDelta(JsonObject call) {
        JsonObject d = new JsonObject();
        JsonArray a = new JsonArray();
        a.add(call);
        d.add("tool_calls", a);
        return d;
    }

    private static JsonObject choice(JsonObject delta, String finish) {
        JsonObject c = new JsonObject();
        c.addProperty("index", 0);
        c.add("delta", delta);
        if (finish != null) c.addProperty("finish_reason", finish);
        JsonObject chunk = new JsonObject();
        JsonArray a = new JsonArray();
        a.add(c);
        chunk.add("choices", a);
        return chunk;
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
