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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI Assistant chat shell for Faditor.
 *
 * <p>Connects to an OpenRouter-compatible API endpoint. When no API key is
 * configured, it falls back to a simple offline helper with FAQ and feature
 * shortcuts so the chat surface is always useful.</p>
 *
 * <p>This is the entry point for the "AI as editor assistant" vision:
 * the chat can answer questions about the app, suggest edits, and (later)
 * emit EditScripts that FadCam validates and applies.</p>
 */
public class ChatAssistantActivity extends AppCompatActivity {

    private static final String TAG = "ChatAssistant";

    public static final String EXTRA_PROJECT_ID = "chat_project_id";

    private static final String PREF_API_KEY = "ai_api_key";
    private static final String PREF_MODEL = "ai_model";
    private static final String PREF_PROVIDER = "ai_provider";

    private static final String DEFAULT_ENDPOINT =
            "https://openrouter.ai/api/v1/chat/completions";
    private static final String DEFAULT_MODEL = "openrouter/auto";

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
    private boolean aiJobRunning;

    private String apiKey;
    private String model;
    private String projectId;

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final OkHttpClient httpClient = new OkHttpClient();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // Static message log — survives Activity recreation so the user sees
    // their conversation when they reopen the chat. Capped at 200 entries
    // to prevent unbounded memory growth.
    private static final int MAX_MESSAGE_LOG = 200;
    private static final List<String[]> messageLog = new ArrayList<>(); // [role, text]

    // Static conversation history — survives Activity recreation so the
    // conversation persists while the app is open, even if the user closes
    // and reopens the chat window.
    private static final List<JSONObject> conversationHistory = new ArrayList<>();

    /** Clear the static conversation (called when the editor opens a different project). */
    public static void resetConversation() {
        conversationHistory.clear();
        messageLog.clear();
    }

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

        AIChatState.chatActive = true;

        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        apiKey = prefs.sharedPreferences.getString(PREF_API_KEY, "");
        model = prefs.sharedPreferences.getString(PREF_MODEL, DEFAULT_MODEL);

