package com.fadcam.ui.faditor.ai;

import com.fadcam.ui.faditor.Studio;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.faditor.ai.joybot.DirectRunner;
import com.fadcam.ui.faditor.ai.joybot.HelpBook;
import com.fadcam.ui.faditor.ai.joybot.JobFile;
import com.fadcam.ui.faditor.ai.joybot.JoybotAgent;
import com.fadcam.ui.faditor.ai.joybot.Idea;
import com.fadcam.ui.faditor.ai.joybot.IdeaEngine;
import com.fadcam.ui.faditor.ai.joybot.IdeaMemory;
import com.fadcam.ui.faditor.ai.joybot.JoybotChatViews;
import com.fadcam.ui.faditor.ai.joybot.JoybotJobs;
import com.fadcam.ui.faditor.ai.joybot.JoybotPictures;
import com.fadcam.ui.faditor.ai.joybot.ModelCatalog;
import com.fadcam.ui.faditor.ai.joybot.ModelPicker;
import com.fadcam.ui.faditor.ai.joybot.JoybotPulse;
import com.fadcam.ui.faditor.ai.joybot.JoybotVoice;
import com.fadcam.ui.faditor.ai.joybot.ProjectFactsReader;
import com.fadcam.ui.faditor.ai.joybot.Recipe;
import com.fadcam.ui.faditor.ai.joybot.RecipeStore;
import com.fadcam.ui.faditor.ai.joybot.SlideFlow;
import com.fadcam.ui.lobby.JoybotView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * AI Assistant chat shell for Faditor.
 *
 * <p>Connects to an OpenRouter-compatible API endpoint. When no API key is
 * configured, it falls back to a simple offline helper with FAQ and feature
 * shortcuts so the chat surface is always useful.</p>
 *
 * <p>This is the entry point for the "AI as editor assistant" vision:
 * the chat can answer questions about the app, suggest edits, and (later)
 * emit EditScripts that Joy Creator validates and applies.</p>
 */
public class ChatAssistantActivity extends AppCompatActivity {

    private static final String TAG = "ChatAssistant";

    public static final String EXTRA_PROJECT_ID = "chat_project_id";
    /** Where Joybot was opened from: an {@link IdeaEngine.Room} name ("LOBBY", "STUDIO", …). */
    public static final String EXTRA_ROOM = "chat_room";
    /** Open with the AI settings dialog showing (the Settings hub's "AI and Joybot" row). */
    public static final String EXTRA_OPEN_SETTINGS = "chat_open_settings";
    /** Id of what was selected in the editor (a layer item, else the spine clip), or absent. */
    public static final String EXTRA_SELECTED_ID = "chat_selected_id";
    /** True when he was tapped while glowing: open by saying the idea he had. */
    public static final String EXTRA_FROM_PULSE = "chat_from_pulse";

    // ── Joybot's own parts of the chat (plan B2) ──────────────────────────────
    private JoybotView actor;
    @Nullable private View headerIcon;
    private android.widget.HorizontalScrollView chipsScroll;
    private LinearLayout chipsRow;
    private TextView stopButton;
    private IdeaEngine.Room room = IdeaEngine.Room.STUDIO;
    /** Connecting an AI by talking (BOT-6): holds a pasted key only until it is checked. */
    private com.fadcam.ui.faditor.ai.joybot.SetupFlow setupFlow;
    /** The typing box's own input type, put back after it took a key (password-style, no suggestions). */
    private int chatInputType = -1;
    private IdeaMemory ideaMemory;
    @Nullable private IdeaEngine.Offer currentOffer;
    /** The best idea shown this visit, and whether it was tapped: two ignores make it quiet. */
    @Nullable private String shownBestId;
    private boolean bestTapped;
    /** Joybot is answering or working: the Stop button shows and the idea buttons wait. */
    private volatile boolean busy;
    private volatile boolean cancelled;
    @Nullable private SlideFlow slideFlow;
    /** The undo-history label for the request in flight, and whether it has parked its step. */
    private volatile String requestLabel = "Joybot";
    private volatile boolean requestStepPushed;
    /** The card showing each live Joybot step, so "undo that" can retire the right one. */
    private final java.util.Map<AIChatState.Step, JoybotChatViews.Card> cardForStep =
            new java.util.IdentityHashMap<>();
    /** The opener last said per project, so reopening the chat doesn't repeat itself. */
    private static final java.util.Map<String, String> LAST_OPENER = new java.util.HashMap<>();

    private static final String PREF_MODEL = "ai_model";
    private static final String PREF_PROVIDER = "ai_provider";

    /** The free router: a new user's first experience costs nothing. They pick a paid model later. */
    private static final String DEFAULT_MODEL = "openrouter/free";

    private LinearLayout messagesContainer;
    private EditText inputField;
    private ImageButton sendButton;
    private ScrollView scrollContainer;
    private LinearLayout tickerContainer;
    private TextView modelLabel;
    private final List<View> messageViews = new ArrayList<>();

    // Status bar UI
    private View statusBar;
    private TextView statusText;
    private TextView statusPercent;
    private ProgressBar statusSpinner;
    private ProgressBar statusProgress;
    private LinearLayout checklistContainer;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideStatusRunnable = () -> statusBar.setVisibility(View.GONE);

    private String apiKey;
    /** A picture attached and waiting for the person to say what to do with it ("data:" URL). */
    @Nullable private String stagedImage;
    private int stagedW, stagedH;
    private String model;
    private String projectId;

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();

    // Static message log — survives Activity recreation so the user sees
    // their conversation when they reopen the chat. Capped at 200 entries
    // to prevent unbounded memory growth.
    private static final int MAX_MESSAGE_LOG = 200;
    /** Each conversation's shown messages ([role, text]), by {@link JoybotAgent#conversationKey}. */
    private static final java.util.Map<String, List<String[]>> MESSAGE_LOGS = new java.util.HashMap<>();
    /** This screen's conversation's shown messages (set in onCreate). */
    private List<String[]> messageLog = new ArrayList<>(); // [role, text]

