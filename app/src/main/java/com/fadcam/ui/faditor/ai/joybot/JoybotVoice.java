package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Joybot's ears and voice: hold-to-talk listening in the chat, and (if switched on) reading his
 * replies aloud.
 *
 * <p>Hold-to-talk is the Siri-like path: press and hold the mic, talk, let go — the words appear
 * in the box as they are heard and send on release. It uses the phone's own speech service in
 * the background, so no dialog covers the chat. A plain tap still opens the system dialog (works
 * on phones where background listening is unavailable).</p>
 *
 * <p>Reading aloud is OFF by default (plan Part A, question 1). Joybot speaks only what he says,
 * never tool output, ids or emoji.</p>
 */
public final class JoybotVoice {

    private static final String PREFS = "joybot_voice";
    private static final String KEY_READ_ALOUD = "read_aloud";

    public interface Ears {
        /** Words heard so far (on the main thread). */
        void onPartial(@NonNull String soFar);
        /** Final words, or null when nothing was understood. */
        void onFinal(@Nullable String text);
        /** Listening could not start or failed, in plain words. */
        void onProblem(@NonNull String message);
    }

    private final Context context;
    @Nullable private SpeechRecognizer recognizer;
    @Nullable private TextToSpeech tts;
    private boolean ttsReady;
    @Nullable private Ears ears;
    private boolean listening;
    private String lastPartial = "";

    public JoybotVoice(@NonNull Context context) {
        this.context = context;
    }

    // ── ears ───────────────────────────────────────────────────────────────

    public static boolean canListenInBackground(@NonNull Context c) {
        return SpeechRecognizer.isRecognitionAvailable(c);
    }

    /** Start listening; {@link #stopListening} (finger up) ends it and delivers the words. */
    public void startListening(@NonNull Ears e) {
        startListening(e, 4000L);
    }

    /**
     * Start listening. {@code gapMs} is the pause that ends what was said: dictation waits four
     * seconds (holding the button is the "still talking" signal); hands-free sends after about 1.5.
     */
    public void startListening(@NonNull Ears e, long gapMs) {
        ears = e;
        lastPartial = "";
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        // Holding the button IS the "still talking" signal; don't cut off at the first pause.
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, gapMs);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, gapMs);
        try {
            recognizer.startListening(i);
            listening = true;
        } catch (Exception ex) {
            listening = false;
            e.onProblem("I can't listen right now. Tap the mic instead.");
        }
    }

    public void stopListening() {
        if (recognizer != null && listening) recognizer.stopListening();
    }

    public boolean isListening() { return listening; }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onPartialResults(Bundle b) {
            String s = first(b);
            if (s != null && !s.isEmpty() && ears != null) {
                lastPartial = s;
                ears.onPartial(s);
            }
        }

        @Override public void onResults(Bundle b) {
            listening = false;
            String s = first(b);
            if ((s == null || s.isEmpty()) && !lastPartial.isEmpty()) s = lastPartial;
            if (ears != null) ears.onFinal(s == null || s.trim().isEmpty() ? null : s.trim());
        }

        @Override public void onError(int error) {
            listening = false;
            if (ears == null) return;
            // Let go before the service finished: keep whatever it had heard.
            if (!lastPartial.isEmpty()) { ears.onFinal(lastPartial); return; }
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    ears.onFinal(null); break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    ears.onProblem("I need microphone permission to listen."); break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    ears.onProblem("The phone's speech service needs the internet right now. Type instead, or try again."); break;
                default:
                    ears.onProblem("I couldn't hear that. Try again, or tap the mic.");
            }
        }

        @Nullable
        private String first(Bundle b) {
            if (b == null) return null;
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return r == null || r.isEmpty() ? null : r.get(0);
        }
    }

    // ── voice ──────────────────────────────────────────────────────────────

    public static boolean readAloud(@NonNull Context c) {
        return prefs(c).getBoolean(KEY_READ_ALOUD, false);
    }

    public static void setReadAloud(@NonNull Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_READ_ALOUD, on).apply();
    }

    /** Speak a sentence if reading aloud is on. Emoji and symbols are dropped first. */
    public void speak(@Nullable String text) {
        if (text == null || !readAloud(context)) return;
        say(text, null);
    }

    /**
     * Speak a sentence whatever the read-aloud setting says (hands-free reads the replies to
     * requests made by voice). {@code done} runs on the main thread when the sentence has been
     * said, or straight away when there was nothing to say.
     */
    public void speakNow(@Nullable String text, @NonNull Runnable done) {
        say(text, done);
    }

    private void say(@Nullable String text, @Nullable Runnable done) {
        String clean = text == null || looksLikeData(text) ? "" : speakable(text);
        if (clean.isEmpty()) { finish(done); return; }
        if (tts == null) {
            tts = new TextToSpeech(context.getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady && tts != null) {
                    tts.setLanguage(Locale.getDefault());
                    utter(clean, done);
                } else {
                    finish(done);
                }
            });
            return;
        }
        if (ttsReady) utter(clean, done); else finish(done);
    }

    private void utter(@NonNull String clean, @Nullable Runnable done) {
        if (done == null) {
            tts.setOnUtteranceProgressListener(null);
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "joybot");
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean ran = new AtomicBoolean(false);
        Runnable once = () -> { if (ran.compareAndSet(false, true)) done.run(); };
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) { main.post(once); }
            @Override public void onError(String utteranceId) { main.post(once); }
        });
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "joybot");
    }

    private void finish(@Nullable Runnable done) {
        if (done != null) new Handler(Looper.getMainLooper()).post(done);
    }

    /** Tool output, JSON and proposal markers are never read out. */
    static boolean looksLikeData(@NonNull String s) {
        String t = s.trim();
        return t.startsWith("{") || t.startsWith("[") || t.contains("@@")
                || t.matches("(?s).*\"\\w+\"\\s*:\\s*.*");
    }

    public void hush() {
        if (tts != null) tts.stop();
    }

    /** What is worth saying out loud: letters, numbers and ordinary punctuation. */
    @NonNull
    static String speakable(@NonNull String s) {
        String t = s.replaceAll("[\\p{So}\\p{Cn}\\u2190-\\u21FF\\u2600-\\u27BF\\u2700-\\u27BF]", " ")
                .replaceAll("[*_`#>\\[\\]{}]", " ")
                .replaceAll("\\s+", " ").trim();
        return t.length() > 600 ? t.substring(0, 600) : t;
    }

    public void release() {
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        ears = null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