        initViews();
        initSystemPrompt();

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
    }

    private void restoreMessages() {
        // Copy to avoid ConcurrentModificationException if the background
        // AI thread modifies messageLog while we're iterating.
        List<String[]> snapshot;
        synchronized (messageLog) {
            snapshot = new ArrayList<>(messageLog);
        }
        for (String[] msg : snapshot) {
            if ("user".equals(msg[0])) {
                addUserMessage(msg[1]);
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
        updateModelLabel();

        ImageButton btnAttach = findViewById(R.id.chat_attach);
        btnAttach.setOnClickListener(v -> showAttachChooser());

        ImageButton btnSettings = findViewById(R.id.chat_settings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        ImageButton btnMic = findViewById(R.id.chat_mic);
        btnMic.setOnClickListener(v -> startVoiceInput());

        // Mascot bounces in when the chat window lands. scaleX animates 0 -> -1 (not
        // 0 -> 1) to preserve the inward-facing horizontal flip set in the layout.
        View headerIcon = findViewById(R.id.chat_header_ai_icon);
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

    private void startAiJob(@NonNull String task) {
        aiJobRunning = true;
        AIJobService.start(this, task);
        // Persist job state so it can be resumed after process death
        new AIJobStore(this).saveJob(task, projectId != null ? projectId : "", null, task);
    }

    private void updateAiJob(@NonNull String task, int percent) {
        if (aiJobRunning) {
            AIJobService.update(this, task, percent);
            new AIJobStore(this).updateProgress(percent, task);
        }
    }

    private void finishAiJob() {
        if (aiJobRunning) {
            aiJobRunning = false;
            AIJobService.stop(this);
            new AIJobStore(this).clearJob();
        }
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
            updateAiJob(task, percent);
        });
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private String humanizeToolName(@NonNull String toolName) {
        return toolName.replace('_', ' ');
    }

    private static final int VOICE_REQUEST_CODE = 1001;
    private static final int IMAGE_PICK_REQUEST_CODE = 1002;

    private void startVoiceInput() {
        try {
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your request");
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
        if (requestCode == VOICE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> results = data
                    .getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                inputField.setText(results.get(0));
                inputField.setSelection(inputField.getText().length());
                inputField.requestFocus();
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

        if (apiKey == null || apiKey.isEmpty()) {
            addBotMessage("Connect an API key (top-right settings) and I can look at this.");
            return;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        frame.compress(Bitmap.CompressFormat.JPEG, 85, baos);
        String base64 = android.util.Base64.encodeToString(
                baos.toByteArray(), android.util.Base64.NO_WRAP);
        // Say so plainly when the connected model has no eyes, rather than sending an image into
        // a text-only model and reporting whatever it hallucinates about a picture it never saw.
        if (ModelCapabilities.visionFor(model) == ModelCapabilities.Vision.NO) {
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
        caption.setText("[Attached image] Describe what you see.");
        caption.setTextColor(Studio.INK_FAINT);
        caption.setTextSize(12);
        wrapper.addView(caption);

        messagesContainer.addView(wrapper);
        messageViews.add(wrapper);
        addTickerTick(true);
        scrollToBottom();

        // Reset input to "describe this image" context
        inputField.setText("Describe what's in this image.");

        // Encode the full-size bitmap as base64 for the AI vision API
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
        byte[] imageBytes = baos.toByteArray();
        String base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP);

        // Send as a multimodal vision message if API key is set
        if (apiKey != null && !apiKey.isEmpty()) {
            sendVisionMessage(base64, bitmap.getWidth(), bitmap.getHeight());
        } else {
            addBotMessage("I can see an image is attached. "
                    + "Connect an API key (top-right settings) for AI vision analysis.");
        }
        bitmap.recycle();
    }

    /** Send a multimodal vision message to the AI with an image (base64 JPEG). */
    private void sendVisionMessage(@NonNull String base64Image, int imgW, int imgH) {
        // View state must be read on the main thread — capture the prompt here,
        // before the work is handed to the background executor.
        final String userText = inputField.getText().toString().trim();

        addBotMessage("Thinking...");

        aiExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("max_tokens", 1024);
                body.put("temperature", 0.7);

                // Build multimodal content: text + image (text part first —
                // OpenRouter parses the parts in order).
                JSONArray content = new JSONArray();

                JSONObject textPart = new JSONObject();
                textPart.put("type", "text");
                textPart.put("text", userText);
                content.put(textPart);

                JSONObject imagePart = new JSONObject();
                JSONObject imageUrl = new JSONObject();
                imageUrl.put("url", "data:image/jpeg;base64," + base64Image);
                imagePart.put("type", "image_url");
                imagePart.put("image_url", imageUrl);
                content.put(imagePart);

                // The image travels in THIS turn's outgoing request only. What is
                // retained in the static history is a compact textual placeholder —
                // keeping the data URL would re-send megabytes of base64 on every
                // subsequent turn (cost, bandwidth and memory).
                JSONObject outgoingUserMsg = new JSONObject();
                outgoingUserMsg.put("role", "user");
                outgoingUserMsg.put("content", content);

                JSONObject retainedUserMsg = new JSONObject();
                retainedUserMsg.put("role", "user");
                // Worded as a FACT ABOUT THE PAST, not as an attachment. The first wording said
                // "[image attached: …]", which is true of that turn and false of every turn
                // after it — and a model reading it on turn 2, with no image present, replies
                // "I can't see the image you attached, I don't have image analysis
                // capabilities". That denial then reads to the user as vision being broken when
                // it is the history being honest. Measured on device 2026-08-06.
                retainedUserMsg.put("content", userText
                        + "\n[The user showed you a " + imgW + "x" + imgH + " video frame at this"
                        + " point in the conversation. It was visible to you then and is not"
                        + " re-attached now; ask them to share it again if you need another look.]");
                conversationHistory.add(retainedUserMsg);

                // Build the request body: prior history (with placeholders) plus
                // this turn's full multimodal message.
                JSONArray messages = new JSONArray();
                for (JSONObject msg : conversationHistory) {
                    messages.put(msg == retainedUserMsg ? outgoingUserMsg : msg);
                }

                body.put("messages", messages);

                Request request = new Request.Builder()
                        .url(DEFAULT_ENDPOINT)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    runOnUiThread(() -> updateLastBotMessage(
                            "API error " + response.code() + ": "
                                    + responseBody.substring(0, Math.min(200, responseBody.length()))));
                    return;
                }

                JSONObject json = new JSONObject(responseBody);
                // A completion names the model that ACTUALLY served it, which for a router
                // (openrouter/free, openrouter/auto) is only knowable after the fact. Recording
                // it turns the eye indicator's prediction into an observation.
                ModelCapabilities.noteServedModel(json.optString("model", null));
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    runOnUiThread(() -> updateLastBotMessage("Empty response from AI."));
                    return;
                }

                String aiText = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");

                JSONObject aiMsg = new JSONObject();
                aiMsg.put("role", "assistant");
                aiMsg.put("content", aiText);
                conversationHistory.add(aiMsg);

                runOnUiThread(() -> updateLastBotMessage(aiText));
            } catch (Exception e) {
                runOnUiThread(() -> updateLastBotMessage("Vision error: " + e.getMessage()));
            }
        });
    }

    private void initSystemPrompt() {
        // Only build the system prompt once — if the conversation already
        // has entries, the user is returning to an existing chat session.
        if (!conversationHistory.isEmpty()) return;

        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");

            StringBuilder sb = new StringBuilder();
            sb.append("You are FadCam AI, an assistant for the FadCam video editor app. ");
            sb.append("You help users with video editing tasks. ");
            sb.append("You can suggest edits, explain features, and guide users. ");
            sb.append("Be concise and practical.\n\n");

            sb.append(AIToolExecutor.getToolDescriptions());
            sb.append("\nWhen you want to DO something, call a tool. ");
            sb.append("When you want to EXPLAIN something, just reply normally.\n\n");

            sb.append("When suggesting edits, you can emit an EditScript as JSON:\n");
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

            // Include project context if available
            if (projectId != null && !projectId.isEmpty()) {
                sb.append("Current project context:\n");
                try {
                    com.fadcam.ui.faditor.project.ProjectStorage storage =
                            new com.fadcam.ui.faditor.project.ProjectStorage(this);
                    com.fadcam.ui.faditor.model.FaditorProject proj = storage.load(projectId);
                    if (proj != null) {
                        sb.append("Project: ").append(proj.getName()).append("\n");
                        sb.append("Project folder: ").append(
                                new java.io.File(getFilesDir(), "faditor/projects/" + projectId).getAbsolutePath()
                        ).append("\n");
                        sb.append("Clips: ").append(proj.getTimeline().getClipCount()).append("\n");
                        com.fadcam.ui.faditor.model.Timeline tl = proj.getTimeline();
                        for (int i = 0; i < tl.getClipCount(); i++) {
                            com.fadcam.ui.faditor.model.Clip c = tl.getClip(i);
                            sb.append("  Clip ").append(i)
                                    .append(" id=").append(c.getId())
                                    .append(" dur=").append(c.getTrimmedDurationMs()).append("ms")
                                    .append(" speed=").append(c.getSpeedMultiplier())
                                    .append(" muted=").append(c.isAudioMuted());
                            if (c.isCaptionsEnabled()) {
                                sb.append(" captions=").append(c.getCaptionStyleId());
                            }
                            if (!c.getRemovedSpans().isEmpty()) {
                                sb.append(" cuts=").append(c.getRemovedSpans().size());
                            }
                            sb.append("\n");

                            // Include transcript text with word-level timestamps
                            // so the AI can reason about content and suggest cuts.
                            java.util.List<com.fadcam.ui.faditor.transcript.NamedTranscript> versions =
                                    c.getTranscripts();
                            if (!versions.isEmpty()) {
                                com.fadcam.ui.faditor.transcript.NamedTranscript active =
                                        c.getActiveNamedTranscript();
                                if (active == null) active = versions.get(0);
                                sb.append("  Transcript (").append(active.engine)
                                        .append("):\n");
                                for (com.fadcam.ui.faditor.transcript.TranscriptWord w
                                        : active.transcript.words) {
                                    sb.append("    [").append(w.startMs).append("-")
                                            .append(w.endMs).append("ms] ");
                                    sb.append(w.text);
                                    if (w.struck) sb.append(" [CUT]");
                                    sb.append("\n");
                                }
                            } else {
                                sb.append("  (No transcript yet — user may ask you about content)\n");
                            }
                        }
                        if (tl.hasAudioClips()) {
                            sb.append("Audio clips: ").append(tl.getAudioClipCount()).append("\n");
                            for (int ai = 0; ai < tl.getAudioClipCount(); ai++) {
                                com.fadcam.ui.faditor.model.AudioClip ac = tl.getAudioClips().get(ai);
                                sb.append("  AudioClip ").append(ai)
                                        .append(" id=").append(ac.getId())
                                        .append(" dur=").append(ac.getTrimmedDurationMs()).append("ms")
                                        .append(" muted=").append(ac.isMuted());
                                if (ac.isCaptionsEnabled()) {
                                    sb.append(" captions=").append(ac.getCaptionStyleId());
                                }
                                sb.append("\n");
                                java.util.List<com.fadcam.ui.faditor.transcript.NamedTranscript> aVersions =
                                        ac.getTranscripts();
                                if (!aVersions.isEmpty()) {
                                    com.fadcam.ui.faditor.transcript.NamedTranscript aActive =
                                            ac.getActiveNamedTranscript();
                                    if (aActive == null) aActive = aVersions.get(0);
                                    sb.append("  Transcript (").append(aActive.engine)
                                            .append("):\n");
                                    for (com.fadcam.ui.faditor.transcript.TranscriptWord w
                                            : aActive.transcript.words) {
                                        sb.append("    [").append(w.startMs).append("-")
                                                .append(w.endMs).append("ms] ");
                                        sb.append(w.text);
                                        if (w.struck) sb.append(" [CUT]");
                                        sb.append("\n");
                                    }
                                } else {
                                    sb.append("  (No transcript yet)\n");
                                }
                            }
                        }
                        if (tl.hasTextOverlays()) {
                            sb.append("Overlays: ").append(tl.getTextOverlays().size()).append("\n");
                            for (com.fadcam.ui.faditor.model.TextOverlayItem o
                                    : tl.getTextOverlays()) {
                                sb.append("  Overlay id=").append(o.getId())
                                        .append(" text=\"").append(o.getText(), 0, Math.min(30, o.getText().length())).append("\"")
                                        .append(" start=").append(o.getStartMs())
                                        .append(" end=").append(o.getEndMs() == Long.MAX_VALUE ? "end" : o.getEndMs())
                                        .append(" armed=").append(o.isArmed())
                                        .append("\n");
                            }
                        }
                        sb.append("Canvas: ").append(proj.getCanvasPreset()).append("\n");

                        // Include B-roll bucket contents
                        BRollBucket bucket = new BRollBucket(this);
                        sb.append("\n").append(bucket.getAssetsSummary());
                    }
                } catch (Exception e) {
                    sb.append("(Could not load project context: ").append(e.getMessage()).append(")\n");
                }
            }

            systemMsg.put("content", sb.toString());
            conversationHistory.add(systemMsg);
        } catch (Exception ignored) { }
    }

    private void showWelcome() {
        if (apiKey == null || apiKey.isEmpty()) {
            addBotMessage("Hi! I'm your FadCam assistant. "
                    + "I can help you discover features, answer questions, and suggest edits.\n\n"
                    + "To unlock full AI power, add an API key in Settings (top-right icon). "
                    + "Until then, I can still help with quick answers about the app!");
        } else {
            addBotMessage("Hi! I'm your FadCam assistant, connected to " + model + ". "
                    + "Ask me anything about your project, or tell me what you want to do!");
        }
    }

    private void sendMessage() {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;

        addUserMessage(text);
        inputField.setText("");

        if (apiKey == null || apiKey.isEmpty()) {
            handleOfflineQuery(text);
        } else {
            handleAiQuery(text);
        }
    }

    // ── Offline helper (no API key) ────────────────────────────────

    private void handleOfflineQuery(@NonNull String query) {
        String lower = query.toLowerCase();
        String response;

        if (lower.contains("transcript") || lower.contains("subtitle")) {
            response = "Transcripts: Tap the Transcript tool to transcribe your video. "
                    + "FadCam supports Vosk (fast) and Whisper (accurate) offline. "
                    + "Word-level timing is saved so you can edit by tapping words to cut.";
        } else if (lower.contains("silence") || lower.contains("dead air")) {
            response = "Silence detection: Tap the Silence tool to find quiet spots. "
                    + "They appear as yellow bands on the timeline. "
                    + "Tap one to convert it to a cut — non-destructive and reversible.";
        } else if (lower.contains("overlay") || lower.contains("text") || lower.contains("sticker")) {
            response = "Overlays: Use the Text or Sticker tools to add overlays. "
                    + "Drag to move, pinch to scale, tap to edit. "
                    + "Add keyframes to animate position/scale/rotation over time. "
                    + "Layer bars below the timeline show visible ranges and keyframes.";
        } else if (lower.contains("export") || lower.contains("save") || lower.contains("render")) {
            response = "Export: Tap Export to render your project. "
                    + "Captions, overlays, and keyframes are baked into the output. "
                    + "Enable 'Clean Audio v2' for denoise, dynamic smoothing, and two-pass loudness correction.";
        } else if (lower.contains("cut") || lower.contains("trim") || lower.contains("split")) {
            response = "Cutting: Drag the green handles on a selected clip to trim. "
                    + "Tap Split to cut at the playhead. "
                    + "Deleted spans are non-destructive — ghost footage remains visible.";
        } else if (lower.contains("caption")) {
            response = "Captions: Generate a transcript first, then tap Captions to enable. "
                    + "Choose from Pop, Zoom, Bounce, Boxed, and Hot styles. "
                    + "Drag to reposition. Captions are baked into export.";
        } else if (lower.contains("audio") || lower.contains("sound") || lower.contains("noise")) {
            response = "Audio: You can import external audio, mute clips, adjust volume, "
                    + "and enable Clean Audio v2 on export for denoise, dynamic smoothing, "
                    + "and two-pass loudness correction.";
        } else if (lower.contains("keyframe") || lower.contains("animate")) {
            response = "Keyframes: Open an overlay's editor, then tap 'Add Keyframe' at the playhead. "
                    + "Move the playhead, reposition the overlay, and add another keyframe. "
                    + "The timeline shows keyframe diamonds on layer bars — drag them to adjust timing.";
        } else if (lower.contains("api") || lower.contains("key") || lower.contains("model")) {
            response = "To connect an AI: Tap the Settings icon (top-right) and paste your API key. "
                    + "OpenRouter gives access to many models with free tiers. "
                    + "Once connected, I can suggest edits, answer questions, and (soon) apply edit scripts.";
        } else if (lower.contains("help") || lower.contains("what can you do")) {
            response = "I can help you:\n"
                    + "- Discover features (transcripts, silence, captions, overlays)\n"
                    + "- Explain how tools work\n"
                    + "- Suggest edit workflows\n"
                    + "- Answer questions about your project\n\n"
                    + "Add an API key in Settings for full AI assistance!";
        } else {
            response = "I can help with transcripts, silence detection, captions, overlays, "
                    + "keyframes, audio, trimming, and export. "
                    + "What would you like to know? (Add an API key for full AI assistance.)";
        }
        addBotMessage(response);
    }

    // ── Online AI query ────────────────────────────────────────────

    private void handleAiQuery(@NonNull String userText) {
        try {
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userText);
            conversationHistory.add(userMsg);
        } catch (Exception e) {
            addBotMessage("Error preparing message: " + e.getMessage());
            return;
        }

        addBotMessage("Thinking...");

        aiExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("messages", new JSONArray(conversationHistory));
                body.put("max_tokens", 1024);
                body.put("temperature", 0.7);

                Request request = new Request.Builder()
                        .url(DEFAULT_ENDPOINT)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    runOnUiThread(() -> updateLastBotMessage(
                            "API error " + response.code() + ": "
                                    + responseBody.substring(0, Math.min(200, responseBody.length()))));
                    return;
                }

                JSONObject json = new JSONObject(responseBody);
                // A completion names the model that ACTUALLY served it, which for a router
                // (openrouter/free, openrouter/auto) is only knowable after the fact. Recording
                // it turns the eye indicator's prediction into an observation.
                ModelCapabilities.noteServedModel(json.optString("model", null));
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    runOnUiThread(() -> updateLastBotMessage("Empty response from AI."));
                    return;
                }

                String aiText = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");

                JSONObject aiMsg = new JSONObject();
                aiMsg.put("role", "assistant");
                aiMsg.put("content", aiText);
                conversationHistory.add(aiMsg);

                // Check if the AI response is a tool call
                String toolResult = tryExecuteTool(aiText);
                if (toolResult != null) {
                    // Show the tool call + result, then continue the conversation
                    final String toolMsg = toolResult;
                    runOnUiThread(() -> {
                        updateLastBotMessage(aiText + "\n\n⏳ Executing tool...");
                        addToolResultMessage(toolMsg);
                    });

                    // Feed tool result back to AI for a natural-language response
                    JSONObject toolResponseMsg = new JSONObject();
                    toolResponseMsg.put("role", "user");
                    toolResponseMsg.put("content",
                            "Tool result:\n" + toolResult
                            + "\n\nNow respond to the user with a summary of what happened.");
                    conversationHistory.add(toolResponseMsg);

                    // Make a follow-up AI call
                    continueAiConversation();
                } else {
                    runOnUiThread(() -> updateLastBotMessage(aiText));
                }
            } catch (IOException e) {
                runOnUiThread(() -> updateLastBotMessage("Network error: " + e.getMessage()));
            } catch (Exception e) {
                runOnUiThread(() -> updateLastBotMessage("Error: " + e.getMessage()));
            }
        });
    }

    private void continueAiConversation() {
        aiExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("messages", new JSONArray(conversationHistory));
                body.put("max_tokens", 1024);
                body.put("temperature", 0.7);

                Request request = new Request.Builder()
                        .url(DEFAULT_ENDPOINT)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Response response = httpClient.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    runOnUiThread(() -> addBotMessage("Follow-up API error " + response.code()));
                    return;
                }

                JSONObject json = new JSONObject(responseBody);
                // A completion names the model that ACTUALLY served it, which for a router
                // (openrouter/free, openrouter/auto) is only knowable after the fact. Recording
                // it turns the eye indicator's prediction into an observation.
                ModelCapabilities.noteServedModel(json.optString("model", null));
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    runOnUiThread(() -> addBotMessage("Done."));
                    return;
                }

                String aiText = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");

                JSONObject aiMsg = new JSONObject();
                aiMsg.put("role", "assistant");
                aiMsg.put("content", aiText);
                conversationHistory.add(aiMsg);

                // Check for chained tool calls
                String toolResult = tryExecuteTool(aiText);
                if (toolResult != null) {
                    final String toolMsg = toolResult;
                    runOnUiThread(() -> addToolResultMessage(toolMsg));

                    JSONObject toolResponseMsg = new JSONObject();
                    toolResponseMsg.put("role", "user");
                    toolResponseMsg.put("content",
                            "Tool result:\n" + toolResult
                            + "\n\nNow respond to the user with a summary.");
                    conversationHistory.add(toolResponseMsg);
                    continueAiConversation();
                } else {
                    runOnUiThread(() -> addBotMessage(aiText));
                }
            } catch (Exception e) {
                runOnUiThread(() -> addBotMessage("Follow-up error: " + e.getMessage()));
            }
        });
    }

    @Nullable
    private String tryExecuteTool(@NonNull String aiText) {
        String trimmed = aiText.trim();

        // Remove markdown code fences if present
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start + 1, end).trim();
            }
        }

        try {
            JSONObject json = new JSONObject(trimmed);
            if (json.has("tool")) {
                String toolName = json.getString("tool");
                JSONObject args = json.optJSONObject("args");
                if (args == null) args = new JSONObject();

                // Show status bar with tool name
                showStatus(toolName);
                startAiJob(humanizeToolName(toolName));
                updateAiJob(humanizeToolName(toolName), 0);

                // Show checklist for multi-step tools
                if ("ai_enhance".equals(toolName)) {
                    showChecklist(new String[]{
                            "Generate Vosk transcript", "Generate Whisper transcript",
                            "Detect silence", "Synthesize transcripts", "Cut fillers"});

                    // Run in background so the UI updates
                    final String fnToolName = toolName;
                    final JSONObject fnArgs = args;
                    aiExecutor.execute(() -> {
                        AIToolExecutor executor = createExecutor();
                        String result = executor.executeTool(fnToolName, fnArgs);
                        runOnUiThread(() -> {
                            for (int i = 0; i < 5; i++) checkChecklistItem(i, true);
                            hideStatus();
                        });
                        finishAiJob();
                        final String toolMsg = fnToolName + " → " + result;
                        runOnUiThread(() -> addBotMessage("🔧 " + toolMsg));

                        // Feed result back to AI
                        try {
                            JSONObject toolResponseMsg = new JSONObject();
                            toolResponseMsg.put("role", "user");
                            toolResponseMsg.put("content",
                                    "Tool result:\n" + result
                                    + "\n\nNow respond to the user with a summary.");
                            conversationHistory.add(toolResponseMsg);
                        } catch (Exception ignored) { }
                        continueAiConversation();
                    });
                    return null; // handled async
                }

                AIToolExecutor executor = createExecutor();
                String result = executor.executeTool(toolName, args);
                finishAiJob();
                hideStatus();
                return toolName + " → " + result;
            }
        } catch (Exception ignored) {
            // Not a tool call — normal text response
        }
        return null;
    }

    // ── Settings dialog ────────────────────────────────────────────

    private void showSettingsDialog() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        String currentKey = prefs.sharedPreferences.getString(PREF_API_KEY, "");
        String currentModel = prefs.sharedPreferences.getString(PREF_MODEL, DEFAULT_MODEL);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView labelKey = new TextView(this);
        labelKey.setText("API Key (OpenRouter or compatible):");
        labelKey.setTextColor(Studio.INK_DIM);
        root.addView(labelKey);

        EditText inputKey = new EditText(this);
        inputKey.setText(currentKey);
        inputKey.setHint("sk-or-v1-...");
        inputKey.setTextColor(Studio.INK);
        inputKey.setSingleLine(false);
        root.addView(inputKey);

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

        TextView hint = new TextView(this);
        hint.setText("Free tiers available on OpenRouter for lower-end models.\n"
                + "Leave empty for offline helper mode.");
        hint.setTextColor(Studio.INK_FAINT);
        hint.setTextSize(12);
        hint.setPadding(0, pad, 0, 0);
        root.addView(hint);

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
        titleIcon.setImageResource(R.drawable.ic_ai_assistant_robot);
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

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setCustomTitle(titleRow)
                .setView(root)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

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
                final String finalMdl = mdl.isEmpty() ? DEFAULT_MODEL : mdl;
                prefs.sharedPreferences.edit()
                        .putString(PREF_API_KEY, key)
                        .putString(PREF_MODEL, finalMdl)
                        .apply();
                apiKey = key;
                model = finalMdl;
                updateModelLabel();
                titleIcon.animate()
                        .scaleX(0f)
                        .scaleY(0f)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> {
                            addBotMessage("Settings updated. Model: " + finalMdl
                                    + (key.isEmpty() ? " (offline mode)" : " (connected)"));
                            dialog.dismiss();
                        })
                        .start();
            });
        });

        dialog.show();
    }

    // ── UI helpers ─────────────────────────────────────────────────

    /** Trim messageLog to MAX_MESSAGE_LOG entries (oldest removed first). */
    private static void trimMessageLog() {
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

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextColor(isUser ? Studio.INK : Studio.INK_DIM);
        tv.setBackgroundResource(isUser ? R.drawable.chat_bubble_user : R.drawable.chat_bubble_bot);
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
        copyBtn.setTextColor(0x66C4C4CE);
        copyBtn.setTextSize(13);
        copyBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        clp.setMargins(0, 0, dp(2), isUser ? dp(9) : dp(2));
        copyBtn.setLayoutParams(clp);
        copyBtn.setTag(text);
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
        addBotMessage("🔧 " + toolMsg);
    }

    private boolean renderProposalCard(@NonNull String s) {
        try {
            String body = s.substring("@@PROPOSAL:".length());
            int tagEnd = body.indexOf("@@");
            if (tagEnd < 0) return false;
            String type = body.substring(0, tagEnd);
            String jsonStr = extractJsonObject(body.substring(tagEnd + 2));
            if (jsonStr == null) return false;
            JSONObject payload = new JSONObject(jsonStr);
            if ("narrative".equals(type)) { buildNarrativeCard(payload); return true; }
            if ("broll".equals(type)) { buildBrollCard(payload); return true; }
            if ("avatar_rig".equals(type)) { buildAvatarRigCard(payload); return true; }
        } catch (Exception ignored) { }
        return false;
    }

    /** First balanced {...} object in a string (tolerates newlines and quoted braces). */
    @Nullable
    private static String extractJsonObject(@NonNull String s) {
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

    private void applyProposal(@NonNull String tool, @NonNull JSONObject args) {
        addBotMessage("⏳ Applying…");
        aiExecutor.execute(() -> {
            AIToolExecutor ex = createExecutor();
            String res = ex.executeTool(tool, args);
            runOnUiThread(() -> addBotMessage("🔧 " + res));
        });
    }

    private LinearLayout newCardContainer() {
        LinearLayout card = new LinearLayout(this);
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
        if (count > 0) {
            View last = messagesContainer.getChildAt(count - 1);
            if (last instanceof FrameLayout) {
                FrameLayout fl = (FrameLayout) last;
                if (fl.getChildCount() > 0 && fl.getChildAt(0) instanceof TextView) {
                    ((TextView) fl.getChildAt(0)).setText(text);
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
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(tickWidth / 2f);
        // Colors now match the bubble they represent: user bubble is green, bot bubble is gray.
        shape.setColor(isUser ? Studio.GO : Studio.INK_OFF);
        tick.setBackground(shape);
        final int idx = messageViews.size() - 1;
        tick.setOnClickListener(v -> scrollToMessage(idx));
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
        boolean hasKey = apiKey != null && !apiKey.isEmpty();
        if (!hasKey) {
            modelLabel.setVisibility(View.GONE);
            return;
        }
        boolean named = model != null && !model.isEmpty() && !DEFAULT_MODEL.equals(model);
        String base = named ? model : "connected";
        ModelCapabilities.Vision v = ModelCapabilities.visionFor(named ? model : DEFAULT_MODEL);
        String marker = v == ModelCapabilities.Vision.YES ? "  👁"
                : (v == ModelCapabilities.Vision.NO ? "  ⃠" : "");
        modelLabel.setText(base + marker);
        modelLabel.setVisibility(View.VISIBLE);
        maybeRefreshCapabilities();
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
