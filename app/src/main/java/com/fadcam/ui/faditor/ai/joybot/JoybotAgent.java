package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.AIJobService;
import com.fadcam.ui.faditor.ai.AIToolExecutor;
import com.fadcam.ui.faditor.ai.AiService;
import com.fadcam.ui.faditor.ai.ModelCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Joybot thinking and acting on his own: one request, from the person's words to the reply,
 * with every tool the model calls along the way — and no screen required.
 *
 * <p>Hand-off P0-3 (2026-10-15): a request the MODEL was carrying out lived in the chat screen,
 * so closing the chat mid-way stopped it half done. It runs here now, on the same app-wide worker
 * as Joybot's button jobs ({@link JoybotJobs}) — so two writers never touch the project at once —
 * under the foreground notification. The chat is only a window onto it: it attaches a
 * {@link Listener} while open; a request that finishes with no chat open waits for the next one
 * ("While you were away…"). With the editor on screen, its writes go into the open project as one
 * undo step ({@link JoybotStorage}).</p>
 *
 * <p>This is also the seam for Joybot working with no chat at all (a headless run from a room, a
 * scripted job): {@link #ask} needs a context and an {@link Ask}, nothing else.</p>
 *
 * <p>A picture can ride along ({@link Ask#imageDataUrl}). It used to go down a separate path
 * that could only TALK about it — "label this sprite sheet" could never label anything. Now a
 * picture request uses the same tools as any other.</p>
 */
public final class JoybotAgent {

    private JoybotAgent() {}

    private static final String TAG = "JoybotAgent";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient();

    public static final int MAX_TOOL_ROUNDS = 8;
    private static final int REPLY_TOKENS = 4096;
    private static final int CONTEXT_CAP = 14_000;
    private static final int TRANSCRIPT_PREVIEW = 900;

    /**
     * The conversation, kept while the app is open: the chat shows it, the model reads it. Lives
     * here rather than in the chat screen because a request outlives the screen.
     */
    public static final List<JSONObject> HISTORY = Collections.synchronizedList(new ArrayList<>());

    /** Where the person was when they asked — what "this", "here" and "now" mean. */
    public static final class Situation {
        @NonNull public IdeaEngine.Room room = IdeaEngine.Room.LOBBY;
        /** Editor playhead, or -1. */
        public long playheadMs = -1;
        /** What is selected in the editor, or null. */
        @Nullable public String selectedId;
        /** The idea buttons on screen, so "do the second one" resolves. */
        @NonNull public final List<String> suggestions = new ArrayList<>();
    }

    /** One request. */
    public static final class Ask {
        @Nullable public final String projectId;
        @NonNull public final String text;
        @NonNull public final String model;
        @NonNull public final String apiKey;
        /** The undo step's name, in the person's words ("Joybot: “make it pop”"). */
        @NonNull public final String undoLabel;
        @NonNull public final Situation situation;
        /** A plain question is never nudged into "you said you changed it but didn't". */
        public boolean question;
        /** "data:image/jpeg;base64,…" sent with THIS request only, or null. */
        @Nullable public String imageDataUrl;
        public int imageW, imageH;

        public Ask(@Nullable String projectId, @NonNull String text, @NonNull String model,
                   @NonNull String apiKey, @NonNull String undoLabel, @NonNull Situation situation) {
            this.projectId = projectId;
            this.text = text;
            this.model = model;
            this.apiKey = apiKey;
            this.undoLabel = undoLabel;
            this.situation = situation;
        }
    }

    /** How a request ended. */
    public static final class Finished {
        @Nullable public final String projectId;
        /** The person's words, for "While you were away: you asked…". */
        @NonNull public final String asked;
        /** Joybot's last words, or null when stopped. */
        @Nullable public String reply;
        public boolean ok;
        /** Every tool result line, in order (proposal cards ride in these). */
        @NonNull public final List<String> toolLines = new ArrayList<>();
        /** The undo step the request made, if it changed the project behind the editor. */
        @Nullable public AIChatState.Step step;

        Finished(@Nullable String projectId, @NonNull String asked) {
            this.projectId = projectId;
            this.asked = asked;
        }
    }

    /** What the chat shows while a request runs. Every call is on the main thread. */
    public interface Listener {
        /** Words to show: replace Joybot's newest bubble, or open a new one. */
        void onText(@NonNull String shown, boolean newBubble);
        /** A tool is running ({@code percent} -1 = no number yet). */
        void onWorking(@NonNull String task, int percent);
        /** A long tool with known stages is starting. */
        void onChecklist(@NonNull String[] stages);
        /** The tool finished; its result line. */
        void onToolLine(@NonNull String line);
        /** The request's first change behind the editor, as its undo step. */
        void onStep(@NonNull AIChatState.Step step);
        /** The request is over. Return false if this screen can't show it (it then waits). */
        boolean onFinished(@NonNull Finished finished);
    }

    @Nullable private static Listener listener;
    private static final List<Finished> waiting = new ArrayList<>();
    @Nullable private static volatile Ask current;
    private static volatile boolean cancelled;
    @Nullable private static volatile okhttp3.Call call;

    public static synchronized void attach(@Nullable Listener l) { listener = l; }

    /** Detach only if {@code l} is still the one attached (a newer chat may have replaced it). */
    public static synchronized void detach(@NonNull Listener l) {
        if (listener == l) listener = null;
    }

    /** The request running now, or null. */
    @Nullable
    public static Ask current() { return current; }

    /** Stop the reply being written; a tool already running finishes first. */
    public static void cancel() {
        cancelled = true;
        okhttp3.Call c = call;
        if (c != null) c.cancel();
    }

    /** Requests that finished with no chat open, for this project; removed once taken. */
    @NonNull
    public static synchronized List<Finished> takeWaiting(@Nullable String projectId) {
        List<Finished> out = new ArrayList<>();
        for (int i = waiting.size() - 1; i >= 0; i--) {
            String p = waiting.get(i).projectId;
            if (p == null ? projectId == null : p.equals(projectId)) out.add(0, waiting.remove(i));
        }
        return out;
    }

    /** Run a request where closing the chat can't stop it. */
    public static void ask(@NonNull Context context, @NonNull Ask ask) {
        final Context app = context.getApplicationContext();
        cancelled = false;
        current = ask;
        JoybotJobs.execute(() -> {
            AIJobService.start(app, "Joybot: " + shortOf(ask.text));
            Finished f = new Finished(ask.projectId, ask.text);
            try {
                new Run(app, ask, f).go();
            } catch (Throwable t) {
                FLog.e(TAG, "request failed", t);
                if (!cancelled) {
                    f.reply = t instanceof IOException ? friendlyNetworkError((IOException) t)
                            : "Something went wrong: " + t.getMessage();
                    f.ok = false;
                    post(l -> l.onText(f.reply, false));
                }
            } finally {
                call = null;
                current = null;
                AIJobService.stop(app);
            }
            if (cancelled) f.reply = null;
            MAIN.post(() -> deliver(f));
        });
    }

    private static synchronized void deliver(@NonNull Finished f) {
        Listener l = listener;
        if (l != null && l.onFinished(f)) return;
        if (f.reply != null || !f.toolLines.isEmpty()) waiting.add(f);
    }

    private interface Event { void to(@NonNull Listener l); }

    private static void post(@NonNull Event e) {
        MAIN.post(() -> {
            Listener l;
            synchronized (JoybotAgent.class) { l = listener; }
            if (l != null) e.to(l);
        });
    }

    // ── one request ─────────────────────────────────────────────────────────

    private static final class Run {
        final Context app;
        final Ask ask;
        final Finished out;
        /** Writes made while the editor is on screen merge into one step by this token. */
        final String token = UUID.randomUUID().toString();
        boolean stepPushed, nudged, toolRan;
        /** The last tool's own result, handed back to the model. */
        String lastResult = "";
        /** What {@code look} just fetched, and the message that will carry it. */
        @Nullable JoybotEyes.Sight sight;
        @Nullable JSONObject sightMsg;
        /** Whether the bubble this round writes into is already open. */
        boolean opened = true;

        Run(@NonNull Context app, @NonNull Ask ask, @NonNull Finished out) {
            this.app = app;
            this.ask = ask;
            this.out = out;
        }

        void go() throws Exception {
            JSONObject retained = new JSONObject().put("role", "user").put("content", ask.imageDataUrl == null
                    ? ask.text
                    // Worded as a FACT ABOUT THE PAST: on a later turn the picture is gone, and
                    // "[image attached]" made models deny they could ever see (device, 2026-08-06).
                    : ask.text + "\n[The person showed you a " + ask.imageW + "x" + ask.imageH
                            + " picture at this point in the conversation. It was visible to you then"
                            + " and is not re-attached now; ask them to share it again if you need"
                            + " another look.]");
            HISTORY.add(retained);

            for (int round = 0; ; round++) {
                if (cancelled) return;
                if (round > MAX_TOOL_ROUNDS) {
                    finish(true, "I stopped after " + MAX_TOOL_ROUNDS + " steps in a row so nothing"
                            + " runs away. Say \"keep going\" if you want me to continue.");
                    return;
                }
                opened = round == 0;   // the first reply fills the "Thinking…" bubble
                Completion c;
                if (round == 0 && ask.imageDataUrl != null) {
                    c = complete(retained, ask.text, ask.imageDataUrl);
                } else if (sight != null && sightMsg != null) {
                    c = complete(sightMsg, sightMsg.optString("content"), sight.dataUrl);
                } else {
                    c = complete(null, null, null);
                }
                sight = null;
                sightMsg = null;
                if (cancelled) return;
                if (c.error != null) { finish(false, friendlyApiError(c.error)); return; }
                ModelCapabilities.noteServedModel(c.servedModel);
                if (c.text.isEmpty()) {
                    if (round == 0) finish(false, "I got an empty reply. Try asking again.");
                    else finish(true, "Done.");
                    return;
                }
                String aiText = c.text;
                HISTORY.add(new JSONObject().put("role", "assistant").put("content", aiText));

                JSONObject toolCall = findToolCall(aiText);
                if (toolCall != null) {
                    String prose = proseOf(aiText);
                    text(prose.isEmpty() ? (round == 0 ? "On it…" : "Next step…") : prose);
                    String line = runTool(toolCall);
                    if (line == null) { finish(false, "That step didn't work, so I stopped."); return; }
                    out.toolLines.add(line);
                    post(l -> l.onToolLine(line));
                    JSONObject result = new JSONObject().put("role", "user").put("content", "Tool result:\n"
                            + lastResult
                            + "\n\nNow tell the person what changed, in one or two plain sentences"
                            + " (or call the next tool if the request needs another step).");
                    HISTORY.add(result);
                    // A look: this message carries the picture on the next request (only).
                    if (sight != null) sightMsg = result;
                    continue;
                }
                boolean unfinished = claimsAChange(aiText) || HelpBook.wantsChange(ask.text);
                if (!nudged && !toolRan && ask.projectId != null && !ask.question
                        && !aiText.trim().endsWith("?") && unfinished) {
                    // Nothing in the project changed, yet the reply claims it did (a free model,
                    // Note 9 2026-10-14) or the person asked for a change and got a description
                    // instead (a model LOOKED at a sprite sheet, listed sixteen names and stopped,
                    // 2026-10-15). Ask once for the tool that does it.
                    nudged = true;
                    text("Doing it…");
                    HISTORY.add(new JSONObject().put("role", "user").put("content",
                            "Nothing in the project has changed yet, but the person asked for a change: \""
                                    + ask.text + "\". Make it now — reply with ONLY the tool JSON"
                                    + " {\"tool\":...,\"args\":{...}} for the tool that does it"
                                    + " (for names on a sprite sheet: label_sprite_cells), no other words."));
                    continue;
                }
                finish(true, nudged && !toolRan && unfinished
                        ? "This model keeps describing the change instead of making it, so nothing"
                          + " changed. Try again, or pick another model (tap its name at the top)."
                        : replyToShow(aiText));
                return;
            }
        }

        /** Show words: into the open bubble, or a new one. */
        void text(@NonNull String shown) {
            final boolean fresh = !opened;
            opened = true;
            post(l -> l.onText(shown, fresh));
        }

        void finish(boolean ok, @NonNull String reply) {
            out.ok = ok;
            out.reply = reply;
            text(reply);
        }

        /** Run one tool the model asked for; its result line, or null if it could not run. */
        @Nullable
        String runTool(@NonNull JSONObject toolCall) {
            String name;
            JSONObject args;
            try {
                name = toolCall.getString("tool");
                args = toolCall.optJSONObject("args");
                if (args == null) args = toolCall.optJSONObject("arguments");
                if (args == null) args = new JSONObject();
            } catch (Exception e) {
                return null;
            }
            if ("look".equals(name)) return look(args);
            toolRan = true;
            final String task = name.replace('_', ' ');
            post(l -> l.onWorking(task, -1));
            if ("ai_enhance".equals(name)) {
                post(l -> l.onChecklist(new String[]{"Generate Vosk transcript",
                        "Generate Whisper transcript", "Detect silence", "Synthesize transcripts",
                        "Cut fillers"}));
            }
            AIJobService.update(app, "Joybot: " + task, 0);
            // While the editor is on screen it records the change itself, one step per request.
            AIChatState.liveWriteLabel = ask.undoLabel;
            AIChatState.liveWriteRequest = token;
            String pid = ask.projectId;
            boolean live = JoybotStorage.isLive(pid);
            String before = (!live && !stepPushed && pid != null) ? snapshot(app, pid) : null;
            String result;
            try {
                result = new AIToolExecutor(app, pid, (t, pct) -> {
                    AIJobService.update(app, "Joybot: " + t, pct);
                    post(l -> l.onWorking(t, pct));
                }).executeTool(name, args);
            } catch (Throwable t) {
                FLog.e(TAG, "tool failed: " + name, t);
                result = "Error: " + t.getMessage();
            }
            // The editor came back on screen while the tool ran (the chat was closed mid-request):
            // it re-saved the file, so before/after differ by ITS save, not ours. Any write of ours
            // went through it (JoybotStorage) as its own undo step. Recording one here put a false
            // "Changed:" card with an Undo in the chat after a read-only describe (Note 9, 10-15).
            if (before != null && !JoybotStorage.isLive(pid)) {
                String after = snapshot(app, pid);
                if (after != null && !after.equals(before)) {
                    stepPushed = true;
                    AIChatState.pushStep(pid, ask.undoLabel, before, token);
                    final AIChatState.Step step = AIChatState.peekStep(pid);
                    out.step = step;
                    if (step != null) post(l -> l.onStep(step));
                }
            }
            lastResult = result;
            return name + " → " + result;
        }

        /**
         * {@code look}: a picture of something in the project, for the model's next turn. Read-only,
         * so it is not a "tool ran" (the nudge still applies) and parks no undo step.
         */
        @NonNull
        String look(@NonNull JSONObject args) {
            final String id = args.optString("id", "playhead");
            post(l -> l.onWorking("looking", -1));
            String result;
            if (!AiService.isCustom(app)
                    && ModelCapabilities.visionFor(ask.model) == ModelCapabilities.Vision.NO) {
                result = "Error: the model in use can't see pictures. Tell the person to pick one with"
                        + " the eye (tap the model name at the top), then ask again.";
            } else if (ask.projectId == null) {
                result = "Error: no project is open.";
            } else {
                com.fadcam.ui.faditor.model.FaditorProject p = new JoybotStorage(app).load(ask.projectId);
                JoybotEyes.Sight s = p == null ? null
                        : JoybotEyes.look(app, p, id, args.optLong("atMs", -1), ask.situation.playheadMs);
                if (s == null) {
                    result = "Error: " + (p == null ? "the project could not be read." : JoybotEyes.lastProblem);
                } else {
                    sight = s;
                    result = "You are looking at " + s.caption + " The picture is attached to THIS"
                            + " message only; call look again if you need another view later.";
                }
            }
            lastResult = result;
            return "look \u2192 " + result;
        }

        /**
         * One streamed completion over the conversation. When {@code swap} is given, that message
         * goes out as {@code swapText} plus the picture at {@code imageUrl}: this request only.
         */
        @NonNull
        Completion complete(@Nullable JSONObject swap, @Nullable String swapText,
                            @Nullable String imageUrl) throws Exception {
            JSONArray messages = messages();
            JSONObject withImage = swap;
            if (withImage != null && imageUrl != null) {
                JSONArray content = new JSONArray()
                        // Text part first: OpenRouter reads the parts in order.
                        .put(new JSONObject().put("type", "text").put("text", swapText == null ? "" : swapText))
                        .put(new JSONObject().put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", imageUrl)));
                JSONObject outgoing = new JSONObject().put("role", "user").put("content", content);
                for (int i = 0; i < messages.length(); i++) {
                    if (messages.opt(i) == withImage) messages.put(i, outgoing);
                }
            }
            String url = AiService.chatUrl(app);
            Quirks q = quirksFor(url);
            for (int attempt = 0; ; attempt++) {
                JSONObject body = new JSONObject()
                        .put("model", ask.model)
                        .put("messages", messages)
                        .put(q.completionTokens ? "max_completion_tokens" : "max_tokens", REPLY_TOKENS)
                        .put("stream", q.stream);
                if (q.temperature) body.put("temperature", 0.7);
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + ask.apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();
                okhttp3.Call k = HTTP.newCall(request);
                call = k;
                try (Response response = k.execute()) {
                    if (response.code() == 400 && attempt < 3 && response.body() != null) {
                        // A service that refuses one of OUR settings (OpenAI's newer models take
                        // max_completion_tokens and no temperature; some accounts may not stream):
                        // drop it, remember it for this address, and ask again.
                        String err = response.body().string();
                        if (q.adapt(err)) continue;
                        Completion c = new Completion();
                        c.error = "API error 400: " + err.substring(0, Math.min(200, err.length()));
                        return c;
                    }
                    return read(response, q.stream);
                }
            }
        }

        /** A finished response: streamed (server-sent events) or one JSON body. */
        @NonNull
        private Completion read(@NonNull Response response, boolean streamed) throws Exception {
            Completion c = new Completion();
            {
                if (!response.isSuccessful() || response.body() == null) {
                    String err = response.body() != null ? response.body().string() : "";
                    c.error = "API error " + response.code() + ": " + err.substring(0, Math.min(200, err.length()));
                    return c;
                }
                if (!streamed) {
                    JSONObject json = new JSONObject(response.body().string());
                    c.servedModel = json.optString("model", null);
                    JSONArray ch = json.optJSONArray("choices");
                    JSONObject msg = ch == null || ch.length() == 0 ? null : ch.getJSONObject(0).optJSONObject("message");
                    c.text = msg == null || msg.isNull("content") ? "" : msg.optString("content", "");
                    if (!c.text.isEmpty()) text(shownWhileStreaming(c.text));
                    return c;
                }
                okio.BufferedSource src = response.body().source();
                StringBuilder text = new StringBuilder();
                long lastShown = 0;
                String line;
                while ((line = src.readUtf8Line()) != null) {
                    // Lines are "data: {json}", ": keep-alive" comments, or blank separators.
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    JSONObject chunk;
                    try {
                        chunk = new JSONObject(data);
                    } catch (Exception notJson) {
                        continue;
                    }
                    if (chunk.has("error")) {
                        JSONObject e = chunk.optJSONObject("error");
                        c.error = "API error: " + (e != null ? e.optString("message", "unknown") : chunk.optString("error"));
                        return c;
                    }
                    if (c.servedModel == null && chunk.has("model")) c.servedModel = chunk.optString("model", null);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null || delta.isNull("content")) continue;
                    String piece = delta.optString("content", "");
                    if (piece.isEmpty()) continue;
                    text.append(piece);
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastShown >= 80) {
                        lastShown = now;
                        text(shownWhileStreaming(text.toString()));
                    }
                }
                c.text = text.toString();
            }
            return c;
        }

        /** The conversation as sent: the system message carries a just-read project context. */
        @NonNull
        JSONArray messages() {
            JSONArray out = new JSONArray();
            List<JSONObject> copy;
            synchronized (HISTORY) { copy = new ArrayList<>(HISTORY); }
            for (int i = 0; i < copy.size(); i++) {
                JSONObject m = copy.get(i);
                if (i == 0 && "system".equals(m.optString("role"))) {
                    try {
                        out.put(new JSONObject().put("role", "system")
                                .put("content", m.optString("content") + "\n" + context()));
                        continue;
                    } catch (Exception ignored) { }
                }
                out.put(m);
            }
            return out;
        }

        /** A compact, just-read picture of the project. Blocking (reads the project). */
        @NonNull
        String context() {
            Situation s = ask.situation;
            StringBuilder sb = new StringBuilder("CURRENT PROJECT (read just now; trust it over anything earlier in the chat)\n");
            sb.append("Joybot was opened from: ").append(s.room.name().toLowerCase(Locale.US)).append('\n');
            String pid = ask.projectId;
            if (pid == null) return sb.append("No project is open.\n").toString();
            try {
                // JoybotStorage: with the editor on screen this is the project open there, edits
                // made since the chat closed included.
                com.fadcam.ui.faditor.model.FaditorProject p = new JoybotStorage(app).load(pid);
                if (p == null) return sb.append("The project could not be read.\n").toString();
                com.fadcam.ui.faditor.model.Timeline tl = p.getTimeline();
                sb.append("Project \"").append(p.getName()).append("\": ").append(tl.getClipCount())
                        .append(" clips, ").append(clock(tl.getTotalDurationMs()))
                        .append(" long, canvas ").append(p.getCanvasPreset()).append('\n');
                if (s.room == IdeaEngine.Room.STUDIO && s.playheadMs >= 0) {
                    sb.append("Playhead: ").append(s.playheadMs).append("ms (").append(clock(s.playheadMs)).append(")\n");
                }
                long clipAt = 0;
                for (int i = 0; i < tl.getClipCount(); i++) {
                    com.fadcam.ui.faditor.model.Clip c = tl.getClip(i);
                    sb.append("Clip ").append(i).append(" id=").append(c.getId()).append(" \"")
                            .append(ProjectFactsReader.nameOf(c)).append("\" ")
                            .append(c.isGeneratedSlide() ? "slide" : c.isImageClip() ? "picture" : "video")
                            .append(" from ").append(clipAt).append("ms for ").append(c.getVisualDurationMs()).append("ms");
                    clipAt += c.getVisualDurationMs();
                    if (c.isAudioMuted()) sb.append(" muted");
                    sb.append(fades(c.getMasterFadeInMs(), c.getMasterFadeOutMs()));
                    if (c.getSpeedMultiplier() != 1f) sb.append(" speed=").append(c.getSpeedMultiplier());
                    if (c.isCaptionsEnabled()) sb.append(" captions=").append(c.getCaptionStyleId());
                    if (!c.getRemovedSpans().isEmpty()) sb.append(" cuts=").append(c.getRemovedSpans().size());
                    sb.append('\n');
                    appendTranscript(sb, c.getActiveNamedTranscript(), c.getTranscripts(), c.getRemovedSpans());
                }
                for (com.fadcam.ui.faditor.model.AudioClip ac : tl.getAudioClips()) {
                    sb.append("AudioClip id=").append(ac.getId()).append(" \"").append(ProjectFactsReader.nameOf(ac))
                            .append("\" at ").append(ac.getOffsetMs()).append("ms, ")
                            .append(clock(ac.getTrimmedDurationMs()));
                    if (ac.isMuted()) sb.append(" muted");
                    if (ac.isCaptionsEnabled()) sb.append(" captions=").append(ac.getCaptionStyleId());
                    sb.append('\n');
                    appendTranscript(sb, ac.getActiveNamedTranscript(), ac.getTranscripts(), ac.getRemovedSpans());
                }
                // Every layer kind, with ids, names, times and fade handles — so "this", "the cat",
                // "every layer" and "the fades" all resolve to real things.
                int shown = 0;
                for (com.fadcam.ui.faditor.model.Clip oc : tl.getOverlayClips()) {
                    if (shown++ >= 30) break;
                    sb.append("Video layer id=").append(oc.getId()).append(" \"").append(ProjectFactsReader.nameOf(oc))
                            .append("\" from ").append(oc.getOverlayStartMs()).append("ms")
                            .append(" for ").append(clock(oc.getVisualDurationMs()))
                            .append(fades(oc.getMasterFadeInMs(), oc.getMasterFadeOutMs())).append('\n');
                }
                for (com.fadcam.ui.faditor.model.TextOverlayItem o : tl.getTextOverlays()) {
                    if (shown++ >= 30) break;
                    String kind = o.isGeneratedSlide() ? "Slide layer (on top)" : o.isImage() ? "Image layer" : "Text layer";
                    String txt = o.isGeneratedSlide() || o.isImage() ? kind.toLowerCase(Locale.US) : o.getText();
                    if (txt != null && txt.length() > 30) txt = txt.substring(0, 30) + "…";
                    sb.append(kind).append(" id=").append(o.getId())
                            .append(" \"").append(txt).append("\" ")
                            .append(o.getStartMs()).append("ms-")
                            .append(o.getEndMs() == Long.MAX_VALUE ? "end" : o.getEndMs() + "ms")
                            .append(fades(o.getImageFadeInMs(), o.getImageFadeOutMs())).append('\n');
                }
                for (com.fadcam.ui.faditor.sprite.SpriteOverlayItem sp : tl.getSpriteOverlays()) {
                    if (shown++ >= 30) break;
                    sb.append("Sprite layer id=").append(sp.getId()).append(' ').append(sp.getStartMs()).append("ms-")
                            .append(sp.getEndMs()).append("ms").append(fades(sp.getFadeInMs(), sp.getFadeOutMs())).append('\n');
                }
                for (com.fadcam.ui.faditor.model.WaveformOverlayInstance w : tl.getWaveformOverlays()) {
                    if (shown++ >= 30) break;
                    sb.append("Visualizer id=").append(w.getId()).append(' ').append(w.getStartMs()).append("ms-")
                            .append(w.getEndMs()).append("ms").append(fades(w.getFadeInMs(), w.getFadeOutMs())).append('\n');
                }
                for (com.fadcam.ui.faditor.model.AdjustmentLayer a : tl.getAdjustmentLayers()) {
                    if (shown++ >= 30) break;
                    sb.append("Adjustment layer id=").append(a.getId()).append(" \"").append(a.getName()).append("\" ")
                            .append(a.getStartMs()).append("ms-").append(a.getEndMs()).append("ms\n");
                }
                if (shown > 30) sb.append("  …more layers not listed\n");
                for (com.fadcam.ui.faditor.model.Transition t : tl.getTransitions()) {
                    sb.append("Transition ").append(t.type).append(' ').append(t.durationMs).append("ms at seam ")
                            .append(t.clipIndex).append(" (between clip ").append(t.clipIndex).append(" and ")
                            .append(t.clipIndex + 1).append(")\n");
                }
                if (s.room == IdeaEngine.Room.STUDIO && s.selectedId != null) {
                    sb.append("SELECTED in the editor (\"this\"): id=").append(s.selectedId).append('\n');
                }
                for (com.fadcam.ui.faditor.sprite.SpriteSheet sh : p.getSpriteSheets()) {
                    sb.append("Sprite sheet id=").append(sh.getId()).append(" \"").append(sh.getName()).append("\" ")
                            .append(sh.cellCount()).append(sh.isSequence() ? " frames" : " cells, ")
                            .append(sh.isSequence() ? "" : sh.getCellNames().size() + " named, ")
                            .append(sh.getPresets().size()).append(" animations\n");
                }
                try {
                    int b = new com.fadcam.ui.faditor.ai.BRollBucket(app).listAssets().size();
                    if (b > 0) sb.append("B-roll bucket: ").append(b).append(" files (list_broll for names)\n");
                } catch (Exception ignored) { }
                if (!s.suggestions.isEmpty()) {
                    sb.append("Joybot's suggestions on screen now: ");
                    for (String label : s.suggestions) sb.append('"').append(label).append("\" ");
                    sb.append('\n');
                }
            } catch (Exception e) {
                sb.append("(Could not read the project: ").append(e.getMessage()).append(")\n");
            }
            if (sb.length() > CONTEXT_CAP) sb.setLength(CONTEXT_CAP);
            return sb.toString();
        }
    }

    /**
     * What a service at one address accepts, learned from its refusals and kept while the app runs.
     * Every service starts with the settings OpenRouter takes; a 400 that names one of them turns
     * it off.
     */
    private static final class Quirks {
        volatile boolean completionTokens, stream = true, temperature = true;

        /** Change what this error is about; false when it is about none of ours. */
        boolean adapt(@NonNull String err) {
            String l = err.toLowerCase(Locale.US);
            if (!completionTokens && l.contains("max_tokens")) { completionTokens = true; return true; }
            if (temperature && l.contains("temperature")) { temperature = false; return true; }
            if (stream && l.contains("stream")) { stream = false; return true; }
            return false;
        }
    }

    private static final java.util.Map<String, Quirks> QUIRKS = new java.util.concurrent.ConcurrentHashMap<>();

    @NonNull
    private static Quirks quirksFor(@NonNull String url) {
        Quirks q = QUIRKS.get(url);
        if (q == null) { q = new Quirks(); QUIRKS.put(url, q); }
        return q;
    }

    private static final class Completion {
        String text = "";
        @Nullable String servedModel;
        /** What to show instead of a reply, or null when it worked. */
        @Nullable String error;
    }

    /** The project as the undo snapshot format; null when unreadable. */
    @Nullable
    private static String snapshot(@NonNull Context app, @NonNull String projectId) {
        try {
            com.fadcam.ui.faditor.project.ProjectStorage st = new com.fadcam.ui.faditor.project.ProjectStorage(app);
            com.fadcam.ui.faditor.model.FaditorProject p = st.load(projectId);
            return p == null ? null : st.toJson(p);
        } catch (Exception e) {
            return null;
        }
    }

    // ── words: what the person sees of a reply ───────────────────────────────

    /**
     * The words to show for a finished reply. The free router sometimes hands a turn to a
     * moderation model, whose whole answer is "User Safety: safe / Response Safety: safe" (seen on
     * the Note 9, 2026-10-14); that is never shown as Joybot speaking.
     */
    @NonNull
    public static String replyToShow(@NonNull String aiText) {
        String prose = proseOf(aiText);
        String t = (prose.isEmpty() ? aiText : prose).trim();
        if (looksLikeJunk(t)) return "Done.";
        // Ids are for tools, never for people (the persona says so; free models forget).
        return t.replaceAll("\\s*\\(id\\s*=\\s*[^)]*\\)", "")
                .replaceAll("\\bid\\s*=\\s*[0-9a-fA-F-]{8,}", "").trim();
    }

    /** A reply that says the project changed ("Done", "I've set…", "now ends at…"). */
    public static boolean claimsAChange(@NonNull String t) {
        String l = t.toLowerCase(Locale.US);
        String[] tells = {"done", "i've ", "i have ", "has been", "have been", "will end", "now ends",
                "will start", "now starts", "is now", "are now", "set the", "added", "removed",
                "trimmed", "moved", "applied", "updated", "changed"};
        for (String w : tells) if (l.contains(w)) return true;
        return false;
    }

    public static boolean looksLikeJunk(@NonNull String t) {
        String l = t.toLowerCase(Locale.US).trim();
        if (l.isEmpty() || l.equals("safe") || l.equals("unsafe")) return true;
        if (l.matches("(?s)^((user|response|prompt)\\s*safety\\s*:\\s*\\w+\\s*)+$")) return true;
        return l.startsWith("<|") || l.startsWith("[inst]");
    }

    /** What the person sees of a reply that contains a tool command: the words, never the JSON. */
    @NonNull
    public static String proseOf(@NonNull String soFar) {
        String t = soFar.trim();
        if (t.startsWith("{")) return "";
        int cut = -1;
        // Some models emit their own tool-call tokens before the JSON ("<dots_function_call>",
        // seen on the Note 9) — markup, never words for the person.
        for (String marker : new String[]{"{\"tool\"", "```", "{ \"tool\"", "<dots_function_call",
                "<tool_call", "<function", "<|"}) {
            int i = t.indexOf(marker);
            if (i >= 0 && (cut < 0 || i < cut)) cut = i;
        }
        if (cut < 0) return t;
        return t.substring(0, cut).trim();
    }

    /** A reply that is a tool command (JSON) shows as "Working..." while it streams, not as code. */
    @NonNull
    static String shownWhileStreaming(@NonNull String soFar) {
        String prose = proseOf(soFar);
        return prose.isEmpty() ? "Working…" : prose;
    }

    /**
     * The tool command in a reply, if there is one. Models are told to reply with ONLY the JSON,
     * and many add a sentence first ("Sure! {...}") or wrap it in a code fence; the old parser
     * then treated the whole reply as chat and the edit silently never happened.
     */
    @Nullable
    static JSONObject findToolCall(@NonNull String aiText) {
        String t = aiText.trim();
        int from = 0;
        while (true) {
            int i = t.indexOf('{', from);
            if (i < 0) return null;
            String obj = extractJsonObject(t.substring(i));
            if (obj == null) return null;
            try {
                JSONObject j = new JSONObject(obj);
                if (j.has("tool") && j.optString("tool", "").length() > 0) return j;
            } catch (Exception ignored) { }
            from = i + 1;
        }
    }

    /** First balanced {...} object in a string (tolerates newlines and quoted braces). */
    @Nullable
    public static String extractJsonObject(@NonNull String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        char prev = 0;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inStr) {
                if (ch == '"' && prev != '\\') inStr = false;
            } else if (ch == '"') {
                inStr = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
            prev = ch;
        }
        return null;
    }

    /** The service's error, said plainly; the free tier's limits get their own sentence. */
    @NonNull
    public static String friendlyApiError(@NonNull String raw) {
        String l = raw.toLowerCase(Locale.US);
        if (l.contains("429") || l.contains("rate limit") || l.contains("rate-limit")) {
            return "The AI service is busy, or I've hit its limit for now (free models allow a few "
                    + "requests a minute). Try again in a minute, or pick another model in ⚙.";
        }
        if (l.contains("401") || l.contains("unauthorized") || l.contains("invalid api key")
                || l.contains("no auth")) {
            return "The AI service didn't accept the key. Check it in ⚙.";
        }
        if (l.contains("402") || l.contains("credit") || l.contains("insufficient")) {
            return "That model needs credit on your AI account. Pick a free one in ⚙, or add credit.";
        }
        return raw;
    }

    @NonNull
    public static String friendlyNetworkError(@NonNull IOException e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof java.net.UnknownHostException || m.contains("Unable to resolve host")) {
            return "I can't reach the AI service — check the internet, or the address in ⚙. "
                    + "The buttons below still work offline.";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "The AI service took too long to answer. Try again in a moment.";
        }
        return "Network problem: " + m;
    }

    /** The person's words, short, for a notification or a "you asked…" line. */
    @NonNull
    public static String shortOf(@NonNull String text) {
        String t = text.trim().replaceAll("\\s+", " ");
        return t.length() > 40 ? t.substring(0, 39).trim() + "…" : t;
    }

    @NonNull
    private static String clock(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** " fade handles: in 2.0s, out 2.0s", or "" when it has none. */
    @NonNull
    private static String fades(long in, long out) {
        if (in <= 0 && out <= 0) return "";
        return String.format(Locale.US, " fade handles: in %.1fs, out %.1fs", in / 1000.0, out / 1000.0);
    }

    private static void appendTranscript(@NonNull StringBuilder sb,
                                         @Nullable com.fadcam.ui.faditor.transcript.NamedTranscript nt,
                                         @NonNull List<com.fadcam.ui.faditor.transcript.NamedTranscript> all,
                                         @NonNull List<long[]> removed) {
        if (nt == null && !all.isEmpty()) nt = all.get(0);
        if (nt == null || nt.transcript == null) { sb.append("  (no transcript)\n"); return; }
        StringBuilder words = new StringBuilder();
        int kept = 0, fillers = 0;
        for (com.fadcam.ui.faditor.transcript.TranscriptWord w : nt.transcript.words) {
            if (Fillers.isCut(w.startMs, w.endMs, removed)) continue;
            if (w.struck || Fillers.isFiller(w.text)) { fillers++; continue; }
            kept++;
            if (words.length() < TRANSCRIPT_PREVIEW) words.append(w.text).append(' ');
        }
        sb.append("  Transcript (").append(nt.engine).append(", ").append(kept).append(" words, ")
                .append(fillers).append(" filler words still in): \"").append(words.toString().trim())
                .append(words.length() >= TRANSCRIPT_PREVIEW ? "…\" (get_transcript for all words with timings)" : "\"")
                .append('\n');
    }
}