    // Static conversation history — survives Activity recreation so the
    // conversation persists while the app is open, even if the user closes
    // and reopens the chat window.
    // It lives in JoybotAgent: a request the model is carrying out outlives this screen.
    // Each project (or room, with none open) has its own (JoybotAgent.history): a request still
    // running for one project never talks into another's chat (audit 2026-10-18 J4).
    private List<JSONObject> conversationHistory = new ArrayList<>();
    /** This screen's conversation ({@link JoybotAgent#conversationKey}). */
    private String chatKey = "";

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fade_out_quick, R.anim.fade_out_quick);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make the window translucent so the editor is faintly visible behind
        // the chat — keeps the user mentally "in" the editor.
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.dimAmount = 0.75f;
        getWindow().setAttributes(params);
        setContentView(R.layout.activity_chat_assistant);
        overridePendingTransition(R.anim.slide_down_in, R.anim.fade_out_quick);

        projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        if (projectId != null && projectId.isEmpty()) projectId = null;
        try {
            String r = getIntent().getStringExtra(EXTRA_ROOM);
            if (r != null) room = IdeaEngine.Room.valueOf(r);
        } catch (IllegalArgumentException ignored) { }
        ideaMemory = new IdeaMemory(this);
        com.fadcam.ui.faditor.ai.joybot.TipUsage.openedIn(this, room);
        // The conversation is kept while the app is open, one per project (or room): opening
        // Joybot about a different project shows that project's talk, never the last one's.
        chatKey = JoybotAgent.conversationKey(projectId, room);
        conversationHistory = JoybotAgent.history(chatKey);
        synchronized (MESSAGE_LOGS) {
            List<String[]> log = MESSAGE_LOGS.get(chatKey);
            if (log == null) {
                log = new ArrayList<>();
                MESSAGE_LOGS.put(chatKey, log);
            }
            messageLog = log;
        }

        AIChatState.chatActive = true;

        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        apiKey = AiKey.get(this);
        model = prefs.sharedPreferences.getString(PREF_MODEL, DEFAULT_MODEL);

        initViews();
        initSystemPrompt();
        setupFlow = new com.fadcam.ui.faditor.ai.joybot.SetupFlow(setupHost());
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            inputField.post(this::showSettingsDialog);
        }

        // Restore previous messages if returning to an existing conversation
        if (!messageLog.isEmpty()) {
            restoreMessages();
        } else {
            showWelcome();
        }

        // Check if a job was running before process death
        AIJobStore jobStore = new AIJobStore(this);
        if (jobStore.isJobActive() && !jobStore.isStale()) {
            String task = jobStore.getTaskDescription();
            int progress = jobStore.getProgress();
            showStatusProgress(task != null ? task : "Resuming...", progress);
            addBotMessage("A background AI job was interrupted. The service is still running — "
                    + "your progress (" + progress + "%) has been preserved.");
        } else if (jobStore.isJobActive()) {
            jobStore.clearJob();
        }

        setupActor();
        JoybotJobs.attach(jobListener);
        if (JoybotJobs.isRunning()) {
            setBusy(true);
            actor.setMood(JoybotView.Mood.WORK);
            showStatus("Still working…");
        }
        // A request the model is carrying out keeps going with the chat closed: pick it back up.
        JoybotAgent.attach(agentListener);
        JoybotAgent.Ask running = JoybotAgent.liveFor(chatKey);
        if (running != null) {
            setBusy(true);
            actor.setMood(JoybotView.Mood.WORK);
            addBotMessage("Still working on \u201c" + JoybotAgent.shortOf(running.text) + "\u201d\u2026");
        }
        showFinishedWhileAway();
        offerCarryOn();
        offerBringIn();
        // A plan still waiting for Go ahead: its card isn't in the saved words, so show it again.
        JoybotAgent.Plan waitingPlan = JoybotAgent.pendingPlan(projectId);
        if (waitingPlan != null && !planCardShown && JoybotAgent.liveFor(chatKey) == null) addPlanCard(waitingPlan);
        // The last Director run's Review: Show me opens the editor and closes this screen, and the
        // card is not in the saved words, so bring it back (rows already put back stay grey).
        List<JoybotAgent.Change> lastReview = JoybotAgent.pendingReview(projectId);
        if (lastReview != null && !reviewCardShown && projectId != null && JoybotAgent.liveFor(chatKey) == null) {
            addReviewCard(projectId, lastReview);
        }
        refreshIdeas(true);
    }

    private void restoreMessages() {
        // Copy to avoid ConcurrentModificationException if the background
        // AI thread modifies messageLog while we're iterating.
        List<String[]> snapshot;
        synchronized (messageLog) {
            snapshot = new ArrayList<>(messageLog);
            // Replaying re-logs every message, so the log starts empty: it used to be replayed ON
            // TOP of itself and doubled at every reopen (two "Hi, I'm Joybot" on the Note 9).
            messageLog.clear();
        }
        for (String[] msg : snapshot) {
            if ("user".equals(msg[0])) {
                addUserMessage(msg[1]);
            } else if ("tool".equals(msg[0])) {
                addToolResultMessage(msg[1]);   // a quiet tool line again, not a speech bubble
            } else {
                addBotMessage(msg[1]);
            }
        }
    }

    private void initViews() {
        messagesContainer = findViewById(R.id.chat_messages);
        inputField = findViewById(R.id.chat_input);
        sendButton = findViewById(R.id.chat_send);
        scrollContainer = findViewById(R.id.chat_scroll);
        tickerContainer = findViewById(R.id.chat_ticker);
        rebuildTicker();

        statusBar = findViewById(R.id.chat_status_bar);
        statusText = findViewById(R.id.chat_status_text);
        statusPercent = findViewById(R.id.chat_status_percent);
        statusSpinner = findViewById(R.id.chat_status_spinner);
        statusProgress = findViewById(R.id.chat_status_progress);
        checklistContainer = findViewById(R.id.chat_checklist);

        sendButton.setOnClickListener(v -> sendMessage());
        findViewById(R.id.chat_back).setOnClickListener(v -> finish());

        modelLabel = findViewById(R.id.chat_model_label);
        modelLabel.setOnClickListener(v -> showModelSwitcher());
        androidx.appcompat.widget.TooltipCompat.setTooltipText(modelLabel,
                "Switch between your starred models, or find more");
        updateModelLabel();

        ImageButton btnAttach = findViewById(R.id.chat_attach);
        btnAttach.setOnClickListener(v -> showAttachChooser());

        ImageButton btnSettings = findViewById(R.id.chat_settings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        ImageButton btnMic = findViewById(R.id.chat_mic);
        voice = new JoybotVoice(this);
        addReadAloudToggle();
        // Dictation: tap to talk, words collect across pauses, tap again to stop. Never sends.
        btnMic.setOnClickListener(v -> toggleDictation());
        // Hold the mic: say it and it sends itself when you stop talking, and hands-free turns on.
        btnMic.setOnLongClickListener(v -> { dictateAndSend(); return true; });

        actor = findViewById(R.id.chat_actor);
        chipsScroll = findViewById(R.id.chat_chips_scroll);
        chipsRow = findViewById(R.id.chat_chips);
        stopButton = findViewById(R.id.chat_stop);
        stopButton.setOnClickListener(v -> stopWorking());

        // Every button says what it does when pointed at (owner rule: stylus and mouse users
        // learn the app by hovering).
        androidx.appcompat.widget.TooltipCompat.setTooltipText(sendButton, "Send your message to Joybot");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(btnMic,
                "Talk instead of typing: tap to start, tap again to stop, then press Send. "
                        + "Hold it to talk hands-free: it sends itself when you stop talking");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(btnAttach, "Show Joybot a picture or the frame you're on");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(btnSettings, "AI service, key and model");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(findViewById(R.id.chat_back), "Close Joybot");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(stopButton, "Stop what Joybot is doing");

        // Mascot bounces in when the chat window lands. scaleX animates 0 -> -1 (not
        // 0 -> 1) to preserve the inward-facing horizontal flip set in the layout.
        View headerIcon = findViewById(R.id.chat_header_ai_icon);
        this.headerIcon = headerIcon;
        if (headerIcon != null) {
            headerIcon.setScaleX(0f);
            headerIcon.setScaleY(0f);
            headerIcon.animate()
                    .scaleX(-1f)
                    .scaleY(1f)
                    .setStartDelay(120)
                    .setDuration(500)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                    .start();
        }
    }

    /** Resolve a theme color attribute (e.g. colorPrimary) so icon tints genuinely follow the theme. */
    private int resolveThemeColor(int attrResId) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrResId, tv, true);
        return tv.data;
    }

    /** Show the status bar with a tool name. Auto-hides after 3s when idle. */
    private void showStatus(@NonNull String tool) {
        runOnUiThread(() -> {
            statusBar.setVisibility(View.VISIBLE);
            statusSpinner.setVisibility(View.VISIBLE);
            statusText.setText("🔧 " + tool);
            statusPercent.setText("");
            statusProgress.setVisibility(View.GONE);
            checklistContainer.setVisibility(View.GONE);
            statusHandler.removeCallbacks(hideStatusRunnable);
        });
    }

    /** Show status with a progress percentage (0-100, -1 = indeterminate). */
    private void showStatusProgress(@NonNull String tool, int percent) {
        runOnUiThread(() -> {
            statusBar.setVisibility(View.VISIBLE);
            statusSpinner.setVisibility(View.GONE);
            statusText.setText("🔧 " + tool);
            if (percent >= 0) {
                statusPercent.setText(percent + "%");
                statusProgress.setVisibility(View.VISIBLE);
                statusProgress.setProgress(percent);
            } else {
                statusPercent.setText("");
                statusProgress.setVisibility(View.VISIBLE);
                statusProgress.setIndeterminate(true);
            }
            statusHandler.removeCallbacks(hideStatusRunnable);
        });
    }

    /** Show a task checklist for multi-step AI jobs. */
    private void showChecklist(@NonNull String[] tasks) {
        runOnUiThread(() -> {
            checklistContainer.setVisibility(View.VISIBLE);
            checklistContainer.removeAllViews();
            for (int i = 0; i < tasks.length; i++) {
                TextView item = new TextView(this);
                item.setText("☐ " + tasks[i]);
                item.setTextColor(Studio.INK_FAINT);
                item.setTextSize(12);
                item.setPadding(0, 2, 0, 2);
                item.setTag(i);
                checklistContainer.addView(item);
            }
        });
    }

    /** Mark a checklist item as done. */
    private void checkChecklistItem(int index, boolean done) {
        runOnUiThread(() -> {
            if (index < checklistContainer.getChildCount()) {
                TextView item = (TextView) checklistContainer.getChildAt(index);
                item.setText((done ? "☑ " : "☐ ") + item.getText().subSequence(2, item.getText().length()));
                item.setTextColor(done ? Studio.GO : Studio.INK_FAINT);
            }
        });
    }

    /** Hide the status bar after a delay. */
    private void hideStatus() {
        runOnUiThread(() -> {
            statusSpinner.setVisibility(View.GONE);
            statusText.setText("✓ Done");
            statusHandler.postDelayed(hideStatusRunnable, 2000);
        });
    }

    private void markChecklistFromTask(@NonNull String task) {
        String lower = task.toLowerCase();
        if (lower.contains("vosk")) checkChecklistItem(0, true);
        else if (lower.contains("whisper")) checkChecklistItem(1, true);
        else if (lower.contains("silence")) checkChecklistItem(2, true);
        else if (lower.contains("synthes")) checkChecklistItem(3, true);
        else if (lower.contains("filler") || lower.contains("cutting")) checkChecklistItem(4, true);
    }

    private AIToolExecutor createExecutor() {
        return new AIToolExecutor(this, projectId, (task, percent) -> {
            runOnUiThread(() -> {
                showStatusProgress(task, percent);
                markChecklistFromTask(task);
            });
        });
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static final int VOICE_REQUEST_CODE = 1001;
    private static final int IMAGE_PICK_REQUEST_CODE = 1002;

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Tell Joybot what you'd like");
            actor.setMood(JoybotView.Mood.LISTEN);
            startActivityForResult(intent, VOICE_REQUEST_CODE);
        } catch (Exception e) {
            addBotMessage("Voice input not available: " + e.getMessage());
        }
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, IMAGE_PICK_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQUEST_CODE && actor != null) actor.setMood(JoybotView.Mood.IDLE);
        if (requestCode == VOICE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> results = data
                    .getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                // Added to what is already in the box, never sent: the owner finishes the thought
                // and presses Send (2026-10-14: auto-send fired on every breath).
                String had = inputField.getText().toString().trim();
                inputField.setText(had.isEmpty() ? results.get(0) : had + " " + results.get(0));
                inputField.setSelection(inputField.getText().length());
            }
        } else if (requestCode == IMAGE_PICK_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) {
                try {
                    // Read the image into a bitmap and show it as an attached image message
                    InputStream in = getContentResolver().openInputStream(imageUri);
                    if (in != null) {
                        Bitmap bitmap = BitmapFactory.decodeStream(in);
                        in.close();
                        if (bitmap != null) {
                            addImageMessage(bitmap, imageUri);
                        } else {
                            addBotMessage("Could not decode the selected image.");
                        }
                    }
                } catch (Exception e) {
                    addBotMessage("Error reading image: " + e.getMessage());
                }
            }
        }
    }

    // ── Attaching what the AI should LOOK at ────────────────────────────────────────────────

    /**
     * Timeline position the editor was sitting at when it opened this chat, in absolute ms.
     * Lets "send the current frame" mean the frame the user is actually looking at.
     */
    public static final String EXTRA_PLAYHEAD_MS = "chat_playhead_ms";

    /**
     * Attach chooser: a picked image, or the video frame at the playhead.
     *
     * <p>A chooser rather than a second button because {@code res/} is another agent's live file
     * under the working protocol, and a long-press would hide the feature behind a gesture
     * nothing advertises — the same discoverability trap the dope sheet was in.</p>
     *
     * <p>With no project attached there is no frame to send, so this degrades to the picker it
     * has always been rather than offering an option that cannot work.</p>
     */
    private void showAttachChooser() {
        if (projectId == null || projectId.isEmpty()) { pickImage(); return; }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Show the assistant…")
                .setItems(new String[]{"🎞  This frame (at the playhead)", "🖼  Pick an image…"},
                        (d, which) -> {
                            if (which == 0) attachFrameAtPlayhead(); else pickImage();
                        })
                .show();
    }

    /**
     * Extract the video frame under the playhead and attach it.
     *
     * <p><b>What this is and is not.</b> It is the SOURCE video frame at that moment — the
     * picture the clip contributes. It is not the fully composed frame: overlays, text, sprites
     * and image sequences are drawn by the export compositor, and reproducing that here would
     * mean running it offscreen. So the assistant can answer "is this shot in focus / what is
     * happening here / what colour is the wall", and cannot yet answer "does my caption read
     * over this". That second one is the natural follow-up and wants the compositor, not a
     * bigger screenshot.</p>
     *
     * <p>Time mapping goes through {@code Clip.mapToSourceMs} — the same authority the
     * thumbnails, the seek path and the export all use — so the frame the AI sees is the frame
     * the editor would show, including trims, speed and loop repeats.</p>
     */
    private void attachFrameAtPlayhead() {
        final long playheadMs = getIntent().getLongExtra(EXTRA_PLAYHEAD_MS, 0L);
        addBotMessage("Grabbing the frame…");
        aiExecutor.execute(() -> {
            android.media.MediaMetadataRetriever mmr = null;
            Bitmap frame = null;
            String problem = null;
            try {
                com.fadcam.ui.faditor.project.ProjectStorage storage =
                        new com.fadcam.ui.faditor.project.ProjectStorage(this);
                com.fadcam.ui.faditor.model.FaditorProject proj = storage.load(projectId);
                com.fadcam.ui.faditor.model.Timeline tl =
                        proj == null ? null : proj.getTimeline();
                if (tl == null || tl.getClipCount() == 0) {
                    problem = "There are no clips in this project yet.";
                } else {
                    // Walk the spine the way the timeline does: each clip occupies its VISUAL
                    // duration (trim + loop extension), and the playhead lands in exactly one.
                    int idx = -1;
                    long acc = 0, localMs = 0;
                    for (int i = 0; i < tl.getClipCount(); i++) {
                        com.fadcam.ui.faditor.model.Clip c = tl.getClip(i);
                        if (c == null) continue;
                        long dur = Math.max(1, c.getVisualDurationMs());
                        if (playheadMs < acc + dur || i == tl.getClipCount() - 1) {
                            idx = i;
                            localMs = Math.max(0, Math.min(dur - 1, playheadMs - acc));
                            break;
                        }
                        acc += dur;
                    }
                    com.fadcam.ui.faditor.model.Clip clip = idx < 0 ? null : tl.getClip(idx);
                    if (clip == null || clip.getSourceUri() == null) {
                        problem = "I couldn't find a clip at the playhead.";
                    } else {
                        long srcMs = clip.mapToSourceMs(localMs);
                        mmr = new android.media.MediaMetadataRetriever();
                        mmr.setDataSource(this, clip.getSourceUri());
                        // OPTION_CLOSEST is worth the extra decode here: OPTION_CLOSEST_SYNC can
                        // land seconds away on a sparsely-keyframed clip, and "the frame at the
                        // playhead" showing a different shot entirely is the one failure that
                        // would make the whole feature untrustworthy.
                        frame = mmr.getFrameAtTime(srcMs * 1000L,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST);
                        if (frame == null) problem = "That clip wouldn't give me a frame.";
                    }
                }
            } catch (Exception | OutOfMemoryError e) {
                problem = "I couldn't read that frame.";
            } finally {
                if (mmr != null) try { mmr.release(); } catch (Exception ignored) { }
            }
            final Bitmap got = frame;
            final String err = problem;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) { if (got != null) got.recycle(); return; }
                if (got == null) {
                    updateLastBotMessage(err != null ? err : "I couldn't read that frame.");
                    return;
                }
                // Bound it before it becomes base64: a 4K frame is ~8MB of pixels and several
                // MB of JPEG, and vision models gain nothing from the extra resolution.
                Bitmap sized = scaleForVision(got);
                if (sized != got) got.recycle();
                updateLastBotMessage("Here's the frame at "
                        + String.format(java.util.Locale.US, "%.2fs", playheadMs / 1000f) + ".");
                addFrameMessage(sized, playheadMs);
            });
        });
    }

    /** Longest-edge cap for anything sent to a vision model. */
    private static final int VISION_MAX_DIM = 1024;

    @NonNull
    private static Bitmap scaleForVision(@NonNull Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= VISION_MAX_DIM) return src;
        float s = VISION_MAX_DIM / (float) longest;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    /**
     * Show an extracted frame as a user message and send it for analysis.
     *
     * <p>Mirrors {@link #addImageMessage} but takes no URI — this bitmap came from a decode, not
     * from a document the user picked, so there is no grant to persist and nothing to re-open.
     */
    private void addFrameMessage(@NonNull Bitmap frame, long playheadMs) {
        android.widget.LinearLayout wrapper = new android.widget.LinearLayout(this);
        wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrapper.setPadding(dp(12), dp(8), dp(12), dp(8));
        wrapper.setTag("user");

        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(frame);
        iv.setAdjustViewBounds(true);
        iv.setMaxHeight(dp(240));
        iv.setPadding(0, 0, 0, dp(4));
        wrapper.addView(iv);

        TextView caption = new TextView(this);
        caption.setText(String.format(java.util.Locale.US,
                "[Frame at %.2fs] %d×%d", playheadMs / 1000f,
                frame.getWidth(), frame.getHeight()));
        caption.setTextColor(Studio.INK_FAINT);
        caption.setTextSize(12);
        wrapper.addView(caption);

        messagesContainer.addView(wrapper);
        messageViews.add(wrapper);
        addTickerTick(true);
        scrollToBottom();

        if (inputField.getText().toString().trim().isEmpty()) {
            inputField.setText("What do you see in this frame?");
        }

        if (!AiService.ready(this, apiKey)) {
            addBotMessage("Connect an API key (top-right settings) and I can look at this.");
            return;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        frame.compress(Bitmap.CompressFormat.JPEG, 85, baos);
        String base64 = android.util.Base64.encodeToString(
                baos.toByteArray(), android.util.Base64.NO_WRAP);
        // Say so plainly when the connected model has no eyes, rather than sending an image into
        // a text-only model and reporting whatever it hallucinates about a picture it never saw.
        if (visionOf(model) == ModelCapabilities.Vision.NO) {
            addBotMessage("Heads up: \"" + model + "\" doesn't accept images, so I'll be "
                    + "answering without actually seeing this. Pick a model with the 👁 for a "
                    + "real look.");
        }
        sendVisionMessage(base64, frame.getWidth(), frame.getHeight());
    }

    /** Show an attached image as a user message and send it to the AI for vision analysis. */
    private void addImageMessage(@NonNull Bitmap bitmap, @NonNull Uri imageUri) {
        // Take a temporary persistence grant so the URI stays readable
        try {
            getContentResolver().takePersistableUriPermission(imageUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }

        // Show the attached image inline
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundResource(R.drawable.chat_bubble_user);
        wrapper.setTag("user");
        wrapper.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(60), dp(4), dp(8), dp(4));
        wrapper.setLayoutParams(lp);

        // Image preview (thumbnail, max 240dp)
        ImageView iv = new ImageView(this);
        int maxDim = dp(120);
        float scale = Math.min((float) maxDim / bitmap.getWidth(), (float) maxDim / bitmap.getHeight());
        Bitmap thumb = Bitmap.createScaledBitmap(bitmap,
                Math.max(1, (int) (bitmap.getWidth() * scale)),
                Math.max(1, (int) (bitmap.getHeight() * scale)), true);
        iv.setImageBitmap(thumb);
        iv.setAdjustViewBounds(true);
        iv.setMaxHeight(dp(240));
        iv.setPadding(0, 0, 0, dp(4));
        wrapper.addView(iv);

        // Caption text
        TextView caption = new TextView(this);
        caption.setText("Picture attached");
        caption.setTextColor(Studio.INK_FAINT);
        caption.setTextSize(12);
        wrapper.addView(caption);

        messagesContainer.addView(wrapper);
        messageViews.add(wrapper);
        addTickerTick(true);
        scrollToBottom();

        if (!AiService.ready(this, apiKey)) {
            addBotMessage("I can see a picture is attached. Connect an AI in ⚙ (there's a free"
                    + " one) and I can look at it and work with it.");
            bitmap.recycle();
            return;
        }
        // STAGED, NOT SENT. Attaching used to send "Describe what's in this image." at once, so
        // the person never got to say what the picture was FOR ("label these expressions", "use
        // this as the thumbnail"). Now the picture waits and rides along with the next message.
        // Long edge capped at 2048: plenty for a sprite sheet's cells, and a 12 MP photo would
        // otherwise be ~4 MB of base64 on one request.
        int longEdge = Math.max(bitmap.getWidth(), bitmap.getHeight());
        Bitmap send = bitmap;
        if (longEdge > 2048) {
            float k = 2048f / longEdge;
            send = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * k)),
                    Math.max(1, Math.round(bitmap.getHeight() * k)), true);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        send.compress(Bitmap.CompressFormat.JPEG, 88, baos);
        stagedImage = "data:image/jpeg;base64,"
                + android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
        stagedW = send.getWidth();
        stagedH = send.getHeight();
        if (send != bitmap) send.recycle();
        bitmap.recycle();
        if (visionOf(model) == ModelCapabilities.Vision.NO) {
            addBotMessage("Got it. Heads up: \"" + model + "\" can't see pictures — pick a model"
                    + " with the 👁 at the top first. Then tell me what to do with it.");
        } else {
            addBotMessage("Got it. What should I do with it? (Send with nothing typed and I'll just"
                    + " tell you what I see.)");
        }
        inputField.setText("");
        inputField.setHint("What should I do with this picture?");
        inputField.requestFocus();
    }

    /** Send the frame (base64 JPEG) with whatever is typed, to the model, which can act on it. */
    private void sendVisionMessage(@NonNull String base64Image, int imgW, int imgH) {
        // View state must be read on the main thread — capture the prompt here.
        String typed = inputField.getText().toString().trim();
        inputField.setText("");
        askAgent(typed.isEmpty() ? "What do you see here?" : typed,
                "data:image/jpeg;base64," + base64Image, imgW, imgH);
    }

    private void initSystemPrompt() {
        // Only build the system prompt once — if the conversation already
        // has entries, the user is returning to an existing chat session.
        if (!conversationHistory.isEmpty()) return;

        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");

            StringBuilder sb = new StringBuilder();
            // The SYSTEM PROMPT, which is the deepest place the old name could hide: it is
            // not a label on a screen, it is what the model believes it is. Told it was
            // FadCam AI, it introduced itself as FadCam in answers nobody had reviewed —
            // a brand leak that regenerates itself on every reply.
            sb.append(PERSONA);
            sb.append(HelpBook.forModel()).append('\n');

            sb.append(AIToolExecutor.getToolDescriptions());
            sb.append("\nWhen you want to DO something, call a tool. ");
            sb.append("When you want to EXPLAIN something, just reply normally.\n\n");

            sb.append("For a batch of edits in one step, call apply_edit_script with a script like this"
                    + " (as the \"script\" argument, a JSON string):\n");
            sb.append("```json\n");
            sb.append("{\"version\":1,\"description\":\"...\",\"operations\":[");
            sb.append("{\"type\":\"REMOVE_SPAN\",\"clipId\":\"...\",\"startMs\":0,\"endMs\":0},");
            sb.append("{\"type\":\"ADD_TEXT_OVERLAY\",\"text\":\"...\",\"centerX\":0.5,\"centerY\":0.5,\"sizeFraction\":0.1,\"startMs\":0,\"endMs\":999999},");
            sb.append("{\"type\":\"SET_CLIP_SPEED\",\"clipId\":\"...\",\"speed\":1.5},");
            sb.append("{\"type\":\"SET_CLIP_MUTED\",\"clipId\":\"...\",\"muted\":true},");
            sb.append("{\"type\":\"ADD_OPACITY_KEYFRAME\",\"overlayId\":\"...\",\"timelineMs\":0,\"opacity\":0.0}");
            sb.append("]}\n```\n\n");

            sb.append("Supported operation types: ");
            sb.append("REMOVE_SPAN, ADD_TEXT_OVERLAY, REMOVE_TEXT_OVERLAY, ");
            sb.append("SET_OVERLAY_RANGE, SET_OVERLAY_POSITION, SET_OVERLAY_TEXT, ");
            sb.append("SET_CLIP_SPEED, SET_CLIP_VOLUME, SET_CLIP_MUTED, ");
            sb.append("SET_CAPTIONS_ENABLED, SET_CAPTION_STYLE, SET_CANVAS_PRESET, ");
            sb.append("SET_EXPORT_SETTING, MOVE_KEYFRAME, ADD_KEYFRAME, ");
            sb.append("ADD_OPACITY_KEYFRAME, CLEAR_KEYFRAMES.\n\n");

            // The project itself is NOT written here any more. It used to be, once, when the chat
            // opened — so after the first tool the model was reasoning about a project that no
            // longer existed, and every word of every transcript (with timings) rode along on
            // every request. A fresh, compact context is now added to each request instead
            // (freshContext); full word timings come from get_transcript when needed.

            systemMsg.put("content", sb.toString());
            conversationHistory.add(systemMsg);
        } catch (Exception ignored) { }
    }

    private void showWelcome() {
        // Every empty open teaches one thing, offline too (owner, 10-28): "Did you know…", leaning
        // to the wings this person uses, then the idea buttons below.
        com.fadcam.ui.faditor.ai.joybot.TipBook.Tip tip = com.fadcam.ui.faditor.ai.joybot.TipUsage.next(this);
        String did = com.fadcam.ui.faditor.ai.joybot.TipBook.said(tip);
        if (!AiService.ready(this, apiKey)) {
            // Offline is not a lesser Joybot: everything the buttons do runs on the phone.
            addBotMessage("Hi, I'm Joybot. " + did + "\n\nRight here on the phone I can transcribe, "
                    + "add captions, cut the ums and silences, add transitions and make slides — tap a "
                    + "button below. Connect an AI (there's a free one) and I can take on anything you "
                    + "can describe: tap “Connect an AI” and I'll walk you through it.");
        } else {
            addBotMessage("Hi, I'm Joybot. " + did + "\n\nTap a button below, or tell me what you'd like to make.");
        }
        addTakeMeThere(tip);
    }

    /** A small "Take me there" under the tip, at its right edge; none when the tip is about this room. */
    private void addTakeMeThere(@NonNull com.fadcam.ui.faditor.ai.joybot.TipBook.Tip tip) {
        boolean connect = !AiService.ready(this, apiKey);
        boolean go = tip.route != null && !tip.route.equals(routeOfRoom(room));
        if (!connect && !go) return;
        String where = tip.route;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, dp(60), dp(4));
        row.setLayoutParams(lp);
        if (connect) {
            row.addView(JoybotChatViews.cardButton(this, "Connect an AI",
                    "I'll walk you through it, step by step", v -> {
                        addUserMessage("Connect an AI");
                        setupFlow.start();
                    }));
        }
        if (go) {
            row.addView(JoybotChatViews.cardButton(this, "Take me there",
                    "Open the part of the app this tip is about", v -> route(where)));
        }
        messagesContainer.addView(row);
        messageViews.add(row);
    }

    /** The route() name of the room Joybot was opened in, or null. */
    @Nullable
    private static String routeOfRoom(@NonNull IdeaEngine.Room r) {
        switch (r) {
            case STUDIO: return "studio";
            case VISLAB: return "vislab";
            case JOYBRUSH: return "joybrush";
            case SPRITELAB: return "spritelab";
            case LIBRARY: return IdeaEngine.ROUTE_LIBRARY;
            default: return null;
        }
    }

    /** Who Joybot is, for the model. The project itself arrives fresh with every request. */
    private static final String PERSONA = """
            You are Joybot, the friendly helper inside Joy Creator, a creative studio for Android \
            phones and tablets. Its wings: Studio (the video and sound editor: timeline, clips, \
            transcripts, captions, text and picture layers, keyframes, transitions, visualizers, \
            animated slides, export), JoyBrush (painting, illustration and frame-by-frame \
            animation), SpriteLab (cut sprite sheets into named cells and animations), Avatar \
            Studio (puppet characters driven by face tracking and the mic), Capture (screen and \
            camera recording) and the Library (recordings and exports). People often use it \
            one-handed and in a hurry.

            How you talk: short, warm, plain words. No jargon, no code, no ids in what you say — \
            call a clip by its name. Never invent a number: quote only numbers that appear in the \
            project context or in a tool result. If something can't be done, say so in one \
            sentence and offer the nearest thing that can.

            How you act: when the person wants something DONE to the project, do it with a tool. \
            To call a tool, reply with ONLY one JSON object, {"tool":"name","args":{...}}, and \
            nothing else. After a tool result, say in one or two sentences what changed. Call \
            several tools one at a time. For big or destructive changes (deleting clips, \
            reordering, dropping parts) propose first and wait for a yes, unless they asked for \
            exactly that. Every request you carry out is ONE undo step for them, however many \
            tools it took: one Undo takes back all of it. Never say each change is its own undo step.

            You can SEE the project: the look tool shows you a sprite sheet (cells numbered), a \
            picture layer, a video frame, or the finished video at any moment exactly as it \
            exports (titles and layers included). Before naming, labelling, describing or choosing \
            anything visual, look — never ask the person what is in a picture you can look at. \
            Then act: if they asked for names, apply them with the tool, don't just list them.

            Words that matter: "this" / "the selected one" means the item marked SELECTED in the \
            project context; "here" / "now" means the playhead. Call things by their names in the \
            context ("Cat 2"), and use their ids in tools. Fade HANDLES (set_fades) fade one item in \
            from black (a layer: from nothing) at its own start and out at its own end; TRANSITIONS \
            (add_transition, remove_transitions) blend BETWEEN two clips at a seam. If someone \
            says "fades" and both kinds exist, ask which they mean. "All my clips" means the main \
            track (which=clips); "everything" includes layers and sound.

            Adding something new (a picture, a clip, a slide) can happen two ways, for two different \
            jobs: IN LINE as a clip on the main track — the video gets longer and everything after \
            it moves later, good for a title card or a cut-away; or ON TOP as a layer over what is \
            already there — the length stays the same, good for logos, stickers, picture-in-picture \
            and titles over footage. Unless they already said which, ask, in one short sentence.

            For a BIG job (more than about four changes, or anything over the whole video), call \
            propose_plan first with a title and short plain steps, then stop and wait. When the \
            person says go, you get many more steps: do all of it without asking again, then sum \
            up what you changed.

            """;

    private void sendMessage() {
        boolean follow = handsFreeFollow;
        handsFreeFollow = false;
        stopDictation();
        String text = inputField.getText().toString().trim();
        if (text.isEmpty() && stagedImage == null) return;
        // An AI key typed or pasted here is caught on the phone first: it never reaches the AI, the
        // conversation or the message log; the bubble keeps only its last four letters (BOT-6, 10-28).
        com.fadcam.ui.faditor.ai.joybot.KeyCatcher.Found typedKey = com.fadcam.ui.faditor.ai.joybot.KeyCatcher.find(text, setupFlow.expectingKey());
        if (typedKey != null) {
            inputField.setText("");
            dictatedText = null;
            addUserMessage(typedKey.shown);
            setupFlow.gotKey(typedKey);
            return;
        }
        if (busy) {
            Toast.makeText(this, "Joybot is still working — tap Stop first", Toast.LENGTH_SHORT).show();
            return;
        }
        // Hands-free: dictated and sent unedited = sent by voice; so is a voice answer's new request.
        boolean bySpeech = follow || (dictatedText != null && dictatedText.equals(text));
        if (!follow) handsFreeUsed = false;
        dictatedText = null;
        handsFree = bySpeech;

        voice.hush();
        // A staged picture goes with these words, straight to the model: it is what can see it,
        // and it can act on it with the same tools as any other request.
        if (stagedImage != null) {
            String image = stagedImage;
            stagedImage = null;
            inputField.setHint("Ask anything…");
            if (text.isEmpty()) text = "What do you see in this picture?";
            addUserMessage(text);
            inputField.setText("");
            // A PICTURE REQUEST with a picture attached ("make this sketch a finished cover"): the
            // picture is the reference for the new one, not something to describe.
            String subject = ModelCatalog.talkingToPictures(this) ? text : pictureSubject(text);
            if (subject != null && projectId != null && JoybotPictures.canGenerate(this)) {
                pictureReference = image;
                if (ModelCatalog.talkingToPictures(this)) makePicture(text); else startPictureFlow(subject);
                return;
            }
            askAgent(text, image, stagedW, stagedH);
            return;
        }
        addUserMessage(text);
        inputField.setText("");

        // Mid-slide: typing answers the question on screen.
        if (slideFlow != null) {
            answerSlide(text);
            return;
        }
        if (awaitingPictureSubject) {
            awaitingPictureSubject = false;
            offerPictureWays(text);
            return;
        }
        // Talking to a picture model: every message is a picture request.
        if (ModelCatalog.talkingToPictures(this) && projectId != null) {
            if (JoybotPictures.canGenerate(this)) { makePicture(text); return; }
            addBotMessage("Making pictures needs an OpenRouter key. Tap the model name at the top to talk normally.");
            return;
        }
        String pic = pictureSubject(text);
        if (pic != null) {
            startPictureFlow(pic);
            return;
        }
        // "Undo that" means Joybot's newest change, online or off.
        if (norm(text).matches("(please )?(undo|undo that|undo it|take (that|it) back|put it back|revert( that)?)( please)?")) {
            undoByWords();
            return;
        }
        // "Connect an AI", "I have Claude Pro": Joybot walks them through it, here in the chat.
        if (com.fadcam.ui.faditor.ai.joybot.SetupTalk.asksToConnect(text)) {
            setupFlow.start();
            return;
        }
        // "What can I do here?": this room's real actions, in plain words (BOT-2), online or off.
        if (com.fadcam.ui.faditor.ai.joybot.WhatHere.asks(text)) {
            addBotMessage(com.fadcam.ui.faditor.ai.joybot.WhatHere.answer(room, projectId != null, AiService.ready(this, apiKey), roomActions()));
            return;
        }
        boolean online = AiService.ready(this, apiKey);
        // A sentence that IS one of the buttons runs it directly (free, instant, offline). Online,
        // only an exact match does — anything with more to it deserves the model's reading.
        Idea here = thisHereIdea(text);
        if (here != null) {
            runIdea(here, false);
            return;
        }
        Idea match = matchIdea(text, !online);
        if (match != null) {
            runIdea(match, false);
            return;
        }
        // "Help me make a slide" with nothing more to go on gets the four questions; a detailed
        // request ("a bold title that says Beach Day") goes to the model, which can build it.
        if (projectId != null && wantsSlide(text) && (!online || text.split("\\s+").length <= 6)) {
            startSlideFlow(null);
            return;
        }
        // "How do I…" with a known answer: instant and free, online or off.
        // Online, only plain "how do I / where is" questions: anything else deserves the model.
        if ((online ? HelpBook.isHowTo(text) : HelpBook.isQuestion(text)) && answerFromHelpBook(text)) return;
        if (!online) {
            handleOfflineQuery(text);
        } else {
            handleAiQuery(text);
        }
    }

    // ── Offline helper (no API key) ────────────────────────────────

    /**
     * A "how do I…" with a known answer: said instantly (no AI, no cost), with a way to act —
     * Joybot does it, or takes them there. True when it answered.
     */
    private boolean answerFromHelpBook(@NonNull String text) {
        HelpBook.Topic t = HelpBook.find(text);
        if (t == null) return false;
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this, t.answer, null);
        Idea doIt = null;
        if (t.doIt != null && currentOffer != null) {
            for (Idea i : currentOffer.ideas) if (i.key.equals(t.doIt)) { doIt = i; break; }
        }
        if (doIt != null) {
            final Idea run = doIt;
            card.buttons.addView(JoybotChatViews.cardButton(this, "Do it for me", run.hint,
                    v -> runIdea(run, true)));
        } else if ("slide".equals(t.doIt) && projectId != null) {
            card.buttons.addView(JoybotChatViews.cardButton(this, "Make one with me",
                    "Four quick questions and I'll build it", v -> { addUserMessage("Help me make a slide"); startSlideFlow(null); }));
        } else if ("picture".equals(t.doIt)) {
            card.buttons.addView(JoybotChatViews.cardButton(this, "Make a picture",
                    "Describe it and I'll get it made", v -> { addUserMessage("Help me make a picture"); startPictureFlow(null); }));
        }
        if (t.route != null && !(room == IdeaEngine.Room.STUDIO && "studio".equals(t.route))) {
            card.buttons.addView(JoybotChatViews.cardButton(this, "Take me there",
                    "Open the part of the app this is in", v -> route(t.route)));
        }
        // "Show me how": Joybot points at the real button in the Studio's tool strip and the person
        // taps it. Needs the Studio here, or a project to open the editor on.
        if (t.tool != null && (room == IdeaEngine.Room.STUDIO || projectId != null)) {
            final String where = t.tool;
            card.buttons.addView(JoybotChatViews.cardButton(this, "Show me how",
                    "Joybot points at the button so you can tap it yourself", v -> showHow(where)));
        }
        if (card.buttons.getChildCount() == 0) card.root.removeView(card.buttons);
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
        synchronized (messageLog) { messageLog.add(new String[]{"bot", t.answer}); trimMessageLog(); }
        // A question asked by voice is answered by voice, and then Joybot listens (hands-free).
        if (handsFree) handsFreeSay(t.answer); else voice.speak(t.answer);
        actor.setMood(JoybotView.Mood.TALK);
        actor.postDelayed(() -> actor.setMood(JoybotView.Mood.IDLE), 1200L);
        try {
            conversationHistory.add(new JSONObject().put("role", "user").put("content", text));
            conversationHistory.add(new JSONObject().put("role", "assistant").put("content", t.answer));
        } catch (Exception ignored) { }
        return true;
    }

    private void handleOfflineQuery(@NonNull String query) {
        if (answerFromHelpBook(query)) return;
        // Not something the help book or the buttons cover. Say so plainly and point at what does
        // work offline, rather than a canned paragraph that may not be about their question.
        addBotMessage("I can't work that one out without an AI connected. Right here I can do "
                + "anything on the buttons below, and answer “how do I…” questions about "
                + "the app. Connect an AI in ⚙ (there's a free one) for everything else.");
        actor.setMood(JoybotView.Mood.OOPS);
        actor.postDelayed(() -> actor.setMood(JoybotView.Mood.IDLE), 700L);
    }

    // ── Online AI query ────────────────────────────────────────────

    private void handleAiQuery(@NonNull String userText) {
        askAgent(userText, null, 0, 0);
    }

    /**
     * Hand a request to Joybot's agent, which runs it — tools and all — whether or not this
     * screen stays open; {@link #agentListener} shows it while it does.
     */
    /** An account other than OpenRouter with no model of its own chosen yet. */
    private boolean needsModelPick() {
        return AiService.isCustom(this) && (model == null || model.isEmpty() || model.equals(DEFAULT_MODEL));
    }

    private void askAgent(@NonNull String text, @Nullable String imageDataUrl, int imgW, int imgH) {
        askAgent(text, imageDataUrl, imgW, imgH, requestLabelFor(text), false, JoybotAgent.MAX_TOOL_ROUNDS);
    }

    /** {@code director}: carrying out an approved plan, with its own undo label and step budget. */
    private void askAgent(@NonNull String text, @Nullable String imageDataUrl, int imgW, int imgH,
                          @NonNull String undoLabel, boolean director, int maxSteps) {
        if (needsModelPick()) {
            // Sending "openrouter/free" to OpenAI only earns an error: ask for the model first.
            addBotMessage("Pick which " + AiAccounts.active(this).name
                    + " model to talk to first, then send that again.");
            ModelPicker.show(this, false, new ModelPicker.OnPick() {
                @Override public void chat(@NonNull ModelCatalog.Model m) { useChatModel(m.id); }
                @Override public void pictures(@NonNull ModelCatalog.Model m) { usePictureModel(m.id); }
            });
            return;
        }
        addBotMessage(imageDataUrl != null ? "Looking…" : "Thinking…");
        cancelled = false;
        setBusy(true);
        actor.setMood(JoybotView.Mood.THINK);
        JoybotAgent.Situation where = new JoybotAgent.Situation();
        where.room = room;
        where.playheadMs = getIntent().getLongExtra(EXTRA_PLAYHEAD_MS, -1);
        where.selectedId = getIntent().getStringExtra(EXTRA_SELECTED_ID);
        IdeaEngine.Offer o = currentOffer;
        if (o != null && o.hasIdea()) {
            for (Idea i : o.ideas) if (i.kind != Idea.Kind.TYPE) where.suggestions.add(i.label);
        }
        JoybotAgent.Ask ask = new JoybotAgent.Ask(projectId, text,
                model == null || model.isEmpty() ? DEFAULT_MODEL : model, apiKey == null ? "" : apiKey,
                undoLabel, where);
        ask.question = HelpBook.isQuestion(text);
        ask.imageDataUrl = imageDataUrl;
        ask.imageW = imgW;
        ask.imageH = imgH;
        ask.director = director;
        ask.maxSteps = maxSteps;
        JoybotAgent.ask(this, ask);
    }

    /** The agent's request, shown while this screen is open (attached in onCreate). */
    private final JoybotAgent.Listener agentListener = new JoybotAgent.Listener() {
        private boolean gone() { return isFinishing() || isDestroyed(); }

        @Override public String conversationKey() { return chatKey; }

        @Override public void onText(@NonNull String shown, boolean newBubble) {
            if (gone()) return;
            if (newBubble) addBotMessage(shown);
            else updateLastBotMessage(shown);
            if (!shown.startsWith("Working") && !shown.startsWith("Thinking")
                    && !shown.startsWith("Looking")) {
                actor.setMood(JoybotView.Mood.TALK);
            }
        }

        @Override public void onWorking(@NonNull String task, int percent) {
            if (gone()) return;
            if (percent < 0) showStatus(task);
            else showStatusProgress(task, percent);
            markChecklistFromTask(task);
            actor.setMood(JoybotView.Mood.WORK);
        }

        @Override public void onChecklist(@NonNull String[] stages) {
            if (!gone()) showChecklist(stages);
        }

        @Override public void onToolLine(@NonNull String line) {
            if (gone()) return;
            if (line.startsWith("ai_enhance")) for (int i = 0; i < 5; i++) checkChecklistItem(i, true);
            hideStatus();
            addToolResultMessage(line);
            // An open_room action names a room; going there is a screen change, so it happens here.
            String room = com.fadcam.ui.faditor.ai.actions.AppActions.takePendingRoute();
            if (room != null) {
                final String where = room;
                messagesContainer.postDelayed(() -> { if (!gone()) route(where); }, 900L);
            }
            // open_library_item / new_project_from: open it as the Library would, then step aside.
            messagesContainer.postDelayed(() -> {
                if (!gone() && com.fadcam.ui.faditor.ai.actions.AppActions.takePendingOpen(ChatAssistantActivity.this)) {
                    finish();
                }
            }, 900L);
        }

        @Override public void onStep(@NonNull AIChatState.Step step) {
            if (!gone()) addUndoRow(step);
        }

        @Override public boolean onFinished(@NonNull JoybotAgent.Finished f) {
            if (gone() || !java.util.Objects.equals(projectId, f.projectId)) return false;
            // Stopped (reply null): stopWorking already said so, but a stopped Director run still
            // made changes that the Review card should offer to put back.
            if (f.reply == null) { endHandsFree(); renderFinishedExtras(f); return true; }
            if (handsFree && f.ok) {
                handsFreeSay(f.reply);
            } else {
                endHandsFree();
                if (f.ok) voice.speak(f.reply);
            }
            doneTalking(f.ok);
            renderFinishedExtras(f);
            return true;
        }
    };

    /**
     * Whether the model takes pictures. Answered from OpenRouter's catalogue, which says nothing
     * about a model on another service: there it is unknown, never a wrong "no".
     */
    @NonNull
    private ModelCapabilities.Vision visionOf(@Nullable String modelId) {
        return AiService.isCustom(this) ? ModelCapabilities.Vision.UNKNOWN : ModelCapabilities.visionFor(modelId);
    }

    // ══ JOYBOT: ideas, direct runs, results, undo, the slide flow ═══════════════════════
    // Plan: tasks/joybot/JOYBOT_PLAN_20261014.md (Phase 1 + the slide flow). The ideas come from a
    // local decision tree (IdeaEngine) — instant, free, honest — and a tapped idea that can run on
    // the phone does so without the model at all.

    /** He flies down from his seat in the header into the corner, and hovers there. */
    private void setupActor() {
        actor.setOrb(com.fadcam.ui.faditor.Studio.ROOM_AVATAR, com.fadcam.ui.faditor.Studio.ORB_DEEP);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(actor, "Joybot: tap for ideas");
        JoybotPulse.dressWhenReady(actor);
        actor.setOnClickListener(v -> {
            actor.react();
            if (busy) return;
            LAST_OPENER.remove(projectId == null ? "" : projectId);
            refreshIdeas(true);
        });
        // He drops in from the top edge of the message area, under his seat in the header. Not from
        // the header itself: that needs clipping switched off up the tree, and then the message
        // list scrolls over the title bar (seen on the Note 9, 2026-10-14).
        View stage = (View) actor.getParent();

        actor.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (headerIcon == null || com.fadcam.ui.motion.Motion.reduced(this)) {
                actor.setVisibility(View.VISIBLE);
                if (headerIcon != null) headerIcon.setAlpha(0f);
                return;
            }
            int[] h = new int[2], s = new int[2];
            headerIcon.getLocationInWindow(h);
            stage.getLocationInWindow(s);
            float hx = Math.max(actor.getWidth() / 2f, h[0] - s[0] + headerIcon.getWidth() / 2f);
            float hy = actor.getHeight() / 2f;
            actor.setTranslationX(hx - (actor.getLeft() + actor.getWidth() / 2f));
            actor.setTranslationY(hy - (actor.getTop() + actor.getHeight() / 2f));
            // Wait for the header's own bounce, then he leaves his seat.
            actor.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                headerIcon.animate().alpha(0f).setDuration(160L).start();
                actor.setVisibility(View.VISIBLE);
                actor.flyHome(null);
            }, 640L);
        });
    }

    /**
     * Work out what Joybot would suggest now and show it as buttons; when {@code speak}, also
     * say the "I noticed…" sentence (once per new idea, not every time the chat opens).
     */
    private void refreshIdeas(boolean speak) {
        checkRecipes();
        if (room == IdeaEngine.Room.VISLAB || room == IdeaEngine.Room.JOYBRUSH) {
            // VisLab's ideas are about the look on screen, JoyBrush's about the open drawing: no
            // project is needed (without this JoyBrush showed the lobby's "Start a project").
            com.fadcam.ui.faditor.ai.joybot.ProjectFacts f = new com.fadcam.ui.faditor.ai.joybot.ProjectFacts();
            f.online = AiService.ready(this, apiKey);
            currentOffer = IdeaEngine.offer(f, room, java.util.Collections.<String>emptySet(),
                    java.util.Collections.<String>emptySet());
            if (!busy && slideFlow == null) showChips(currentOffer.ideas);
            return;
        }
        if (projectId == null) {
            currentOffer = null;
            showChips(noProjectIdeas());
            return;
        }
        final boolean fromPulse = getIntent().getBooleanExtra(EXTRA_FROM_PULSE, false);
        JoybotPulse.check(this, projectId, room, (offer, fresh) -> {
            if (isFinishing() || isDestroyed()) return;
            currentOffer = offer;
            if (offer == null) { hideChips(); return; }
            if (speak && offer.opener != null
                    && (fromPulse || !offer.opener.equals(LAST_OPENER.get(projectId)))) {
                LAST_OPENER.put(projectId, offer.opener);
                addBotMessage(offer.opener);
                voice.speak(offer.opener);
                actor.setMood(JoybotView.Mood.TALK);
                actor.postDelayed(() -> { if (!busy) actor.setMood(JoybotView.Mood.IDLE); }, 1400L);
            }
            if (!busy && slideFlow == null) showChips(offer.ideas);
            Idea best = offer.best();
            if (best != null) {
                ideaMemory.markSeen(projectId, best.quietId());
                if (!best.quietId().equals(shownBestId)) {
                    shownBestId = best.quietId();
                    bestTapped = false;
                }
            }
        });
    }

    /** Without a project there is nothing to notice, so the buttons are ways to start one. */
    @NonNull
    private List<Idea> noProjectIdeas() {
        List<Idea> l = new ArrayList<>();
        l.add(Idea.of("start_project", Idea.Kind.ROUTE, "Start a project").flow("studio")
                .say("I want to start a project")
                .hint("Go to the Studio to make a new project").build());
        l.add(Idea.of("record", Idea.Kind.ROUTE, "Record something").flow(IdeaEngine.ROUTE_RECORD)
                .say("I want to record something")
                .hint("Go to Capture to film your screen or your camera").build());
        l.add(IdeaEngine.SOMETHING_ELSE);
        return l;
    }

    // ── Files outside the Joy Creator folder (replaces the editor's yellow dot + dialog) ──
    //
    // Owner, 2026-10-16: Joybot breathes amber in the editor while the project uses outside files,
    // and says it here, with his Copy/Move guess for each and four buttons. The editor carries the
    // answer out when the chat closes (AIChatState.putBringIn → bringInOutside).

    /** In the Studio, with outside files: say so first thing. */
    private void offerBringIn() {
        if (projectId == null || room != IdeaEngine.Room.STUDIO) return;
        final String pid = projectId;
        aiExecutor.execute(() -> {
            com.fadcam.ui.faditor.model.FaditorProject p =
                    new com.fadcam.ui.faditor.project.ProjectStorage(this).load(pid);
            final List<com.fadcam.joyfolder.studio.OutsideFiles.File> files = p == null
                    ? new ArrayList<com.fadcam.joyfolder.studio.OutsideFiles.File>()
                    : com.fadcam.joyfolder.studio.OutsideFiles.list(this, p);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || files.isEmpty()) return;
                showBringIn(files);
            });
        });
    }

    /**
     * Joybot says it, and his message holds the very panel the old dialog showed (BringInPanel):
     * Copy all / Move all / Leave all, each file's Copy · Move · Leave with his guess picked, and
     * Do it. Owner, 2026-10-16: "same formatting, just conversational".
     */
    private void showBringIn(@NonNull List<com.fadcam.joyfolder.studio.OutsideFiles.File> files) {
        int n = files.size();
        String title = (n == 1 ? "Heads up: one file in this project lives" : "Heads up: " + n + " files in this project live")
                + " outside your Joy Creator folder, so if " + (n == 1 ? "it's" : "they're")
                + " moved or deleted on the phone, the project loses " + (n == 1 ? "it" : "them")
                + ". I've picked what I'd do for each — change any, then tap Do it.";
        List<String> refs = new ArrayList<>(), names = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        for (com.fadcam.joyfolder.studio.OutsideFiles.File f : files) {
            refs.add(f.ref);
            names.add(f.name);
            sizes.add(f.size);
        }
        final com.fadcam.joyfolder.studio.BringInPanel panel =
                com.fadcam.joyfolder.studio.BringInPanel.build(this, refs, names, sizes, false);
        final JoybotChatViews.Card card = JoybotChatViews.resultCard(this, title, null);
        panel.view.setPadding(0, dp(8), 0, dp(4));
        card.root.addView(panel.view, card.root.indexOfChild(card.buttons));
        card.buttons.addView(JoybotChatViews.cardButton(this, "Do it",
                "Bring the files in as chosen above; Leave keeps a file where it is", v -> {
                    card.retire("Done.");
                    answerBringIn(panel.choices);
                }));
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
        synchronized (messageLog) { messageLog.add(new String[]{"bot", title}); trimMessageLog(); }
        voice.speak(n == 1 ? "One file is outside your Joy Creator folder." : n + " files are outside your Joy Creator folder.");
        actor.setMood(JoybotView.Mood.SHOW);
        actor.postDelayed(() -> actor.setMood(JoybotView.Mood.IDLE), 1200L);
    }

    private void answerBringIn(@NonNull java.util.Map<String, com.fadcam.joyfolder.studio.BringInDialog.Choice> chosen) {
        if (projectId == null) return;
        java.util.Map<String, String> take = new java.util.LinkedHashMap<>();
        List<String> left = new ArrayList<>();
        for (java.util.Map.Entry<String, com.fadcam.joyfolder.studio.BringInDialog.Choice> e : chosen.entrySet()) {
            if (e.getValue() == com.fadcam.joyfolder.studio.BringInDialog.Choice.LEAVE) left.add(e.getKey());
            else take.put(e.getKey(), e.getValue().name());
        }
        // Leave is a decision, remembered: Joybot stops glowing about those files.
        if (!left.isEmpty()) com.fadcam.joyfolder.studio.OutsideFiles.leave(this, projectId, left);
        if (take.isEmpty()) {
            addBotMessage(left.size() == 1 ? "Okay, it stays where it is. I won't bring it up again."
                    : "Okay, they stay where they are. I won't bring them up again.");
            return;
        }
        AIChatState.putBringIn(projectId, take);
        // The editor does the copying (it re-points every clip and, for a move, other projects);
        // it starts as soon as it's back on screen.
        addBotMessage("On it — I'll close the chat so you can watch it happen.");
        messagesContainer.postDelayed(() -> { if (!isFinishing()) finish(); }, 900L);
    }

    private void showChips(@NonNull List<Idea> ideas) {
        shownIdeas = ideas;
        JoybotChatViews.fillChips(chipsRow, withRecipesChip(ideas), new JoybotChatViews.OnChip() {
            @Override public void onTap(@NonNull Idea idea) { runIdea(idea, true); }
            @Override public void onDismiss(@NonNull Idea idea) { dismissIdea(idea); }
        });
        chipsScroll.scrollTo(0, 0);
        if (chipsScroll.getVisibility() != View.VISIBLE) {
            chipsScroll.setVisibility(View.VISIBLE);
            com.fadcam.ui.motion.Motion.enter(chipsScroll, 0);
        }
    }

    private void hideChips() {
        chipsScroll.setVisibility(View.GONE);
    }

    private void dismissIdea(@NonNull Idea idea) {
        if (projectId == null) return;
        ideaMemory.quieten(projectId, idea.quietId());
        addBotMessage("Okay — I won't suggest “" + idea.label + "” for this project again.");
        refreshIdeas(false);
    }

    /** A tapped idea (or a typed sentence that matched one). */
    private void runIdea(@NonNull Idea idea, boolean sayIt) {
        if (busy) {
            Toast.makeText(this, "Joybot is still working — tap Stop first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (MY_RECIPES.key.equals(idea.key)) {
            addUserMessage(idea.say);
            showRecipes();
            return;
        }
        if (idea.kind == Idea.Kind.TYPE) {
            inputField.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            return;
        }
        if (projectId != null) {
            ideaMemory.markTapped(projectId, idea.quietId());
            if (idea.quietId().equals(shownBestId)) bestTapped = true;
        }
        if (sayIt) addUserMessage(idea.say);
        switch (idea.kind) {
            case DIRECT:
                runDirect(idea);
                break;
            case ASK:
                if (AiService.ready(this, apiKey)) {
                    handleAiQuery(idea.say);
                } else {
                    addBotMessage("That one needs an AI to think it through. Connect one in ⚙ "
                            + "(the free one works) and tap it again.");
                }
                break;
            case FLOW:
                if (IdeaEngine.FLOW_PICTURE.equals(idea.flow)) {
                    startPictureFlow(null);
                } else if (IdeaEngine.FLOW_SLIDE.equals(idea.flow)) {
                    startSlideFlow("title".equals(idea.key) ? "title"
                            : "intro_slide".equals(idea.key) ? "title" : null);
                }
                break;
            case ROUTE:
                route(idea.flow);
                break;
            default:
                break;
        }
    }

    // ── Recipes (gap F7): keep what Joybot did, and run it again in one tap ──

    /** The chip that opens My recipes. Kind TYPE so it never shows the "stop suggesting" hold. */
    private static final Idea MY_RECIPES = Idea.of("my_recipes", Idea.Kind.TYPE, "My recipes")
            .say("My recipes")
            .hint("Run or delete the recipes you have saved")
            .build();

    /** Whether any recipe is saved (read on the worker by {@link #checkRecipes}). */
    private boolean haveRecipes;
    /** The ideas the chip row shows, before the My recipes chip is added. */
    private List<Idea> shownIdeas = new ArrayList<>();

    /** The ideas, with My recipes just before Something else? when a recipe is saved. */
    @NonNull
    private List<Idea> withRecipesChip(@NonNull List<Idea> ideas) {
        if (!haveRecipes) return ideas;
        List<Idea> out = new ArrayList<>(ideas);
        int at = out.size();
        if (!out.isEmpty() && out.get(out.size() - 1).kind == Idea.Kind.TYPE) at = out.size() - 1;
        out.add(at, MY_RECIPES);
        return out;
    }

    /** Reads the saved recipes on the worker; shows or hides the My recipes chip when that changed. */
    private void checkRecipes() {
        aiExecutor.execute(() -> {
            boolean any = !RecipeStore.list(this).isEmpty();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || any == haveRecipes) return;
                haveRecipes = any;
                if (chipsScroll.getVisibility() == View.VISIBLE) showChips(shownIdeas);
            });
        });
    }

    /** After a request that changed the project: offer to keep its steps as a recipe. */
    private void addSaveRecipeCard(@NonNull JoybotAgent.Finished f) {
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this, "Want to do this again?", null);
        card.buttons.addView(JoybotChatViews.cardButton(this, "Save as a recipe",
                "Keep these steps so you can run them again in one tap",
                v -> askRecipeName(f, card)));
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
    }

    /** Asks what to call the recipe; the request, shortened, is the suggested name. */
    private void askRecipeName(@NonNull JoybotAgent.Finished f, @NonNull JoybotChatViews.Card card) {
        if (!idleOrSay()) return;
        String words = f.asked.replaceAll("\\s+", " ").trim();
        if (words.length() > 30) words = words.substring(0, 29).trim() + "…";
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(words);
        field.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Name this recipe")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> saveRecipe(f, field.getText().toString(), card))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveRecipe(@NonNull JoybotAgent.Finished f, @NonNull String name,
                            @NonNull JoybotChatViews.Card card) {
        Recipe recipe = new Recipe(name, f.asked, f.calls);
        aiExecutor.execute(() -> {
            String failed = null;
            try {
                RecipeStore.save(this, recipe);
            } catch (Exception e) {
                failed = e.getMessage() == null ? "something went wrong" : e.getMessage();
            }
            final String why = failed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (why != null) {
                    addBotMessage("I couldn't save that recipe: " + why);
                    return;
                }
                card.retire("Saved as a recipe.");
                addBotMessage("Saved “" + recipe.name + "” — " + recipe.describe() + ". Find it under My recipes.");
                checkRecipes();
            });
        });
    }

    /** My recipes: the saved recipes as rows, each with Run and Delete. Read on the worker. */
    private void showRecipes() {
        aiExecutor.execute(() -> {
            List<RecipeStore.Saved> saved = RecipeStore.list(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (saved.isEmpty()) {
                    addBotMessage("You have no recipes yet. After I do something for you, tap Save as a recipe.");
                    checkRecipes();
                    return;
                }
                addRecipesCard(saved);
            });
        });
    }

    private void addRecipesCard(@NonNull List<RecipeStore.Saved> saved) {
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this, "My recipes", null);
        card.root.removeView(card.buttons);
        for (RecipeStore.Saved s : saved) {
            Recipe recipe = s.recipe;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            TextView label = new TextView(this);
            label.setText(recipe.name + "\n" + recipe.describe());
            label.setTextColor(Studio.INK);
            label.setTextSize(13);
            row.addView(label);
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setPadding(0, dp(6), 0, 0);
            buttons.addView(JoybotChatViews.cardButton(this, "Run", "Run “" + recipe.name + "” again",
                    v -> runRecipe(recipe)));
            TextView delete = JoybotChatViews.cardButton(this, "Delete", "Delete the recipe “" + recipe.name + "”",
                    v -> confirmDeleteRecipe(s, row));
            delete.setTextSize(11);
            buttons.addView(delete);
            row.addView(buttons);
            card.root.addView(row);
        }
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
    }

    /** Runs a recipe: on the phone when its steps fit any project, else through the model as an approved plan. */
    private void runRecipe(@NonNull Recipe recipe) {
        if (!idleOrSay()) return;
        String undo = "recipe “" + recipe.name + "”";
        if (recipe.portable() && projectId != null) {
            Idea.Builder b = Idea.of("recipe", Idea.Kind.DIRECT, recipe.name)
                    .say("Run my recipe “" + recipe.name + "”")
                    .hint("Run this recipe on this project")
                    .undo(undo);
            for (Idea.Step s : recipe.steps) b.step(s);
            addUserMessage("Run my recipe “" + recipe.name + "”");
            runDirect(b.build());
        } else {
            sendFollowUp("Run my recipe “" + recipe.name + "”.", recipe.request, "Joybot: " + undo,
                    true, JoybotAgent.DIRECTOR_STEPS);
        }
    }

    private void confirmDeleteRecipe(@NonNull RecipeStore.Saved s, @NonNull View row) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Delete “" + s.recipe.name + "”?")
                .setMessage("Its steps will be gone from My recipes.")
                .setPositiveButton("Delete", (d, w) -> aiExecutor.execute(() -> {
                    String failed = null;
                    try {
                        RecipeStore.delete(this, s.file);
                    } catch (Exception e) {
                        failed = e.getMessage() == null ? "something went wrong" : e.getMessage();
                    }
                    final String why = failed;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (why != null) {
                            addBotMessage("I couldn't delete that recipe: " + why);
                            return;
                        }
                        row.setVisibility(View.GONE);
                        addBotMessage("Deleted “" + s.recipe.name + "”.");
                        checkRecipes();
                    });
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Run an idea's steps on the phone, then show what changed with Undo and Show me. */
    private void runDirect(@NonNull Idea idea) {
        if (projectId == null) return;
        setBusy(true);
        cancelled = false;
        actor.setMood(JoybotView.Mood.WORK);
        showStatus(idea.label);
        // On the app-wide worker, not this screen's: closing the chat must not stop a long job.
        JoybotJobs.run(this, projectId, idea);
    }

    /** Joybot's jobs report here while the chat is open (see onCreate / onDestroy). */
    private final JoybotJobs.Listener jobListener = new JoybotJobs.Listener() {
        @Override public void onProgress(@NonNull String task, int percent) {
            if (!isFinishing() && !isDestroyed()) showStatusProgress(task, percent);
        }

        @Override public boolean onDone(@NonNull JoybotJobs.Done done) {
            if (isFinishing() || isDestroyed()) return false;
            if (projectId == null || !projectId.equals(done.projectId)) return false;
            hideStatus();
            setBusy(false);
            showDirectResult(done.idea, done.result);
            rememberForModel(done.idea.say, done.result);
            refreshIdeas(false);
            return true;
        }
    };

    /** Jobs that finished after the chat was closed: say so first thing. */
    private void showFinishedWhileAway() {
        // Requests the MODEL carried out after the chat closed (JoybotAgent): what was asked, what
        // it did, what it said — and its Undo while that step is still the newest.
        for (JoybotAgent.Finished f : JoybotAgent.takeWaiting(projectId)) {
            addBotMessage("While you were away I finished “" + JoybotAgent.shortOf(f.asked) + "”:");
            for (String line : f.toolLines) addToolResultMessage(line);
            if (f.reply != null) addBotMessage(f.reply);
            if (f.step != null && projectId != null && AIChatState.peekStep(projectId) == f.step) addUndoRow(f.step);
            renderFinishedExtras(f);
        }
        java.util.List<JoybotJobs.Done> away = JoybotJobs.takeWaiting(projectId);
        if (away.isEmpty()) return;
        addBotMessage(away.size() == 1 ? "While you were away I finished this:" : "While you were away I finished these:");
        for (JoybotJobs.Done d : away) {
            showDirectResult(d.idea, d.result);
            rememberForModel(d.idea.say, d.result);
        }
    }

    // ── Director mode in the chat: plans, Keep going, the Review card, Carry on (gaps F3/F4) ──

    /** Under a finished request's reply: the usage line, the Review card, Keep going, the plan. */
    private void renderFinishedExtras(@NonNull JoybotAgent.Finished f) {
        String words = f.usageWords();
        if (!words.isEmpty() && (f.director || f.steps > 1)) addUsageLine(words);
        if (f.projectId != null && f.changes.size() >= 2) addReviewCard(f.projectId, f.changes);
        if (f.budgetHit) addKeepGoingCard(f);
        if (f.plan != null) addPlanCard(f.plan);
        if (f.ok && !f.calls.isEmpty()) addSaveRecipeCard(f);
    }

    /** What a request spent, in a small grey line like the tool lines. */
    private void addUsageLine(@NonNull String words) {
        TextView tv = new TextView(this);
        tv.setText(words);
        tv.setTextSize(12);
        tv.setTextColor(Studio.INK_FAINT);
        tv.setPadding(dp(16), dp(2), dp(60), dp(2));
        messagesContainer.addView(tv);
        scrollToBottom();
    }

    /** The one action a card leads with: the studio green pill the Send button wears. */
    @NonNull
    private TextView primaryCardButton(@NonNull String text, @NonNull String hint,
                                       @NonNull View.OnClickListener onClick) {
        TextView b = JoybotChatViews.cardButton(this, text, hint, onClick);
        b.setTag(VOICE_LEAD);
        b.setBackgroundResource(R.drawable.studio_action_pill);
        b.setTextColor(Studio.ON_GO);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    /** False (with a note) while Joybot is still working: a card button must not start a second request. */
    private boolean idleOrSay() {
        if (!busy) return true;
        Toast.makeText(this, "Joybot is still working — tap Stop first", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** A follow-up request: the person's bubble, then the agent, exactly as a typed message would go. */
    private void sendFollowUp(@NonNull String shown, @NonNull String text, @NonNull String undoLabel,
                              boolean director, int maxSteps) {
        addUserMessage(shown);
        askAgent(text, null, 0, 0, undoLabel, director, maxSteps);
    }

    /** A plan Joybot wants approved before a big job: Go ahead starts a Director run, Not now retires it. */
    /** This screen already shows the waiting plan's card (so reopening doesn't add a second). */
    private boolean planCardShown;

    private void addPlanCard(@NonNull JoybotAgent.Plan plan) {
        planCardShown = true;
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < plan.steps.size(); i++) {
            if (i > 0) steps.append('\n');
            steps.append(i + 1).append(". ").append(plan.steps.get(i));
        }
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this, plan.title,
                steps.length() > 0 ? steps.toString() : null);
        card.buttons.addView(primaryCardButton("Go ahead", "Joybot does the whole plan without asking again", v -> {
            if (!idleOrSay()) return;
            card.retire("Going ahead.");
            sendFollowUp("Go ahead with the plan.", "Go ahead with the plan.", "Joybot: " + plan.title,
                    true, JoybotAgent.DIRECTOR_STEPS);
        }));
        card.buttons.addView(JoybotChatViews.cardButton(this, "Not now", "Leave the plan for now",
                v -> {
                    JoybotAgent.clearPendingPlan(projectId);
                    card.retire("Not now.");
                }));
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
    }

    /** Joybot used its step budget with work left: one tap carries on with the rest. */
    private void addKeepGoingCard(@NonNull JoybotAgent.Finished f) {
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this, "There's more to do.", null);
        card.buttons.addView(primaryCardButton("Keep going", "Joybot used its steps for now: carry on with the rest", v -> {
            if (!idleOrSay()) return;
            card.retire("Carrying on.");
            sendFollowUp("Keep going.", "Keep going.", requestLabelFor("Keep going."), f.director,
                    f.director ? JoybotAgent.DIRECTOR_STEPS : JoybotAgent.MAX_TOOL_ROUNDS);
        }));
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
    }

    /**
     * A Director run's changes, one row each: what it was, Show me, and Undo from here (puts the
     * project back as it was before that change, and every later change goes with it).
     */
    /** This screen already shows the last Director run's Review card. */
    private boolean reviewCardShown;

    private void addReviewCard(@NonNull String pid, @NonNull List<JoybotAgent.Change> changes) {
        reviewCardShown = true;
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this,
                "Review — " + changes.size() + " changes", null);
        card.root.removeView(card.buttons);
        final List<JoybotAgent.Change> list = new ArrayList<>(changes);
        final List<LinearLayout> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            final JoybotAgent.Change ch = list.get(i);
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);
            TextView said = new TextView(this);
            said.setText(ch.said.replaceAll("\\s+", " ").trim());
            said.setTextSize(13);
            said.setTextColor(Studio.INK_DIM);
            said.setSingleLine(true);
            said.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMarginEnd(dp(6));
            said.setLayoutParams(tlp);
            row.addView(said);
            if (!ch.ids.isEmpty()) {
                final String clip = ch.ids.get(0);
                row.addView(JoybotChatViews.cardButton(this, "Show me", "Go to this clip in the editor",
                        v -> showMe(clip)));
            }
            if (ch.canRevert()) {
                row.addView(JoybotChatViews.cardButton(this, "Undo from here",
                        "Put the project back the way it was before this change",
                        v -> confirmUndoFrom(pid, list, index, rows)));
            }
            card.root.addView(row);
            rows.add(row);
        }
        // Rows already put back (before this screen was closed) stay grey, with no buttons.
        for (int k = Math.max(0, JoybotAgent.reviewRevertedFrom(pid)); k < rows.size(); k++) {
            LinearLayout r = rows.get(k);
            r.setAlpha(0.35f);
            for (int c = 1; c < r.getChildCount(); c++) r.getChildAt(c).setVisibility(View.GONE);
        }
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
    }

    private void confirmUndoFrom(@NonNull String pid, @NonNull List<JoybotAgent.Change> changes, int index,
                                 @NonNull List<LinearLayout> rows) {
        if (!idleOrSay()) return;
        int after = changes.size() - index - 1;
        String rest = after == 0 ? "This change will go."
                : "This change and " + (after == 1 ? "the one after it" : "the " + after + " after it") + " will go.";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Undo from here?")
                .setMessage("Put the project back how it was before this? " + rest)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Undo from here", (d, w) -> undoFrom(pid, changes, index, rows))
                .show();
    }

    /** Puts the project back before change {@code index} on a worker; greys that row and every later one. */
    private void undoFrom(@NonNull String pid, @NonNull List<JoybotAgent.Change> changes, int index,
                          @NonNull List<LinearLayout> rows) {
        if (!idleOrSay()) return;
        final JoybotAgent.Change ch = changes.get(index);
        setBusy(true);
        aiExecutor.execute(() -> {
            final String problem = JoybotAgent.revertTo(this, pid, ch);
            final boolean ok = problem == null;
            runOnUiThread(() -> {
                setBusy(false);
                if (isFinishing() || isDestroyed()) return;
                if (ok) {
                    for (int k = index; k < rows.size(); k++) {
                        LinearLayout r = rows.get(k);
                        r.setAlpha(0.35f);
                        for (int c = 1; c < r.getChildCount(); c++) r.getChildAt(c).setVisibility(View.GONE);
                    }
                    addBotMessage("Put back how it was before “" + JoybotAgent.shortOf(ch.said) + "”.");
                    try {
                        conversationHistory.add(new JSONObject().put("role", "user")
                                .put("content", "[The person undid Joybot's changes from: " + ch.said + " onward.]"));
                    } catch (Exception ignored) { }
                } else {
                    addBotMessage(problem);
                }
                refreshIdeas(false);
            });
        });
    }

    /** A job the phone stopped in the middle (the app was killed): offer to pick it up, or let it go. */
    private void offerCarryOn() {
        JobFile.Interrupted j = JoybotAgent.interrupted(this);
        if (j == null || projectId == null || !projectId.equals(j.projectId)) return;
        String steps = j.round + (j.round == 1 ? " step" : " steps");
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this,
                "The phone stopped me in the middle of “" + JoybotAgent.shortOf(j.text) + "” (after " + steps + ").",
                null);
        card.buttons.addView(primaryCardButton("Carry on", "Pick the job up where it stopped", v -> {
            if (!idleOrSay()) return;
            card.retire("Carrying on.");
            carryOn(j);
        }));
        card.buttons.addView(JoybotChatViews.cardButton(this, "Leave it", "Forget the unfinished job", v -> {
            card.retire("Left it.");
            leaveJob(j);
        }));
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
    }

    /** Puts the killed job's conversation back, then carries on with it as a Director run. */
    private void carryOn(@NonNull JobFile.Interrupted j) {
        // restore() replaces whatever the chat holds (the fresh system prompt included) with the
        // saved conversation, which carries its own system prompt; and it forgets the file.
        JoybotAgent.restore(this, j);
        sendFollowUp("Carry on.",
                "Carry on with the job from where you stopped. Check the project first — some of it is already done.",
                j.undoLabel, true, JoybotAgent.DIRECTOR_STEPS);
    }

    /**
     * Forgets the killed job: the file must go, or the card would come back on every open. restore()
     * is the only public way to clear it, so the history it brings is put back to what the chat had.
     */
    private void leaveJob(@NonNull JobFile.Interrupted j) {
        JoybotAgent.forgetInterrupted(this);
    }

    private void showDirectResult(@NonNull Idea idea, @NonNull DirectRunner.Result r) {
        boolean changed = r.step != null;
        String title;
        String details = null;
        if (r.error != null && !changed) {
            title = plainError(r.error);
        } else if (r.summary != null) {
            title = r.summary + (r.error != null ? "\n\nThen I hit a snag: " + plainError(r.error) : "");
        } else if (changed) {
            // No measurable headline (a layer's edge moved): the tool's own sentence says what.
            title = r.details != null && !r.details.contains("\n") ? r.details : "Done.";
        } else {
            // Information rather than a change (chapters, say): show it, in its own words.
            title = idea.kind == Idea.Kind.DIRECT && "chapters".equals(idea.key)
                    ? "Here's where the topics change:" : "Nothing needed changing.";
            details = r.details;
        }
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this, title, details);
        if (changed) {
            final AIChatState.Step step = r.step;
            card.buttons.addView(JoybotChatViews.cardButton(this, "Undo",
                    "Put the project back the way it was before this", v -> undoStep(card, step)));
            cardForStep.put(step, card);
        }
        if (!idea.target.isEmpty() && isSpineClip(r, idea.target)) {
            card.buttons.addView(JoybotChatViews.cardButton(this, "Show me",
                    "Go to this clip in the editor", v -> showMe(idea.target)));
        } else if (r.newItemId != null && room == IdeaEngine.Room.STUDIO) {
            // Something NEW (a slide on top, a picture): its row can land below the fold, so the
            // editor selects it, scrolls to it and parks the playhead on it.
            final String added = r.newItemId;
            card.buttons.addView(JoybotChatViews.cardButton(this, "Show me",
                    "Show what I just added in the editor", v -> showMe(added)));
        } else if ((changed || r.changed) && room != IdeaEngine.Room.STUDIO && projectId != null) {
            // From the lobby a change happened in a project you can't see: one tap to go and look
            // (owner, 2026-10-14: "I didn't know where it did it or how to get there").
            final String first = firstStepClip(idea);
            card.buttons.addView(JoybotChatViews.cardButton(this, "Open in Studio",
                    "Open this project in the Studio to see what changed",
                    v -> showMe(first != null ? first : "")));
        }
        if (card.buttons.getChildCount() == 0) card.root.removeView(card.buttons);
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
        synchronized (messageLog) { messageLog.add(new String[]{"bot", title}); trimMessageLog(); }
        voice.speak(title);

        if (changed && r.error == null) {
            showOff(card.root);
        } else if (r.error != null) {
            actor.setMood(JoybotView.Mood.OOPS);
            actor.postDelayed(() -> actor.setMood(JoybotView.Mood.IDLE), 700L);
        } else {
            actor.setMood(JoybotView.Mood.IDLE);
        }
    }

    /** The first clip an idea's steps touched, for "Open in Studio". */
    @Nullable
    private static String firstStepClip(@NonNull Idea idea) {
        for (Idea.Step s : idea.steps) {
            Object id = s.args.get("clipId");
            if (id != null) return String.valueOf(id);
        }
        return null;
    }

    private static boolean isSpineClip(@NonNull DirectRunner.Result r, @NonNull String id) {
        if (r.after == null) return false;
        for (com.fadcam.ui.faditor.ai.joybot.ProjectFacts.ClipFacts c : r.after.spine) {
            if (c.id.equals(id)) return true;
        }
        return false;
    }

    /** He flies up to what he just did, shows it off, and flies back. */
    private void showOff(@NonNull View target) {
        target.post(() -> {
            if (isFinishing() || isDestroyed() || actor.getVisibility() != View.VISIBLE) return;
            View stage = (View) actor.getParent();
            int[] t = new int[2], s = new int[2];
            target.getLocationInWindow(t);
            stage.getLocationInWindow(s);
            float x = t[0] - s[0] + target.getWidth() - actor.getWidth() * 0.15f;
            float y = Math.max(actor.getHeight() / 2f, t[1] - s[1] + actor.getHeight() * 0.1f);
            actor.setMood(JoybotView.Mood.SHOW);
            actor.flyTo(Math.min(x, stage.getWidth() - actor.getWidth() / 2f), y, () ->
                    actor.postDelayed(() -> actor.flyHome(() -> actor.setMood(JoybotView.Mood.IDLE)), 900L));
        });
    }

    /** Tell the model what Joybot did by himself, so a later question can build on it. */
    private void rememberForModel(@NonNull String said, @NonNull DirectRunner.Result r) {
        try {
            conversationHistory.add(new JSONObject().put("role", "user").put("content", said));
            String what = r.summary != null ? r.summary
                    : r.error != null ? "That didn't work: " + r.error
                    : r.details != null ? r.details : "Nothing needed changing.";
            conversationHistory.add(new JSONObject().put("role", "assistant").put("content", what));
        } catch (Exception ignored) { }
    }

    /** Joybot's tool vocabulary turned into something a person would say. */
    @NonNull
    private static String plainError(@NonNull String raw) {
        String t = raw.replaceFirst("^(Error|Apply failed|Validation failed|Synthesis failed|Fix failed):?\\s*", "").trim();
        if (t.toLowerCase(java.util.Locale.US).contains("timed out")) {
            return "That took longer than I can wait. Try a shorter clip, or run it from the editor's tools.";
        }
        if (t.toLowerCase(java.util.Locale.US).contains("unknownhost")
                || t.toLowerCase(java.util.Locale.US).contains("unable to resolve")) {
            return "I need the internet once to download the speech model. Connect and try again.";
        }
        return t.isEmpty() ? "Something went wrong." : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Undo the newest Joybot change. Older cards say so instead of undoing out of order. */
    private void undoStep(@NonNull JoybotChatViews.Card card, @NonNull AIChatState.Step step) {
        if (projectId == null) return;
        if (AIChatState.peekStep(projectId) != step) {
            Toast.makeText(this, "Undo the newer change first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) return;
        setBusy(true);
        final String pid = projectId;
        aiExecutor.execute(() -> {
            boolean ok = DirectRunner.undo(this, pid);
            runOnUiThread(() -> {
                setBusy(false);
                if (ok) {
                    card.retire("Undone.");
                    cardForStep.remove(step);
                    addBotMessage("Undone — it's back how it was.");
                    try {
                        conversationHistory.add(new JSONObject().put("role", "user")
                                .put("content", "[The person pressed Undo on: " + step.label + ". That change is gone now.]"));
                    } catch (Exception ignored) { }
                } else {
                    addBotMessage("I couldn't undo that one. The editor's undo button still can.");
                }
                refreshIdeas(false);
            });
        });
    }

    private void undoByWords() {
        AIChatState.Step step = projectId == null ? null : AIChatState.peekStep(projectId);
        if (step == null) {
            addBotMessage("There's nothing of mine to undo here. The editor's undo button undoes your own edits.");
            return;
        }
        JoybotChatViews.Card card = cardForStep.get(step);
        if (card == null) {
            card = JoybotChatViews.resultCard(this, step.label.replaceFirst("^Joybot: ", ""), null);
            card.root.removeView(card.buttons);
            messagesContainer.addView(card.root);
        }
        undoStep(card, step);
    }

    /** Go to the clip in the editor. From the lobby, open the editor first. */
    private void showMe(@NonNull String clipId) {
        AIChatState.showClipId = clipId;
        if (room != IdeaEngine.Room.STUDIO && projectId != null) {
            startActivity(new Intent(this, com.fadcam.ui.faditor.FaditorEditorActivity.class)
                    .putExtra(com.fadcam.ui.faditor.FaditorEditorActivity.EXTRA_PROJECT_ID, projectId));
        }
        finish();
    }

    /** "Show me how": the editor points at this tool's real button. Same route as showMe. */
    private void showHow(@NonNull String toolId) {
        AIChatState.showHowTool = toolId;
        if (room != IdeaEngine.Room.STUDIO && projectId != null) {
            startActivity(new Intent(this, com.fadcam.ui.faditor.FaditorEditorActivity.class)
                    .putExtra(com.fadcam.ui.faditor.FaditorEditorActivity.EXTRA_PROJECT_ID, projectId));
        }
        finish();
    }

    /** "Take me there": a tab of the main screen, or a screen that opens on its own. */
    private void route(@NonNull String where) {
        int tab;
        switch (where) {
            case IdeaEngine.ROUTE_RECORD: tab = 0; break;
            // The Lobby's own screens, not the classic FadCam tabs (lead, DOOR-1 10-28).
            case "library": openOverlay("library"); return;
            case "studio": openOverlay("projects"); return;
            case "setup": openOverlay("settings"); return;
            case "joybrush":
                startActivity(new Intent(this, cc.joycreator.joybrush.android.library.DrawingsActivity.class));
                finish();
                return;
            case "spritelab":
                // Sprite Lab edits a sheet inside a project, so it needs one open. The Lobby does the same
                // (LobbyFragment.enterRoom): with no project yet, the Studio is where one gets made.
                if (projectId == null) { route("studio"); return; }
                startActivity(new Intent(this, com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity.class)
                        .putExtra(com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity.EXTRA_PROJECT_ID, projectId));
                finish();
                return;
            case "avatar":
                // Avatar Studio needs a project too, for the same reason and with the same fallback as Sprite Lab.
                if (projectId == null) { route("studio"); return; }
                startActivity(new Intent(this, com.fadcam.ui.faditor.avatar.AvatarStudioActivity.class)
                        .putExtra(com.fadcam.ui.faditor.avatar.AvatarStudioActivity.EXTRA_PROJECT_ID, projectId));
                finish();
                return;
            case "space":
                // Space is a screen of its own (also reached from Setup > Storage), so it opens directly.
                startActivity(com.fadcam.joyfolder.studio.SpaceActivity.intent(this));
                finish();
                return;
            case "overlay_permission":
                // Android's "Display over other apps" page for Joy Creator: the person flips it there
                // (Joybot never changes a permission itself). The floating visualiser needs it.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                }
                finish();
                return;
            case "vislab":
                // VisLab is its own room (the Lobby's tile opens it the same way).
                startActivity(new Intent(this, com.fadcam.vislab.room.VisLabActivity.class));
                finish();
                return;
            case "bridge":
                // Connect your computer (BOT-6): its own screen, and the chat stays underneath.
                startActivity(com.fadcam.ui.faditor.ai.bridge.BridgeActivity.intent(this, projectId, room));
                return;
            case "trash":
                // The Trash is an overlay inside the main screen (the Lobby's logo-hold opens it).
                openOverlay("trash");
                return;
            default: return;
        }
        startActivity(new Intent(this, com.fadcam.MainActivity.class)
                .putExtra("navigate_to_tab", tab)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    /** A screen the main activity opens over the Lobby ({@code MainActivity} "open_overlay"). */
    private void openOverlay(@NonNull String which) {
        startActivity(new Intent(this, com.fadcam.MainActivity.class)
                .putExtra("open_overlay", which)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    // ── voice ──────────────────────────────────────────────────────────────

    private JoybotVoice voice;
    private static final int MIC_PERMISSION_REQUEST = 1003;

    // ── Dictation (owner, 2026-10-14) ──────────────────────────────────────
    // "As soon as I stop or there's even a small gap to breathe, it will just submit." So the mic
    // never sends: tap it and talk, the words collect in the box across pauses (the listener
    // restarts after each phrase), tap it again to stop, then press Send when the thought is done.

    private boolean dictating;
    /** The box's text before the phrase being heard now. */
    private String dictBase = "";
    /** Phrases in a row that heard nothing: three and the mic switches itself off. */
    private int dictEmpty;

    // ── Hands-free (owner, 2026-10-15) ─────────────────────────────────────
    // For one-handed use. A request sent by voice (dictated and sent unedited, or held-mic dictation
    // that sends itself when talking stops) is read aloud when it finishes, even with read-aloud
    // off. Then Joybot listens once, up to six seconds, for "yes", "undo", "stop" or "no". One
    // follow-up exchange per voice request, then hands-free ends. Any tap on the screen, or Stop,
    // ends it. Export never runs by voice: "yes" only presses the newest card's lead button.

    private static final long HANDS_FREE_GAP_MS = 1500L;
    private static final long HANDS_FREE_LISTEN_MS = 6000L;
    private static final String VOICE_YES = "(yes|yeah|yep|yes please|go|go ahead|do it|do that)";
    private static final String VOICE_UNDO = "(undo|undo that|undo it|take (that|it) back|put it back)";
    private static final String VOICE_STOP = "(stop|stop it|stop that)";
    private static final String VOICE_NO = "(no|nope|no thanks|not now|cancel)";
    /** Tag on a card's lead (primary) button: the only button "yes" may press. */
    private static final String VOICE_LEAD = "joybot_voice_lead";

    /** This request was sent by voice: its reply is read aloud, then Joybot listens once. */
    private boolean handsFree;
    /** Held-mic dictation: the first pause sends what was said. */
    private boolean dictateToSend;
    /** The listening window after a voice reply is open. */
    private boolean handsFreeAnswering;
    private boolean handsFreeHeard;
    /** The one listening window has been used: the next reply is read, then hands-free ends. */
    private boolean handsFreeUsed;
    /** The next send is a voice answer's new request, so it stays hands-free. */
    private boolean handsFreeFollow;
    /** What dictation last put in the box: sent unedited, the request was sent by voice. */
    private String dictatedText;
    private final Handler handsFreeClock = new Handler(Looper.getMainLooper());
    private final Runnable handsFreeDeadline = this::handsFreeTimeUp;

    private void toggleDictation() {
        if (dictating) { stopDictation(); return; }
        startDictation(false);
    }

    /** Hold the mic: say it, and it sends itself when you stop talking. Turns hands-free on. */
    private void dictateAndSend() {
        if (dictating) { stopDictation(); return; }
        startDictation(true);
    }

    private boolean micGranted() {
        return androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void startDictation(boolean sendWhenDone) {
        if (!micGranted()) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
            return;
        }
        if (!JoybotVoice.canListenInBackground(this)) { if (!sendWhenDone) startVoiceInput(); return; }
        voice.hush();
        dictating = true;
        dictateToSend = sendWhenDone;
        if (sendWhenDone) handsFree = true;
        dictEmpty = 0;
        paintMic();
        actor.setMood(JoybotView.Mood.LISTEN);
        listenForPhrase();
    }

    private void listenForPhrase() {
        if (!dictating || isFinishing()) return;
        dictBase = inputField.getText().toString().trim();
        voice.startListening(new JoybotVoice.Ears() {
            @Override public void onPartial(@NonNull String soFar) {
                if (!dictating) return;
                showDictated(soFar);
            }

            @Override public void onFinal(@Nullable String text) {
                if (dictateToSend) {
                    // Held-mic dictation: the first pause is the end, and what was said goes now.
                    stopDictation();
                    if (text == null) {
                        endHandsFree();
                        Toast.makeText(ChatAssistantActivity.this,
                                "I didn't catch that. Hold the mic and try again.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showDictated(text);
                    sendMessage();
                    return;
                }
                if (text != null) { dictEmpty = 0; showDictated(text); }
                else dictEmpty++;
                if (dictating && dictEmpty < 3) listenForPhrase();
                else if (dictating) stopDictation();
            }

            @Override public void onProblem(@NonNull String message) {
                stopDictation();
                endHandsFree();
                Toast.makeText(ChatAssistantActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        }, dictateToSend ? HANDS_FREE_GAP_MS : 4000L);
    }

    private void showDictated(@NonNull String phrase) {
        String t = dictBase.isEmpty() ? phrase : dictBase + " " + phrase;
        inputField.setText(t);
        inputField.setSelection(inputField.getText().length());
        dictatedText = t.trim();
    }

    private void stopDictation() {
        if (!dictating) return;
        dictating = false;
        dictateToSend = false;
        voice.stopListening();
        paintMic();
        actor.setMood(JoybotView.Mood.IDLE);
    }

    /** ON is solid cyan with the icon cut out (house rule); off is the plain mic. */
    private void paintMic() {
        ImageButton mic = findViewById(R.id.chat_mic);
        if (mic == null) return;
        if (dictating) {
            android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
            on.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            on.setColor(com.fadcam.ui.faditor.Studio.ARMED);
            mic.setBackground(on);
            mic.setImageTintList(android.content.res.ColorStateList.valueOf(com.fadcam.ui.faditor.Studio.GROUND));
            inputField.setHint(handsFreeAnswering ? "Listening\u2026 say yes, undo, stop or no"
                    : dictateToSend ? "Listening\u2026 stop talking and it sends"
                    : "Listening\u2026 tap the mic to stop, then Send");
        } else {
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            mic.setBackgroundResource(tv.resourceId);
            mic.setImageTintList(android.content.res.ColorStateList.valueOf(Studio.INK_DIM));
            inputField.setHint("Ask anything\u2026");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toggleDictation();
        }
    }

    // ── the slide flow ─────────────────────────────────────────────────────

    // ── hands-free: the reply, the one listening window, the short words ──────

    /** A voice request's reply: read aloud even with read-aloud off, then listen once if allowed. */
    private void handsFreeSay(@NonNull String reply) {
        final boolean listenAfter = !handsFreeUsed;
        voice.speakNow(reply, () -> {
            if (!handsFree || isFinishing() || isDestroyed()) return;
            if (listenAfter) listenForAnswer(); else endHandsFree();
        });
    }

    /** The one listening window after a voice reply: up to six seconds for a short answer. */
    private void listenForAnswer() {
        if (!handsFree || isFinishing() || isDestroyed()) return;
        // No permission prompt in the middle of a reply: without the mic, hands-free just stops.
        if (!micGranted() || !JoybotVoice.canListenInBackground(this)) { endHandsFree(); return; }
        handsFreeUsed = true;
        handsFreeAnswering = true;
        handsFreeHeard = false;
        dictating = true;
        paintMic();
        actor.setMood(JoybotView.Mood.LISTEN);
        voice.startListening(new JoybotVoice.Ears() {
            @Override public void onPartial(@NonNull String soFar) {
                if (handsFreeAnswering) handsFreeHeard = true;
            }

            @Override public void onFinal(@Nullable String text) {
                if (handsFreeAnswering) heardAnswer(text);
            }

            @Override public void onProblem(@NonNull String message) {
                if (handsFreeAnswering) endHandsFree();
            }
        }, HANDS_FREE_GAP_MS);
        handsFreeClock.postDelayed(handsFreeDeadline, HANDS_FREE_LISTEN_MS);
    }

    /** Six seconds went by: words already heard are let in; silence ends hands-free. */
    private void handsFreeTimeUp() {
        if (!handsFreeAnswering) return;
        if (handsFreeHeard) voice.stopListening(); else endHandsFree();
    }

    /** What was said in the window: a short answer acts, anything else becomes a new request. */
    private void heardAnswer(@Nullable String said) {
        handsFreeAnswering = false;
        handsFreeClock.removeCallbacks(handsFreeDeadline);
        stopDictation();
        String t = said == null ? "" : norm(said);
        if (t.isEmpty()) { endHandsFree(); return; }
        if (t.matches(VOICE_YES)) {
            int how = sayYesToNewestCard();
            // Pressed: hands-free stays on, so the card's reply is read and then it ends.
            if (how == 1) return;
            addBotMessage(how == 2 ? "Tap the button on that card to do it." : "Nothing is waiting for a yes right now.");
            endHandsFree();
            return;
        }
        if (t.matches(VOICE_UNDO)) { endHandsFree(); undoByWords(); return; }
        if (t.matches(VOICE_STOP)) { stopWorking(); return; }
        if (t.matches(VOICE_NO)) { endHandsFree(); addBotMessage("Okay."); return; }
        // Anything else is a new request, sent as a dictated one is: its reply is read aloud.
        inputField.setText(said.trim());
        handsFreeFollow = true;
        sendMessage();
    }

    /**
     * "Yes" by voice presses the lead button of the NEWEST card on screen, if it has one. Returns 1
     * when pressed, 2 when the newest card has no voice-safe lead (Export's card never does), 0 when
     * there is no card.
     */
    private int sayYesToNewestCard() {
        for (int i = messagesContainer.getChildCount() - 1; i >= 0; i--) {
            View c = messagesContainer.getChildAt(i);
            if (!JoybotChatViews.CARD_TAG.equals(c.getTag())) continue;
            View lead = c.findViewWithTag(VOICE_LEAD);
            if (lead == null || !lead.isEnabled()) return 2;
            lead.performClick();
            return 1;
        }
        return 0;
    }

    /** Hands-free is over: nothing more is listened for, and no reply is read on. */
    private void endHandsFree() {
        if (handsFree) voice.hush();
        boolean listening = handsFreeAnswering || dictateToSend;
        handsFree = false;
        handsFreeAnswering = false;
        handsFreeHeard = false;
        handsFreeClock.removeCallbacks(handsFreeDeadline);
        if (listening) stopDictation();
    }

    /** Any tap on the screen ends hands-free: the person is back at the phone. */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (handsFree && ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) endHandsFree();
        return super.dispatchTouchEvent(ev);
    }

    /** Start "help me make a slide"; {@code kind} skips the first question when already known. */
    private void startSlideFlow(@Nullable String kind) {
        if (projectId == null) return;
        final String pid = projectId;
        aiExecutor.execute(() -> {
            String name = "";
            int clips = 0, here = -1;
            try {
                com.fadcam.ui.faditor.model.FaditorProject p =
                        new com.fadcam.ui.faditor.project.ProjectStorage(this).load(pid);
                if (p != null) {
                    name = p.getName() == null ? "" : p.getName();
                    clips = p.getTimeline().getClipCount();
                    if (room == IdeaEngine.Room.STUDIO) here = clipIndexAt(p, getIntent().getLongExtra(EXTRA_PLAYHEAD_MS, -1));
                }
            } catch (Exception ignored) { }
            final SlideFlow f = new SlideFlow(name, clips, here,
                    room == IdeaEngine.Room.STUDIO ? getIntent().getLongExtra(EXTRA_PLAYHEAD_MS, -1) : -1);
            if (kind != null) f.startingWith(kind);
            runOnUiThread(() -> {
                slideFlow = f;
                askSlide();
            });
        });
    }

    private static int clipIndexAt(@NonNull com.fadcam.ui.faditor.model.FaditorProject p, long ms) {
        if (ms < 0) return -1;
        com.fadcam.ui.faditor.model.Timeline tl = p.getTimeline();
        long acc = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            long d = Math.max(1, tl.getClip(i).getVisualDurationMs());
            if (ms < acc + d) return i;
            acc += d;
        }
        return -1;
    }

    private void askSlide() {
        SlideFlow f = slideFlow;
        if (f == null) return;
        SlideFlow.Question q = f.question();
        addBotMessage(q.text);
        voice.speak(q.text);
        actor.setMood(JoybotView.Mood.TALK);
        actor.postDelayed(() -> { if (!busy) actor.setMood(JoybotView.Mood.IDLE); }, 900L);
        JoybotChatViews.fillChoices(chipsRow, q.choices, "Never mind", c -> {
            if (c == null) { cancelSlideFlow(); return; }
            addUserMessage(c.label);
            answerSlide(c.id);
        });
        chipsScroll.setVisibility(View.VISIBLE);
        chipsScroll.scrollTo(0, 0);
        if (q.wantsTyping) {
            if (q.prefill != null && inputField.getText().toString().trim().isEmpty()) {
                inputField.setText(q.prefill);
                inputField.setSelection(inputField.getText().length());
            }
            inputField.requestFocus();
        }
    }

    private void answerSlide(@NonNull String answer) {
        SlideFlow f = slideFlow;
        if (f == null) return;
        SlideFlow.Stage was = f.stage();
        if (!f.answer(answer)) {
            addBotMessage("Sorry, I didn't catch that — pick one of the buttons, or say it another way.");
            return;
        }
        // The words are in; whatever was pre-filled for them must not linger into the next answer.
        if (was == SlideFlow.Stage.TEXT) inputField.setText("");
        if (f.stage() != SlideFlow.Stage.DONE) { askSlide(); return; }
        slideFlow = null;
        inputField.setText("");
        Map<String, Object> args = f.toolArgs();
        Idea build = Idea.of("slide_built", Idea.Kind.DIRECT, "Make the slide")
                .say(f.summary())
                .undo(f.summary().replace(".", ""))
                .step(new Idea.Step("generate_slide", args))
                .build();
        if (!AiService.ready(this, apiKey)) {
            addBotMessage("Building it with my built-in design (connect an AI in ⚙ for a custom one)…");
        } else {
            addBotMessage("Designing it now — this takes a few seconds…");
        }
        runDirect(build);
    }

    private void cancelSlideFlow() {
        slideFlow = null;
        awaitingPictureSubject = false;
        pictureReference = null;
        addBotMessage("No problem.");
        if (currentOffer != null) showChips(currentOffer.ideas); else refreshIdeas(false);
    }

    // ── pictures (roadmap 3.10) ─────────────────────────────────────────────

    /** Waiting for "what should the picture show?". */
    private boolean awaitingPictureSubject;
    /** The person's own picture to make the new one from (a "data:" URL), or null. */
    @Nullable private String pictureReference;

    /** "Make a picture", optionally already knowing what of. */
    private void startPictureFlow(@Nullable String subject) {
        if (subject == null || subject.trim().length() < 3) {
            awaitingPictureSubject = true;
            addBotMessage("What should the picture show?");
            voice.speak("What should the picture show?");
            JoybotChatViews.fillChoices(chipsRow, new ArrayList<>(), "Never mind", c -> {
                awaitingPictureSubject = false;
                cancelSlideFlow();
            });
            chipsScroll.setVisibility(View.VISIBLE);
            inputField.requestFocus();
            return;
        }
        awaitingPictureSubject = false;
        offerPictureWays(subject.trim());
    }

    /** The ways to get it: here (priced), or free through their own AI app. */
    private void offerPictureWays(@NonNull String subject) {
        hideChips();
        final boolean canPay = JoybotPictures.canGenerate(this);
        aiExecutor.execute(() -> {
            String price = canPay ? JoybotPictures.priceWords(JoybotPictures.model(this)) : null;
            runOnUiThread(() -> {
                List<SlideFlow.Choice> ways = new ArrayList<>();
                if (canPay) {
                    ways.add(choice("make", "Make it here" + (price != null ? " — " + price : ""),
                            "Joybot makes it with your OpenRouter key"
                                    + (price != null ? "; OpenRouter lists it at " + price : "")));
                }
                ways.add(choice("handoff", "Ask my ChatGPT or Gemini (free)",
                        "I write the request; you make it in your own AI app and share it back"));
                addBotMessage(canPay
                        ? "I can make it here, or you can make it free in your own AI app and share it back."
                        : "Make it free in your own AI app (ChatGPT, Gemini…) and share it back — "
                          + "I'll write the request for you." + (AiService.isCustom(this) ? ""
                          : " With an OpenRouter key I can also make it right here."));
                JoybotChatViews.fillChoices(chipsRow, ways, "Never mind", c -> {
                    if (c == null) { cancelSlideFlow(); return; }
                    addUserMessage(c.label);
                    if ("make".equals(c.id)) makePicture(subject); else handOffPicture(subject);
                });
                chipsScroll.setVisibility(View.VISIBLE);
                chipsScroll.scrollTo(0, 0);
            });
        });
    }

    @NonNull
    private static SlideFlow.Choice choice(String id, String label, String hint) {
        return SlideFlow.choiceOf(id, label, hint);
    }

    /** The canvas shape for pictures, read from the project. Blocking. */
    @NonNull
    private String canvasPreset() {
        if (projectId == null) return "16:9";
        try {
            com.fadcam.ui.faditor.model.FaditorProject p =
                    new com.fadcam.ui.faditor.project.ProjectStorage(this).load(projectId);
            return p == null || p.getCanvasPreset() == null ? "16:9" : p.getCanvasPreset();
        } catch (Exception e) {
            return "16:9";
        }
    }

    private void makePicture(@NonNull String subject) {
        setBusy(true);
        actor.setMood(JoybotView.Mood.WORK);
        final String ref = pictureReference;
        pictureReference = null;
        addBotMessage((ref != null ? "Making it from your picture" : "Making it")
                + " — this usually takes 5 to 30 seconds…");
        final String pid = projectId;
        aiExecutor.execute(() -> {
            String err = null;
            JoybotPictures.Saved saved = null;
            Bitmap thumb = null;
            try {
                JoybotPictures.Picture pic = JoybotPictures.generate(this, subject, canvasPreset(),
                        ref == null ? null : java.util.Collections.singletonList(ref));
                saved = JoybotPictures.save(this, pid, pic, subject);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                thumb = BitmapFactory.decodeByteArray(pic.bytes, 0, pic.bytes.length, o);
            } catch (JoybotPictures.Problem p) {
                err = p.getMessage();
            } catch (Exception | OutOfMemoryError e) {
                err = "Making the picture failed.";
            }
            final String e = err;
            final JoybotPictures.Saved u = saved;
            final Bitmap b = thumb;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (e != null) {
                    addBotMessage(e);
                    voice.speak(e);
                    actor.setMood(JoybotView.Mood.OOPS);
                    actor.postDelayed(() -> actor.setMood(JoybotView.Mood.IDLE), 700L);
                    refreshIdeas(false);
                    return;
                }
                showPictureCard(subject, u, b);
            });
        });
    }

    /** The new picture in the chat, with a way onto the timeline; Joybot flies up to show it off. */
    private void showPictureCard(@NonNull String subject, @NonNull JoybotPictures.Saved saved,
                                 @Nullable Bitmap thumb) {
        final Uri uri = saved.uri;
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this,
                "Here it is — saved as " + saved.where() + ".",
                projectId == null ? null
                        : "In line adds it to the main track as a 5-second picture clip: the video gets "
                        + "longer and what follows moves later. On top puts it over what's already there "
                        + "as a new layer: the length stays the same, good for stacking.");
        if (thumb != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(thumb);
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(dp(260));
            iv.setPadding(0, dp(8), 0, 0);
            card.root.addView(iv, 1);
        }
        if (projectId != null) {
            card.buttons.addView(JoybotChatViews.cardButton(this, "In line",
                    "Add it at the playhead as a picture clip; the video gets longer (one undo)",
                    v -> putInVideo(uri, false)));
            card.buttons.addView(JoybotChatViews.cardButton(this, "On top",
                    "Put it over the video at the playhead as a new layer; the length stays the same (one undo)",
                    v -> putInVideo(uri, true)));
        }
        card.buttons.addView(JoybotChatViews.cardButton(this, "Another one",
                "Make a new version of the same picture", v -> makePicture(subject)));
        messagesContainer.addView(card.root);
        messageViews.add(card.root);
        addTickerTick(false);
        scrollToBottom();
        voice.speak("Here it is.");
        refreshIdeas(false);
        try {
            conversationHistory.add(new JSONObject().put("role", "assistant")
                    .put("content", "I made a picture of: " + subject + ". It is saved in the Joy Creator folder."));
        } catch (Exception ignored) { }
        showOff(card.root);
    }

    /** Onto the timeline through the share hand-off: the editor inserts it at the playhead. */
    private void putInVideo(@NonNull Uri uri, boolean onTop) {
        if (projectId == null) return;
        if (onTop) {
            AIChatState.putLayerInsert(projectId, uri);
        } else {
            java.util.List<Uri> one = new ArrayList<>();
            one.add(uri);
            com.fadcam.joyfolder.studio.ShareInbox.put(projectId, one);
        }
        if (room != IdeaEngine.Room.STUDIO) {
            startActivity(new Intent(this, com.fadcam.ui.faditor.FaditorEditorActivity.class)
                    .putExtra(com.fadcam.ui.faditor.FaditorEditorActivity.EXTRA_PROJECT_ID, projectId));
        }
        finish();
    }

    /** The free route: their own AI app makes it; Share to Joy Creator brings it back. */
    private void handOffPicture(@NonNull String subject) {
        pictureReference = null;
        aiExecutor.execute(() -> {
            String p = JoybotPictures.handoffPrompt(subject, canvasPreset());
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Picture request", p));
                addBotMessage("I copied this request:\n\n“" + p + "”\n\nPick your AI app, make the "
                        + "picture there, then tap Share → Joy Creator and it lands in this project.");
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, p);
                try {
                    startActivity(Intent.createChooser(send, "Make it with…"));
                } catch (Exception ignored) { }
                refreshIdeas(false);
            });
        });
    }

    /** "Make a picture of a red fox" → "a red fox"; null when it isn't a picture request. */
    @Nullable
    static String pictureSubject(@NonNull String text) {
        String t = text.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)^(please\\s+)?(can you\\s+)?(make|generate|create|draw|paint)\\s+(me\\s+)?(a|an)?\\s*"
                        + "(picture|image|photo|illustration|drawing|thumbnail|background)\\s*(of|showing|with)?\\s*(.*)$")
                .matcher(t);
        if (!m.find()) return null;
        return m.group(8) == null ? "" : m.group(8).replaceAll("[.!?]+$", "").trim();
    }

    // ── busy, stop, and what a typed sentence might mean ─────────────────────

    private void setBusy(boolean b) {
        busy = b;
        runOnUiThread(() -> {
            stopButton.setVisibility(b ? View.VISIBLE : View.GONE);
            sendButton.setVisibility(b ? View.GONE : View.VISIBLE);
            View mic = findViewById(R.id.chat_mic);
            if (mic != null) mic.setVisibility(b ? View.GONE : View.VISIBLE);
            if (b) hideChips();
        });
    }

    /** Stop button: end the reply being written; tool runs stop between steps. */
    private void stopWorking() {
        cancelled = true;
        JoybotJobs.cancel();
        voice.hush();
        endHandsFree();
        JoybotAgent.cancel(chatKey);   // this chat's requests only
        addBotMessage("Stopped.");
        actor.setMood(JoybotView.Mood.IDLE);
        setBusy(false);
        refreshIdeas(false);
    }

    /** End of a model reply (or chain): buttons back, Joybot settles. */
    private void doneTalking(boolean ok) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            if (ok) {
                actor.setMood(JoybotView.Mood.IDLE);
            } else {
                actor.setMood(JoybotView.Mood.OOPS);
                actor.postDelayed(() -> actor.setMood(JoybotView.Mood.IDLE), 700L);
            }
            refreshIdeas(false);
        });
    }

    /**
     * A typed sentence that is plainly one of the buttons runs that button, so "cut the ums"
     * works offline and needs no model online. Anything with more to it goes to the model.
     */
    @Nullable
    private Idea matchIdea(@NonNull String text, boolean loose) {
        String t = norm(text);
        if (t.isEmpty()) return null;
        List<Idea> pool = currentOffer != null ? currentOffer.ideas : noProjectIdeas();
        for (Idea i : pool) {
            if (i.kind == Idea.Kind.TYPE) continue;
            if (t.equals(norm(i.label)) || t.equals(norm(i.say))) return i;
        }
        if (!loose) return null;
        String[][] words = {
                {"transcri", "transcribe", "captions_new"}, {"caption", "captions_on", "captions_new"},
                {"um", "cut_ums", "find_ums"}, {"ums", "cut_ums", "find_ums"},
                {"uh", "cut_ums", "find_ums"}, {"filler", "cut_ums", "find_ums"},
                {"silen", "silences", "tighten"}, {"pause", "silences", "tighten"},
                {"chapter", "chapters"}, {"transition", "transitions"}, {"crossfade", "transitions"},
                {"visuali", "visualizer"}, {"b roll", "broll"}, {"broll", "broll"}};
        for (String[] w : words) {
            // Short keys only as whole words: "ums" must not match "drums".
            if (!(" " + t + " ").contains(w[0].length() <= 3 ? " " + w[0] + " " : w[0])) continue;
            for (int k = 1; k < w.length; k++) {
                for (Idea i : pool) if (i.key.equals(w[k])) return i;
            }
        }
        return null;
    }

    @NonNull
    private static String norm(@NonNull String s) {
        return s.toLowerCase(java.util.Locale.US).replaceAll("[“”\"'.,!?]", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean wantsSlide(@NonNull String text) {
        String t = norm(text);
        return t.contains("slide") || t.contains("title card") || t.contains("make a title")
                || t.contains("intro card") || t.contains("end card") || t.contains("credits");
    }

    /**
     * "Make this end here" / "make this start here": the selected item and the playhead, done
     * directly — free, instant, and impossible for a model to only pretend to do.
     */
    @Nullable
    private Idea thisHereIdea(@NonNull String text) {
        String sel = getIntent().getStringExtra(EXTRA_SELECTED_ID);
        long ph = getIntent().getLongExtra(EXTRA_PLAYHEAD_MS, -1);
        if (room != IdeaEngine.Room.STUDIO || sel == null || ph < 0) return null;
        String t = norm(text);
        boolean end = t.matches("(please )?(make|have|let) (this|it|the selected( one| item)?) (end|stop|finish)( right)? (here|now|at the playhead)( please)?");
        boolean start = t.matches("(please )?(make|have|let) (this|it|the selected( one| item)?) (start|begin)( right)? (here|now|at the playhead)( please)?");
        if (!end && !start) return null;
        return Idea.of(end ? "end_here" : "start_here", Idea.Kind.DIRECT, end ? "End it here" : "Start it here")
                .target(sel).say(text)
                .undo(end ? "End it at the playhead" : "Start it at the playhead")
                .step(Idea.Step.of("set_item_time", "id", sel, end ? "endMs" : "startMs", ph))
                .build();
    }

    /** Read the project as the undo snapshot format. Null when there is no project. */
    @Nullable
    private String snapshotJson() {
        if (projectId == null) return null;
        try {
            com.fadcam.ui.faditor.project.ProjectStorage st = new com.fadcam.ui.faditor.project.ProjectStorage(this);
            com.fadcam.ui.faditor.model.FaditorProject p = st.load(projectId);
            return p == null ? null : st.toJson(p);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Run a model-requested tool, parking the request's undo step at its first real change: later
     * tools in the same request share that step (one request, one undo).
     */
    @NonNull
    private String runToolRecorded(@NonNull String toolName, @NonNull JSONObject args) {
        String before = snapshotJson();
        String result = createExecutor().executeTool(toolName, args);
        if (before != null && !requestStepPushed) {
            String after = snapshotJson();
            if (after != null && !after.equals(before)) {
                requestStepPushed = true;
                AIChatState.pushStep(projectId, requestLabel, before);
                final AIChatState.Step step = AIChatState.peekStep(projectId);
                runOnUiThread(() -> addUndoRow(step));
            }
        }
        return result;
    }

    /** A slim Undo for a request the model carried out. */
    private void addUndoRow(@Nullable AIChatState.Step step) {
        if (step == null) return;
        JoybotChatViews.Card card = JoybotChatViews.resultCard(this,
                "Changed: " + step.label.replaceFirst("^Joybot: ", ""), null);
        card.title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        card.title.setTextColor(com.fadcam.ui.faditor.Studio.INK_FAINT);
        card.buttons.addView(JoybotChatViews.cardButton(this, "Undo",
                "Put the project back the way it was before this request", v -> undoStep(card, step)));
        cardForStep.put(step, card);
        messagesContainer.addView(card.root);
        scrollToBottom();
    }

    /** A short quote of the person's words for the undo history. */
    @NonNull
    private static String requestLabelFor(@NonNull String userText) {
        String t = userText.trim().replaceAll("\\s+", " ");
        if (t.length() > 40) t = t.substring(0, 39).trim() + "…";
        return "Joybot: “" + t + "”";
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDictation();
        // Shown, not tapped, and now leaving: one ignore. Two and that idea goes quiet.
        if (isFinishing() && projectId != null && shownBestId != null && !bestTapped) {
            ideaMemory.markIgnored(projectId, shownBestId);
        }
    }

    // ── Settings dialog ────────────────────────────────────────────

    private void showSettingsDialog() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        String currentKey = AiKey.get(this);
        String currentModel = prefs.sharedPreferences.getString(PREF_MODEL, DEFAULT_MODEL);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        final AiAccounts.Account account = AiAccounts.active(this);
        final int accountCount = AiAccounts.list(this).size();
        TextView labelKey = new TextView(this);
        labelKey.setText((accountCount > 1 ? "Account: " + (account.name.isEmpty() ? "new" : account.name) + "\n" : "")
                + "API key — paste one from OpenRouter, OpenAI, Anthropic, Google…");
        labelKey.setTextColor(Studio.INK_DIM);
        root.addView(labelKey);

        EditText inputKey = new EditText(this);
        inputKey.setText(currentKey);
        inputKey.setHint("sk-or-v1-...");
        inputKey.setTextColor(Studio.INK);
        // Hidden like a password: the settings screen gets screenshotted and screen-recorded, and a
        // key on screen is a key someone else can spend (found while testing on the Note 9,
        // 2026-10-14). Hold the eye to check what was pasted.
        inputKey.setSingleLine(true);
        inputKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        keyRow.addView(inputKey, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView eye = new TextView(this);
        eye.setTypeface(ResourcesCompat.getFont(this, R.font.materialicons));
        eye.setText("visibility");
        eye.setTextSize(20);
        eye.setTextColor(Studio.INK_DIM);
        eye.setPadding(dp(10), dp(8), dp(4), dp(8));
        eye.setContentDescription("Hold to show the key");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(eye, "Hold to show the key");
        eye.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                inputKey.setTransformationMethod(null);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                inputKey.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            }
            inputKey.setSelection(inputKey.getText().length());
            return true;
        });
        keyRow.addView(eye);
        root.addView(keyRow);
        // Which service the pasted key belongs to, said back in words (filled in below).
        TextView detected = new TextView(this);
        detected.setTextColor(Studio.GO);
        detected.setTextSize(12);
        root.addView(detected);

        TextView labelModel = new TextView(this);
        labelModel.setText("Model:");
        labelModel.setTextColor(Studio.INK_DIM);
        labelModel.setPadding(0, pad, 0, 0);
        root.addView(labelModel);

        EditText inputModel = new EditText(this);
        inputModel.setText(currentModel);
        inputModel.setHint(DEFAULT_MODEL);
        inputModel.setTextColor(Studio.INK);
        root.addView(inputModel);

        TextView labelPic = new TextView(this);
        labelPic.setText("Picture model (for pictures Joybot makes):");
        labelPic.setTextColor(Studio.INK_DIM);
        labelPic.setPadding(0, pad, 0, 0);
        root.addView(labelPic);

        EditText inputPic = new EditText(this);
        String curPic = JoybotPictures.model(this);
        inputPic.setText(JoybotPictures.DEFAULT_MODEL.equals(curPic) ? "" : curPic);
        inputPic.setHint(JoybotPictures.DEFAULT_MODEL);
        inputPic.setTextColor(Studio.INK);
        inputPic.setSingleLine(true);
        root.addView(inputPic);

        TextView labelBase = new TextView(this);
        labelBase.setText("Service address (optional):");
        labelBase.setTextColor(Studio.INK_DIM);
        labelBase.setPadding(0, pad, 0, 0);
        root.addView(labelBase);

        EditText inputBase = new EditText(this);
        inputBase.setText(AiService.isCustom(this) ? AiService.base(this) : "");
        inputBase.setHint(AiService.DEFAULT_BASE);
        inputBase.setTextColor(Studio.INK);
        inputBase.setSingleLine(true);
        inputBase.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(inputBase);

        // PASTE A KEY, GET THE SERVICE. The key's own shape says whose it is (sk-or- OpenRouter,
        // sk-proj- OpenAI, sk-ant- Anthropic…), so the address fills itself in and an OpenRouter
        // model name that would mean nothing to OpenAI is cleared for a pick from their own list.
        android.text.TextWatcher detect = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                com.fadcam.ui.faditor.ai.joybot.AiProviders.Provider p =
                        com.fadcam.ui.faditor.ai.joybot.AiProviders.fromKey(e.toString());
                if (p == null) { detected.setText(""); return; }
                String base = inputBase.getText().toString().trim();
                if (p.isOpenRouter()) {
                    // An OpenRouter key can't be spent at OpenAI's address: back to the default.
                    if (!base.isEmpty() && !"OpenRouter".equals(
                            com.fadcam.ui.faditor.ai.joybot.AiProviders.nameForBase(base))) {
                        inputBase.setText("");
                    }
                    if (inputModel.getText().toString().trim().isEmpty()) inputModel.setText(DEFAULT_MODEL);
                    detected.setText("✓ An OpenRouter key.");
                } else {
                    if (base.isEmpty() || !base.equals(p.base)) inputBase.setText(p.base);
                    String m = inputModel.getText().toString().trim();
                    if (m.isEmpty() || m.equals(DEFAULT_MODEL) || m.contains("/")) {
                        inputModel.setText("");
                        inputModel.setHint("you'll pick from your " + p.name + " models");
                    }
                    detected.setText("✓ A " + p.name + " key — address filled in for you.");
                }
            }
        };
        inputKey.addTextChangedListener(detect);
        detect.afterTextChanged(inputKey.getText());

        TextView hint = new TextView(this);
        hint.setText("Free tiers available on OpenRouter for lower-end models.\n"
                + "Any OpenAI-compatible service works: paste its key (the address fills in), or put "
                + "its address above (https, or http://localhost for one running on this phone).\n"
                + "More than one subscription? Add each as its own account: tap the model name at "
                + "the top to switch.\n"
                + "No key and no address: offline helper mode.");
        hint.setTextColor(Studio.INK_FAINT);
        hint.setTextSize(12);
        hint.setPadding(0, pad, 0, 0);
        root.addView(hint);

        // Joybot's voice: off unless asked for (it is a phone in a quiet room as often as not).
        CheckBox readAloud = new CheckBox(this);
        readAloud.setText("Read Joybot's replies aloud");
        readAloud.setTextColor(Studio.INK_DIM);
        readAloud.setChecked(JoybotVoice.readAloud(this));
        readAloud.setPadding(0, pad / 2, 0, 0);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(readAloud,
                "Joybot speaks his answers with the phone's voice");
        root.addView(readAloud);

        // Custom title: mascot icon (flipped inward, facing right toward the label) + text.
        int titlePad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleRow.setPadding(titlePad, titlePad, titlePad, 0);

        ImageView titleIcon = new ImageView(this);
        int iconSize = dp(52);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(dp(8));
        titleIcon.setLayoutParams(iconLp);
        titleIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        titleIcon.setImageResource(R.drawable.joybot_neutral);
        titleIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                resolveThemeColor(android.R.attr.colorPrimary)));
        titleIcon.setScaleX(0f);
        titleIcon.setScaleY(0f);
        titleRow.addView(titleIcon);

        TextView titleText = new TextView(this);
        titleText.setText("AI Assistant Settings");
        titleText.setTextColor(Studio.INK);
        titleText.setTextSize(18);
        titleText.setTypeface(null, Typeface.BOLD);
        titleRow.addView(titleText);

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setCustomTitle(titleRow)
                .setView(root)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (accountCount > 1) {
            builder.setNeutralButton("Remove account", (d, w) ->
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setMessage("Forget the " + account.name + " account and its key on this phone?")
                            .setPositiveButton("Forget it", (d2, w2) -> {
                                AiAccounts.remove(this, account.id);
                                reloadAccount();
                                addBotMessage("Forgot that account. Now using "
                                        + AiAccounts.active(this).name + ".");
                            })
                            .setNegativeButton("Keep", null)
                            .show());
        }
        androidx.appcompat.app.AlertDialog dialog = builder.create();

        // A key and an address are not a username and password: keep password managers out.
        for (EditText f : new EditText[]{inputKey, inputModel, inputBase, inputPic}) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                f.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            }
        }

        dialog.setOnShowListener(d -> {
            titleIcon.animate()
                    .scaleX(-1f)
                    .scaleY(1f)
                    .setStartDelay(80)
                    .setDuration(420)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f))
                    .start();

            // Override the positive button so we can pop the icon back out before dismissing.
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String key = inputKey.getText().toString().trim();
                String mdl = inputModel.getText().toString().trim();
                AiKey.set(this, key);
                AiService.setBase(this, inputBase.getText().toString());
                // Not OpenRouter and no model yet: one is picked from the account's own list below.
                final boolean pickModel = AiService.isCustom(this) && (mdl.isEmpty() || mdl.equals(DEFAULT_MODEL));
                final String finalMdl = mdl.isEmpty() ? DEFAULT_MODEL : mdl;
                JoybotVoice.setReadAloud(this, readAloud.isChecked());
                JoybotPictures.setModel(this, inputPic.getText().toString());
                prefs.sharedPreferences.edit()
                        .putString(PREF_MODEL, finalMdl)
                        .apply();
                if (AiService.isCustom(this)) ModelCatalog.setTalkingToPictures(this, false);
                // Keep the saved account in step; one still wearing an automatic name ("OpenRouter",
                // or none yet) is renamed for the service its key now points at ("OpenAI").
                String newBase = AiService.isCustom(this) ? AiService.base(this) : "";
                boolean autoName = account.name.isEmpty()
                        || account.name.startsWith(com.fadcam.ui.faditor.ai.joybot.AiProviders.nameForBase(account.base));
                AiAccounts.syncActive(this, autoName ? AiAccounts.freshName(this, newBase, account.id) : null);
                apiKey = key;
                model = finalMdl;
                updateModelLabel();
                if (pickModel) {
                    dialog.dismiss();
                    addBotMessage("Connected to " + AiAccounts.active(this).name
                            + ". Pick the model to talk to — these are the ones your account has.");
                    ModelPicker.show(this, false, new ModelPicker.OnPick() {
                        @Override public void chat(@NonNull ModelCatalog.Model m) { useChatModel(m.id); }
                        @Override public void pictures(@NonNull ModelCatalog.Model m) { usePictureModel(m.id); }
                    });
                    return;
                }
                titleIcon.animate()
                        .scaleX(0f)
                        .scaleY(0f)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> {
                            addBotMessage("Settings updated. Model: " + finalMdl
                                    + (AiService.ready(this, key) ? " (connected" + (AiService.isCustom(this) ? " to " + AiService.base(this) : "") + ")" : " (offline mode)"));
                            dialog.dismiss();
                        })
                        .start();
            });
        });

        dialog.show();
    }

    // ── UI helpers ─────────────────────────────────────────────────

    /** Trim messageLog to MAX_MESSAGE_LOG entries (oldest removed first). */
    private void trimMessageLog() {
        synchronized (messageLog) {
            while (messageLog.size() > MAX_MESSAGE_LOG) {
                messageLog.remove(0);
            }
        }
    }

    private void addUserMessage(@NonNull String text) {
        synchronized (messageLog) { messageLog.add(new String[]{"user", text}); trimMessageLog(); }
        FrameLayout wrapper = createMessageBubble(text, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(60), dp(4), dp(8), dp(4));
        wrapper.setLayoutParams(lp);
        messagesContainer.addView(wrapper);
        messageViews.add(wrapper);
        addTickerTick(true);
        scrollToBottom();
    }

    private void addBotMessage(@NonNull String text) {
        synchronized (messageLog) { messageLog.add(new String[]{"bot", text}); trimMessageLog(); }
        FrameLayout wrapper = createMessageBubble(text, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), dp(4), dp(60), dp(4));
        wrapper.setLayoutParams(lp);
        messagesContainer.addView(wrapper);
        messageViews.add(wrapper);
        addTickerTick(false);
        scrollToBottom();
    }

    private FrameLayout createMessageBubble(@NonNull String text, boolean isUser) {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setTag(isUser ? "user" : "bot");

        TextView tv = new TextView(this);
        tv.setText(isUser ? text : JoybotChatViews.styled(text));
        tv.setTextIsSelectable(true);
        // Joybot's answer carries the colour; your own words fade back (owner, 10-28).
        tv.setTextColor(isUser ? Studio.INK_DIM : Studio.ON_GO);
        tv.setBackgroundResource(isUser ? R.drawable.chat_bubble_user : R.drawable.chat_bubble_joybot);
        // Tail side (bottom for user, top for bot — see chat_bubble_user/bot.xml) gets extra
        // padding so text clears the reserved tail-nub band baked into the background drawable.
        if (isUser) {
            tv.setPadding(dp(14), dp(10), dp(14), dp(17));
        } else {
            tv.setPadding(dp(14), dp(17), dp(14), dp(10));
        }
        tv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        wrapper.addView(tv);

        // Copy affordance: the real Material "content_copy" glyph (two overlapping rounded
        // squares) rendered as a low-alpha watermark, not a solid button chip.
        TextView copyBtn = new TextView(this);
        Typeface materialIcons = ResourcesCompat.getFont(this, R.font.materialicons);
        copyBtn.setTypeface(materialIcons);
        copyBtn.setText("content_copy");
        copyBtn.setTextColor(isUser ? Studio.alpha(Studio.LABEL, 0x66) : Studio.alpha(Studio.ON_GO, 0x66));
        copyBtn.setTextSize(13);
        copyBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        clp.setMargins(0, 0, dp(2), isUser ? dp(9) : dp(2));
        copyBtn.setLayoutParams(clp);
        copyBtn.setTag(text);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(copyBtn, "Copy this message");
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("message", (CharSequence) v.getTag()));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
        });
        wrapper.addView(copyBtn);

        wrapper.setPadding(0, 0, 0, dp(4));
        return wrapper;
    }

    // ── Proposal confirmation cards (narrative reorder / b-roll) ─────

    /**
     * Tool results may carry a {@code @@PROPOSAL:<type>@@<json>} sentinel (from
     * {@code analyze_narrative_structure} / {@code suggest_broll_placements}). When present, render an
     * interactive KEEP/DROP card with an Apply button instead of dumping raw JSON, so the user confirms
     * the most destructive AI edit visually. Falls back to a normal bot bubble on any parse failure.
     */
    private void addToolResultMessage(@NonNull String toolMsg) {
        int idx = toolMsg.indexOf("@@PROPOSAL:");
        if (idx >= 0 && renderProposalCard(toolMsg.substring(idx))) return;
        // One quiet line per tool: what Joybot did, not the tool's report (which is written for
        // the model and full of ids). Tap it to read the whole thing.
        int arrow = toolMsg.indexOf(" → ");
        String name = arrow > 0 ? toolMsg.substring(0, arrow) : "tool";
        String body = arrow > 0 ? toolMsg.substring(arrow + 3) : toolMsg;
        boolean failed = DirectRunner.isFailure(body);
        String line = (failed ? "✗ " : "✓ ") + friendlyToolName(name)
                + (failed ? " — " + plainError(body).split("\n")[0] : "");
        TextView tv = new TextView(this);
        tv.setText(line);
        tv.setTextSize(12);
        tv.setTextColor(failed ? com.fadcam.ui.faditor.Studio.CAREFUL : com.fadcam.ui.faditor.Studio.INK_FAINT);
        tv.setPadding(dp(16), dp(2), dp(60), dp(2));
        tv.setOnClickListener(v -> tv.setText(tv.getText().toString().equals(line) ? line + "\n" + body : line));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(tv, "Tap to see the details");
        messagesContainer.addView(tv);
        synchronized (messageLog) { messageLog.add(new String[]{"tool", toolMsg}); trimMessageLog(); }
        scrollToBottom();
    }

    /** Tool names in the words a person would use. */
    @NonNull
    private static String friendlyToolName(@NonNull String tool) {
        switch (tool) {
            case "generate_transcript": return "Transcribed";
            case "cut_all_fillers": return "Cut the ums";
            case "toggle_captions": return "Captions";
            case "set_caption_style": return "Caption style";
            case "detect_silence": return "Found the quiet parts";
            case "remove_silence": return "Cut the silences";
            case "generate_slide": return "Made a slide";
            case "add_transition": return "Added a transition";
            case "add_text_overlay": return "Added text";
            case "add_visualizer": return "Added a visualizer";
            case "get_project_state": case "get_transcript": case "describe_clip": return "Read the project";
            case "apply_edit_script": return "Made the edits";
            case "ai_enhance": return "Cleaned up the speech";
            default:
                // A typed action has its own title, in the person's words ("Change a VisLab look").
                com.fadcam.ui.faditor.ai.actions.ActionSpec spec =
                        com.fadcam.ui.faditor.ai.actions.StudioActions.registry().spec(tool);
                if (spec == null) spec = com.fadcam.ui.faditor.ai.actions.AppActions.registry().spec(tool);
                if (spec != null) return spec.title;
                String t = tool.replace('_', ' ');
                return t.isEmpty() ? "Done" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
    }

    private boolean renderProposalCard(@NonNull String s) {
        try {
            String body = s.substring("@@PROPOSAL:".length());
            int tagEnd = body.indexOf("@@");
            if (tagEnd < 0) return false;
            String type = body.substring(0, tagEnd);
            String jsonStr = JoybotAgent.extractJsonObject(body.substring(tagEnd + 2));
            if (jsonStr == null) return false;
            JSONObject payload = new JSONObject(jsonStr);
            if ("narrative".equals(type)) { buildNarrativeCard(payload); return true; }
            if ("broll".equals(type)) { buildBrollCard(payload); return true; }
            if ("avatar_rig".equals(type)) { buildAvatarRigCard(payload); return true; }
            if ("export".equals(type)) { buildExportCard(payload); return true; }
        } catch (Exception ignored) { }
        return false;
    }

    private void buildNarrativeCard(@NonNull JSONObject payload) throws Exception {
        final String clipId = payload.optString("clipId", "");
        JSONArray chunks = payload.getJSONArray("chunks");
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < chunks.length(); i++) {
            JSONObject c = chunks.optJSONObject(i);
            if (c != null) rows.add(c);
        }
        rows.sort((a, b) -> Long.compare(a.optLong("startMs"), b.optLong("startMs")));

        LinearLayout card = newCardContainer();
        addCardTitle(card, "✂️ Narrative proposal");
        addCardSubtitle(card, "Uncheck a segment to drop it, then Apply.");

        final List<CheckBox> boxes = new ArrayList<>();
        final List<JSONObject> rowData = new ArrayList<>();
        for (JSONObject c : rows) {
            long st = c.optLong("startMs", 0), en = c.optLong("endMs", 0);
            String summary = c.optString("summary", "");
            CheckBox cb = new CheckBox(this);
            cb.setText(formatClock(st) + "–" + formatClock(en)
                    + (summary.isEmpty() ? "" : "  " + summary));
            cb.setChecked(c.optBoolean("keep", true));
            cb.setTextColor(Studio.INK_DIM);
            cb.setPadding(dp(2), dp(4), dp(2), dp(4));
            card.addView(cb);
            boxes.add(cb);
            rowData.add(c);
        }

        addCardButtons(card, v -> {
            try {
                JSONArray out = new JSONArray();
                for (int i = 0; i < rowData.size(); i++) {
                    JSONObject c = new JSONObject(rowData.get(i).toString());
                    c.put("keep", boxes.get(i).isChecked());
                    out.put(c);
                }
                JSONObject args = new JSONObject();
                args.put("clipId", clipId);
                args.put("chunks", out);
                applyProposal("apply_narrative_proposal", args);
            } catch (Exception e) {
                addBotMessage("Could not build proposal: " + e.getMessage());
            }
        });
        messagesContainer.addView(card);
        scrollToBottom();
    }

    private void buildBrollCard(@NonNull JSONObject payload) throws Exception {
        JSONArray cutaways = payload.getJSONArray("cutaways");
        LinearLayout card = newCardContainer();
        addCardTitle(card, "🎬 B-roll suggestions");
        addCardSubtitle(card, "Uncheck any you don't want, then Apply.");

        final List<CheckBox> boxes = new ArrayList<>();
        final List<JSONObject> rowData = new ArrayList<>();
        for (int i = 0; i < cutaways.length(); i++) {
            JSONObject c = cutaways.optJSONObject(i);
            if (c == null) continue;
            long at = c.optLong("atMs", 0);
            String name = c.optString("assetName", "asset");
            String reason = c.optString("reason", "");
            CheckBox cb = new CheckBox(this);
            cb.setText(formatClock(at) + "  " + name + (reason.isEmpty() ? "" : "  — " + reason));
            cb.setChecked(true);
            cb.setTextColor(Studio.INK_DIM);
            cb.setPadding(dp(2), dp(4), dp(2), dp(4));
            card.addView(cb);
            boxes.add(cb);
            rowData.add(c);
        }

        addCardButtons(card, v -> {
            try {
                JSONArray out = new JSONArray();
                for (int i = 0; i < rowData.size(); i++) {
                    if (boxes.get(i).isChecked()) out.put(rowData.get(i));
                }
                if (out.length() == 0) { addBotMessage("Nothing selected."); return; }
                JSONObject args = new JSONObject();
                args.put("cutaways", out);
                applyProposal("apply_broll_proposal", args);
            } catch (Exception e) {
                addBotMessage("Could not build cutaways: " + e.getMessage());
            }
        });
        messagesContainer.addView(card);
        scrollToBottom();
    }

    /**
     * A5 AI rigging: confirm card for a proposed avatar rig ({@code author_avatar_rig}).
     * Apply registers the rig via {@code apply_avatar_rig}; the user then arms the pose
     * extremes in Avatar Studio (AI does structure, human does taste).
     */
    private void buildAvatarRigCard(@NonNull JSONObject payload) throws Exception {
        final JSONObject rig = payload.getJSONObject("rig");
        String name = payload.optString("name", rig.optString("name", "Avatar"));
        int partCount = payload.optInt("partCount", rig.optJSONArray("parts") != null
                ? rig.optJSONArray("parts").length() : 0);
        int domainCount = payload.optInt("domainCount", rig.optJSONArray("domains") != null
                ? rig.optJSONArray("domains").length() : 0);

        LinearLayout card = newCardContainer();
        addCardTitle(card, "🎭 Avatar rig");
        addCardSubtitle(card, "Create \"" + name + "\" — " + partCount + " parts, "
                + domainCount + " pose domains. Apply, then tune the extremes in Avatar Studio.");

        JSONArray parts = rig.optJSONArray("parts");
        if (parts != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(p.optString("id", "?"));
            }
            TextView t = new TextView(this);
            t.setText(sb.toString());
            t.setTextColor(Studio.INK_FAINT);
            t.setTextSize(12);
            t.setPadding(0, 0, 0, dp(4));
            card.addView(t);
        }

        addCardButtons(card, v -> {
            try {
                JSONObject args = new JSONObject();
                args.put("rig", rig);
                applyProposal("apply_avatar_rig", args);
            } catch (Exception e) {
                addBotMessage("Could not build rig: " + e.getMessage());
            }
        });
        messagesContainer.addView(card);
        scrollToBottom();
    }

    /**
     * The export card (family "export", ExportActions.export_video). Joybot never starts an export: the card
     * shows the settings, and only "Export now" starts the Studio's own export.
     */
    private void buildExportCard(@NonNull JSONObject payload) throws Exception {
        final String projectId = payload.optString("projectId", "");
        final String resolution = payload.optString("resolution", "Original");
        final String quality = payload.optString("quality", "HIGH");
        final boolean ranged = payload.has("fromMs") && payload.has("toMs");
        final long fromMs = payload.optLong("fromMs", 0L);
        final long toMs = payload.optLong("toMs", 0L);
        String qualityWords = "LOW".equals(quality) ? "Low quality"
                : "MEDIUM".equals(quality) ? "Medium quality" : "High quality";
        String part = ranged ? formatClock(fromMs) + "–" + formatClock(toMs) : "full video";

        LinearLayout card = newCardContainer();
        addCardTitle(card, "Export the video?");
        addCardSubtitle(card, resolution + " · " + qualityWords + " · " + part);
        addExportCardButtons(card, v -> startExportFromCard(projectId, resolution, quality, ranged, fromMs, toMs));
        messagesContainer.addView(card);
        scrollToBottom();
    }

    /** Not now / Export now for the export card: the same row as addCardButtons, with its own labels. */
    private void addExportCardButtons(@NonNull LinearLayout card, @NonNull View.OnClickListener exportNow) {
        LinearLayout rowView = new LinearLayout(this);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        rowView.setPadding(0, dp(8), 0, 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        Button notNow = new Button(this);
        notNow.setText("Not now");
        notNow.setLayoutParams(blp);
        notNow.setContentDescription("Don't export the video now");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(notNow, "Don't export now");
        notNow.setOnClickListener(v -> {
            View sub = card.getChildAt(1);
            if (sub instanceof TextView) ((TextView) sub).setText("Not exported.");
            setCardEnabled(card, false);
        });
        Button go = new Button(this);
        go.setText("Export now");
        go.setLayoutParams(blp);
        go.setContentDescription("Start the export now with these settings");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(go, "Start the export with these settings");
        go.setOnClickListener(v -> { setCardEnabled(card, false); exportNow.onClick(v); });
        rowView.addView(notNow);
        rowView.addView(go);
        card.addView(rowView);
    }

    /**
     * "Export now" on the export card. Starts the export the Studio's Export button starts
     * (FaditorEditorActivity.doStartOutOfProcessExport): the project is written to an export snapshot file,
     * then ExportService gets ACTION_START_EXPORT with the same extras. Off the UI thread.
     */
    private void startExportFromCard(@NonNull String projectId, @NonNull String resolution,
                                     @NonNull String quality, boolean ranged, long fromMs, long toMs) {
        aiExecutor.execute(() -> {
            String problem = null;
            try {
                com.fadcam.ui.faditor.project.ProjectStorage storage =
                        new com.fadcam.ui.faditor.project.ProjectStorage(this);
                storage.flushPendingWrites();
                com.fadcam.ui.faditor.model.FaditorProject proj = storage.load(projectId);
                if (proj == null) {
                    problem = "I couldn't find this project any more, so nothing was exported.";
                } else if (proj.getTimeline().getClipCount() == 0) {
                    problem = "There is no video to export yet.";
                } else {
                    // The sheet's choices, set on the copy the export reads (the same fields the sheet sets).
                    com.fadcam.ui.faditor.model.ExportSettings es = proj.getExportSettings();
                    if (es != null) {
                        com.fadcam.ui.faditor.model.ExportSettings.Resolution res =
                                com.fadcam.ui.faditor.model.ExportSettings.Resolution.ORIGINAL;
                        if ("1080p".equals(resolution)) res = com.fadcam.ui.faditor.model.ExportSettings.Resolution.FHD_1080P;
                        else if ("720p".equals(resolution)) res = com.fadcam.ui.faditor.model.ExportSettings.Resolution.HD_720P;
                        else if ("480p".equals(resolution)) res = com.fadcam.ui.faditor.model.ExportSettings.Resolution.SD_480P;
                        es.setResolution(res);
                        com.fadcam.ui.faditor.model.ExportSettings.Quality q =
                                com.fadcam.ui.faditor.model.ExportSettings.Quality.HIGH;
                        if ("MEDIUM".equals(quality)) q = com.fadcam.ui.faditor.model.ExportSettings.Quality.MEDIUM;
                        else if ("LOW".equals(quality)) q = com.fadcam.ui.faditor.model.ExportSettings.Quality.LOW;
                        es.setQuality(q);
                    }
                    // The snapshot file the Studio writes at export time (ExportService reads it and deletes it).
                    java.io.File dir = new java.io.File(getFilesDir(), "faditor");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    java.io.File snapshot = new java.io.File(dir,
                            "export_snapshot_" + System.currentTimeMillis() + ".json");
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(snapshot)) {
                        fos.write(storage.toJson(proj).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    android.content.Intent svc = new android.content.Intent(this,
                            com.fadcam.ui.faditor.export.ExportService.class);
                    svc.setAction(com.fadcam.ui.faditor.export.ExportService.ACTION_START_EXPORT);
                    svc.putExtra(com.fadcam.ui.faditor.export.ExportService.EXTRA_PROJECT_SNAPSHOT_PATH,
                            snapshot.getAbsolutePath());
                    svc.putExtra(com.fadcam.ui.faditor.export.ExportService.EXTRA_AUDIO_ONLY, false);
                    if (ranged) {
                        svc.putExtra(com.fadcam.ui.faditor.export.ExportService.EXTRA_RANGE_START_MS, fromMs);
                        svc.putExtra(com.fadcam.ui.faditor.export.ExportService.EXTRA_RANGE_END_MS, toMs);
                    }
                    // The loudness target the sheet last stored (the Studio reads the same key).
                    svc.putExtra("extra_loudness_target", getSharedPreferences("faditor_export", MODE_PRIVATE)
                            .getString("pending_loudness_target", "OFF"));
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                }
            } catch (Exception e) {
                problem = "The export didn't start: " + e.getMessage();
            }
            final String result = problem;
            runOnUiThread(() -> addBotMessage(result != null ? result
                    : "Exporting — you'll see the progress bar in the Studio and lobby; it keeps going if you close me."));
        });
    }

    private void applyProposal(@NonNull String tool, @NonNull JSONObject args) {
        addBotMessage("⏳ Applying…");
        // Applying a card is its own request: its own undo step.
        requestLabel = "Joybot: " + friendlyToolName(tool).toLowerCase(java.util.Locale.US)
                .replace("apply ", "apply the ");
        requestStepPushed = false;
        aiExecutor.execute(() -> {
            String res = runToolRecorded(tool, args);
            runOnUiThread(() -> {
                addToolResultMessage(tool + " → " + res);
                refreshIdeas(false);
            });
        });
    }

    private LinearLayout newCardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setTag(JoybotChatViews.CARD_TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.chat_bubble_bot);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), dp(4), dp(40), dp(4));
        card.setLayoutParams(lp);
        return card;
    }

    private void addCardTitle(@NonNull LinearLayout card, @NonNull String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Studio.INK);
        t.setTextSize(16);
        t.setPadding(0, 0, 0, dp(2));
        card.addView(t);
    }

    private void addCardSubtitle(@NonNull LinearLayout card, @NonNull String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Studio.INK_FAINT);
        t.setTextSize(12);
        t.setPadding(0, 0, 0, dp(6));
        card.addView(t);
    }

    private void addCardButtons(@NonNull LinearLayout card, @NonNull View.OnClickListener apply) {
        LinearLayout rowView = new LinearLayout(this);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        rowView.setPadding(0, dp(8), 0, 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        Button discard = new Button(this);
        discard.setText("Discard");
        discard.setLayoutParams(blp);
        discard.setOnClickListener(v -> {
            View sub = card.getChildAt(1);
            if (sub instanceof TextView) ((TextView) sub).setText("Discarded.");
            setCardEnabled(card, false);
        });
        Button applyBtn = new Button(this);
        applyBtn.setText("Apply");
        applyBtn.setLayoutParams(blp);
        applyBtn.setOnClickListener(v -> { setCardEnabled(card, false); apply.onClick(v); });
        androidx.appcompat.widget.TooltipCompat.setTooltipText(discard, "Don't make this change");
        androidx.appcompat.widget.TooltipCompat.setTooltipText(applyBtn, "Make this change (one Undo puts it back)");
        rowView.addView(discard);
        rowView.addView(applyBtn);
        card.addView(rowView);
    }

    private void setCardEnabled(@NonNull LinearLayout card, boolean enabled) {
        for (int i = 0; i < card.getChildCount(); i++) {
            View v = card.getChildAt(i);
            v.setEnabled(enabled);
            if (v instanceof LinearLayout) {
                LinearLayout r = (LinearLayout) v;
                for (int j = 0; j < r.getChildCount(); j++) r.getChildAt(j).setEnabled(enabled);
            }
        }
    }

    private static String formatClock(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void updateLastBotMessage(@NonNull String text) {
        int count = messagesContainer.getChildCount();
        // Joybot's newest text bubble, even when an Undo card or a tool line landed below it
        // after it opened (the "Working…" that used to be left behind) — but never one older than
        // the person's newest message.
        for (int i = count - 1; i >= 0; i--) {
            Object tag = messagesContainer.getChildAt(i).getTag();
            if ("user".equals(tag)) break;
            if ("bot".equals(tag)) { count = i + 1; break; }
        }
        if (count > 0) {
            View last = messagesContainer.getChildAt(count - 1);
            if (last instanceof FrameLayout) {
                FrameLayout fl = (FrameLayout) last;
                if (fl.getChildCount() > 0 && fl.getChildAt(0) instanceof TextView) {
                    ((TextView) fl.getChildAt(0)).setText(JoybotChatViews.styled(text));
                }
                if (fl.getChildCount() > 1) {
                    fl.getChildAt(1).setTag(text);
                }
            } else if (last instanceof TextView) {
                ((TextView) last).setText(text);
            }
        }
        // Also update the message log
        synchronized (messageLog) {
            if (!messageLog.isEmpty()) {
                String[] last = messageLog.get(messageLog.size() - 1);
                if ("bot".equals(last[0])) {
                    last[1] = text;
                } else {
                    messageLog.add(new String[]{"bot", text});
                    trimMessageLog();
                }
            } else {
                messageLog.add(new String[]{"bot", text});
                trimMessageLog();
            }
        }
        scrollToBottom();
    }

    private void scrollToBottom() {
        scrollContainer.post(() -> {
            scrollContainer.fullScroll(ScrollView.FOCUS_DOWN);
            messagesContainer.requestLayout();
        });
        scrollContainer.postDelayed(() -> scrollContainer.fullScroll(ScrollView.FOCUS_DOWN), 100);
    }

    private void addTickerTick(boolean isUser) {
        if (tickerContainer == null) return;
        View tick = new View(this);
        // Short rounded-end notch (a tiny "pill" line), not a dot.
        int tickWidth = dp(4);
        int tickHeight = dp(11);
        int margin = dp(2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tickWidth, tickHeight);
        lp.setMargins(0, margin, 0, margin);
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        tick.setLayoutParams(lp);
        // Colours match the bubble they stand for: yours wear the studio gradient, Joybot's are grey.
        GradientDrawable shape = isUser
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Studio.GO, Studio.GO_END})
                : new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(tickWidth / 2f);
        if (!isUser) shape.setColor(Studio.INK_OFF);
        tick.setBackground(shape);
        final int idx = messageViews.size() - 1;
        tick.setOnClickListener(v -> scrollToMessage(idx));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(tick, isUser ? "Jump to what you said here" : "Jump to Joybot's message");
        tickerContainer.addView(tick);
    }

    private void rebuildTicker() {
        if (tickerContainer == null) return;
        tickerContainer.removeAllViews();
        for (int i = 0; i < messageViews.size(); i++) {
            boolean isUser = false;
            if (i < messageLog.size()) {
                String[] entry = messageLog.get(i);
                isUser = "user".equals(entry[0]);
            }
            addTickerTick(isUser);
        }
    }

    private void scrollToMessage(int index) {
        if (index < 0 || index >= messageViews.size()) return;
        View target = messageViews.get(index);
        int scrollY = target.getTop() - scrollContainer.getPaddingTop();
        scrollContainer.smoothScrollTo(0, Math.max(0, scrollY));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AIChatState.chatActive = false;
        JoybotJobs.attach(null);
        JoybotAgent.detach(agentListener);
        handsFreeClock.removeCallbacksAndMessages(null);
        if (voice != null) voice.release();
        aiExecutor.shutdownNow();
    }

    /**
     * Update the model-slug label, with an EYE when the connected model can see images.
     *
     * <p>The eye is an honesty affordance, not a gate: routing already pre-filters by what the
     * request contains, so attaching an image to {@code openrouter/free} is safe regardless.
     * What the user cannot otherwise know is whether asking "does this text read over that
     * background" will actually be answered by something with eyes.</p>
     *
     * <p>Three states, deliberately. {@link ModelCapabilities.Vision#UNKNOWN} — before the
     * catalogue has been fetched, or after a failed fetch — shows NO marker at all rather than a
     * dark eye, because "nobody has checked" is not the same claim as "it cannot see".</p>
     */
    private void updateModelLabel() {
        if (modelLabel == null) return;
        boolean hasKey = AiService.ready(this, apiKey);
        modelLabel.setVisibility(View.VISIBLE);
        if (!hasKey) {
            modelLabel.setText("offline helper \u25BE");
            return;
        }
        if (ModelCatalog.talkingToPictures(this)) {
            modelLabel.setText("\uD83C\uDFA8 " + ModelCatalog.nameOf(this, JoybotPictures.model(this)) + " \u25BE");
            return;
        }
        String id = model == null || model.isEmpty() ? DEFAULT_MODEL : model;
        ModelCapabilities.Vision v = visionOf(id);
        String marker = v == ModelCapabilities.Vision.YES ? "  \uD83D\uDC41"
                : (v == ModelCapabilities.Vision.NO ? "  \u20E0" : "");
        // With more than one account, say which one is talking.
        String account = AiAccounts.list(this).size() > 1 ? AiAccounts.active(this).name + " \u00B7 " : "";
        if (needsModelPick()) {
            // OpenRouter's free router means nothing to OpenAI: say what's missing, not "Free models".
            modelLabel.setText(account + "pick a model \u25BE");
            return;
        }
        modelLabel.setText(account + ModelCatalog.nameOf(this, id) + marker + " \u25BE");
        maybeRefreshCapabilities();
    }

    /**
     * Tap the model name: the starred models, one tap to switch (owner, 2026-10-14: "switch on the
     * fly between my starred models"), plus "Find more models…".
     */
    private void showModelSwitcher() {
        java.util.List<String> ids = new ArrayList<>(ModelCatalog.stars(this));
        java.util.Collections.sort(ids);
        // The free router first (on OpenRouter — it means nothing to another service).
        ids.remove(ModelCatalog.FREE_ROUTER.id);
        if (!AiService.isCustom(this)) ids.add(0, ModelCatalog.FREE_ROUTER.id);
        final java.util.List<String> labels = new ArrayList<>();
        final java.util.List<Runnable> actions = new ArrayList<>();
        boolean pics = ModelCatalog.talkingToPictures(this);
        for (String id : ids) {
            boolean isPic = ModelCatalog.isPictureModel(this, id);
            boolean current = isPic ? (pics && id.equals(JoybotPictures.model(this)))
                    : (!pics && id.equals(model == null ? DEFAULT_MODEL : model));
            labels.add((current ? "\u2713 " : "    ") + (isPic ? "\uD83C\uDFA8 " : "") + ModelCatalog.nameOf(this, id));
            actions.add(() -> {
                if (isPic) usePictureModel(id); else useChatModel(id);
            });
        }
        labels.add("Find and star more models\u2026");
        actions.add(() -> ModelPicker.show(this, false, new ModelPicker.OnPick() {
            @Override public void chat(@NonNull ModelCatalog.Model m) { useChatModel(m.id); }
            @Override public void pictures(@NonNull ModelCatalog.Model m) { usePictureModel(m.id); }
        }));
        // MORE THAN ONE SUBSCRIPTION (owner, 2026-10-15): every other saved account, one tap away.
        AiAccounts.Account in = AiAccounts.active(this);
        for (AiAccounts.Account a : AiAccounts.list(this)) {
            if (a.id.equals(in.id)) continue;
            labels.add("\u21c4 Use your " + a.name + " account");
            actions.add(() -> switchAccount(a.id));
        }
        labels.add("+ Add another AI account\u2026");
        actions.add(() -> {
            AiAccounts.addBlank(this);
            reloadAccount();
            showSettingsDialog();
        });
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Talk to\u2026")
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    /** Use another saved AI account: its key, address and model become Joybot's. */
    private void switchAccount(@NonNull String id) {
        AiAccounts.switchTo(this, id);
        reloadAccount();
        AiAccounts.Account a = AiAccounts.active(this);
        if (AiService.isCustom(this) && (model == null || model.isEmpty() || model.equals(DEFAULT_MODEL))) {
            addBotMessage("Now using your " + a.name + " account. Pick a model to talk to.");
            ModelPicker.show(this, false, new ModelPicker.OnPick() {
                @Override public void chat(@NonNull ModelCatalog.Model m) { useChatModel(m.id); }
                @Override public void pictures(@NonNull ModelCatalog.Model m) { usePictureModel(m.id); }
            });
            return;
        }
        addBotMessage("Now using your " + a.name + " account (" + ModelCatalog.nameOf(this, model) + ").");
    }

    /** Re-read the account in use into this screen (after a switch, an add or a removal). */
    private void reloadAccount() {
        apiKey = AiKey.get(this);
        model = SharedPreferencesManager.getInstance(this).sharedPreferences.getString(PREF_MODEL, DEFAULT_MODEL);
        if (AiService.isCustom(this)) ModelCatalog.setTalkingToPictures(this, false);
        updateModelLabel();
    }

    /** Every typed action, as "What can I do here?" lists them. */
    @NonNull
    private static List<com.fadcam.ui.faditor.ai.joybot.WhatHere.Action> roomActions() {
        List<com.fadcam.ui.faditor.ai.joybot.WhatHere.Action> out = new ArrayList<>();
        List<com.fadcam.ui.faditor.ai.actions.ActionSpec> specs = new ArrayList<>(
                com.fadcam.ui.faditor.ai.actions.StudioActions.registry().specs());
        specs.addAll(com.fadcam.ui.faditor.ai.actions.AppActions.registry().specs());
        for (com.fadcam.ui.faditor.ai.actions.ActionSpec s : specs) {
            out.add(new com.fadcam.ui.faditor.ai.joybot.WhatHere.Action(s.tool, s.title, s.wing,
                    s.effect == com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect.READ));
        }
        return out;
    }

    /** What the connect-an-AI conversation needs from this screen. */
    @NonNull
    private com.fadcam.ui.faditor.ai.joybot.SetupFlow.Host setupHost() {
        return new com.fadcam.ui.faditor.ai.joybot.SetupFlow.Host() {
            @NonNull @Override public android.content.Context context() { return ChatAssistantActivity.this; }
            @Override public void say(@NonNull String text) { addBotMessage(text); }
            @Override public void show(@NonNull View card) {
                messagesContainer.addView(card);
                messageViews.add(card);
                scrollToBottom();
            }
            @Override public void openUrl(@NonNull String url) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                } catch (Exception e) {
                    addBotMessage("I couldn't open a browser. The address is " + url);
                }
            }
            @Override public void readyForKey(boolean on) { keyMode(on); }
            @Override public void connect(@NonNull String key, @NonNull com.fadcam.ui.faditor.ai.joybot.AiProviders.Provider p) {
                connectKey(key, p);
            }
            @Nullable @Override public String connectedTo() {
                return AiService.ready(ChatAssistantActivity.this, apiKey) ? AiAccounts.active(ChatAssistantActivity.this).name : null;
            }
            @Override public boolean bridgeReady() { return true; }
            @Override public void openBridge() {
                startActivity(com.fadcam.ui.faditor.ai.bridge.BridgeActivity.intent(ChatAssistantActivity.this, projectId, room));
            }
        };
    }

    /**
     * The typing box takes a key: hidden like a password and kept out of the keyboard's suggestions
     * (a keyboard that learns a key offers it to the next app). Off again once a key arrives.
     */
    private void keyMode(boolean on) {
        if (chatInputType == -1) chatInputType = inputField.getInputType();
        if (on) {
            inputField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            inputField.setHint("Paste your key here");
            inputField.requestFocus();
        } else {
            inputField.setInputType(chatInputType);
            inputField.setHint("Ask anything\u2026");
        }
    }

    /**
     * A key checked in the chat becomes an account and the one in use, as ⚙ would save it. The account
     * in use is kept when it already has a key (switch back from the model name at the top).
     */
    private void connectKey(@NonNull String key, @NonNull com.fadcam.ui.faditor.ai.joybot.AiProviders.Provider p) {
        if (AiAccounts.active(this).hasKey() || AiService.isCustom(this)) AiAccounts.addBlank(this);
        AiKey.set(this, key);
        AiService.setBase(this, p.base);
        String mdl = p.startModel.isEmpty() ? DEFAULT_MODEL : p.startModel;
        SharedPreferencesManager.getInstance(this).sharedPreferences.edit().putString(PREF_MODEL, mdl).apply();
        if (AiService.isCustom(this)) ModelCatalog.setTalkingToPictures(this, false);
        AiAccounts.syncActive(this, AiAccounts.freshName(this, AiService.isCustom(this) ? AiService.base(this) : "",
                AiAccounts.active(this).id));
        apiKey = AiKey.get(this);
        model = mdl;
        updateModelLabel();
        refreshIdeas(false);
        if (AiService.isCustom(this)) {
            // Not OpenRouter: the person picks from the models their own account has.
            addBotMessage("Pick the model to talk to — these are the ones your account has.");
            ModelPicker.show(this, false, new ModelPicker.OnPick() {
                @Override public void chat(@NonNull ModelCatalog.Model m) { useChatModel(m.id); }
                @Override public void pictures(@NonNull ModelCatalog.Model m) { usePictureModel(m.id); }
            });
            return;
        }
        addBotMessage(com.fadcam.ui.faditor.ai.joybot.SetupTalk.firstTry(projectId != null));
    }

    private void useChatModel(@NonNull String id) {
        model = id;
        SharedPreferencesManager.getInstance(this).sharedPreferences.edit().putString(PREF_MODEL, id).apply();
        AiAccounts.syncActive(this, null);
        ModelCatalog.setTalkingToPictures(this, false);
        updateModelLabel();
        addBotMessage("Now talking with " + ModelCatalog.nameOf(this, id) + ".");
    }

    private void usePictureModel(@NonNull String id) {
        JoybotPictures.setModel(this, id);
        ModelCatalog.setTalkingToPictures(this, true);
        updateModelLabel();
        final String name = ModelCatalog.nameOf(this, id);
        aiExecutor.execute(() -> {
            String price = JoybotPictures.priceWords(id);
            runOnUiThread(() -> addBotMessage("Now every message makes a picture with " + name
                    + (price != null ? " (" + price + " each)" : "") + ". Tap the name at the top to talk normally again."));
        });
    }

    /** Header speaker: read Joybot's replies aloud, on or off, without opening settings. */
    private void addReadAloudToggle() {
        View settings = findViewById(R.id.chat_settings);
        if (settings == null || !(settings.getParent() instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) settings.getParent();
        TextView speaker = new TextView(this);
        speaker.setTypeface(ResourcesCompat.getFont(this, R.font.materialicons));
        speaker.setTextSize(22);
        speaker.setGravity(android.view.Gravity.CENTER);
        speaker.setTextColor(com.fadcam.ui.faditor.Studio.INK);
        Runnable paint = () -> {
            boolean on = JoybotVoice.readAloud(this);
            speaker.setText(on ? "volume_up" : "volume_off");
            speaker.setAlpha(on ? 1f : 0.55f);
            androidx.appcompat.widget.TooltipCompat.setTooltipText(speaker,
                    on ? "Joybot reads his replies aloud (tap to stop)" : "Have Joybot read his replies aloud");
            speaker.setContentDescription(on ? "Reading aloud: on" : "Reading aloud: off");
        };
        paint.run();
        speaker.setOnClickListener(v -> {
            boolean on = !JoybotVoice.readAloud(this);
            JoybotVoice.setReadAloud(this, on);
            if (!on) voice.hush();
            paint.run();
        });
        bar.addView(speaker, bar.indexOfChild(settings), new LinearLayout.LayoutParams(dp(40), dp(40)));
    }

    /**
     * Kick a background refresh of the model catalogue if it is missing or stale, then redraw.
     *
     * <p>Public endpoint, no key needed. Failure is silent by design — a network hiccup must not
     * flip a correct eye into a wrong one, so the previous answer simply stands.</p>
     */
    private void maybeRefreshCapabilities() {
        if (!ModelCapabilities.needsRefresh()) return;
        aiExecutor.execute(() -> ModelCapabilities.refreshBlocking(
                () -> runOnUiThread(() -> {
                    // Guard: the refresh outlives a quick close of this screen.
                    if (isFinishing() || isDestroyed() || modelLabel == null) return;
                    updateModelLabel();
                })));
    }
}
