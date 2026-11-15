package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.android.JoyFolderHome;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Report this reply": the person flags one of Joybot's answers.
 *
 * <p>Google Play's AI-generated content policy expects a way to report an AI reply from inside the app
 * (store checklist, {@code tasks/store/OWNER_CLICKS.md} section 6). This keeps a copy of the reply and
 * the question that led to it as a small, readable JSON file in the person's Joy Creator folder
 * ({@code Joybot/Reports/}), and builds an email to the studio with both filled in. Nothing leaves the
 * phone unless the person presses send in their own email app.</p>
 */
public final class ReplyReport {

    private ReplyReport() {}

    /** Where reports go. A stand-in until the owner confirms the mailbox (OWNER_CLICKS section 2). */
    public static final String CONTACT = "studio@joycreator.cc";
    public static final String DIR = "Joybot/Reports";

    /** Where the report was kept, in words the person can find again. */
    public static final class Saved {
        @NonNull public final String where;
        public final boolean inJoyFolder;
        Saved(@NonNull String where, boolean inJoyFolder) { this.where = where; this.inJoyFolder = inJoyFolder; }
    }

    /**
     * Keep the report. Blocking (writes a file). Returns where it went, or null if it could not be
     * written anywhere (the email still carries everything).
     */
    @WorkerThread
    @Nullable
    public static Saved save(@NonNull Context c, @Nullable String question, @NonNull String reply,
                             @NonNull String helper) {
        Date now = new Date();
        byte[] json;
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "joybot-reply-report");
            o.put("reportedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(now));
            o.put("helper", helper);
            o.put("app", appVersion(c));
            o.put("question", question == null ? "" : question);
            o.put("reply", reply);
            json = (o.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        String name = "Report " + new SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(now) + ".json";
        try {
            if (JoyFolderHome.isReachable(c)) {
                JoyFolder jf = JoyFolderHome.open(c);
                if (jf != null) {
                    jf.writeAtomic(DIR + "/" + name, json);
                    return new Saved("your Joy Creator folder, " + DIR + "/" + name, true);
                }
            }
        } catch (Exception ignored) {
            // Falls through to the app's own storage.
        }
        try {
            File dir = new File(c.getFilesDir(), "joybot-reports");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { out.write(json); }
            return new Saved("the app's own storage (no Joy Creator folder is set up)", false);
        } catch (Exception e) {
            return null;
        }
    }

    /** An email to the studio with the question and the reply filled in. It sends nothing by itself. */
    @NonNull
    public static Intent email(@Nullable String question, @NonNull String reply, @NonNull String helper,
                               @NonNull Context c) {
        String body = "I'm reporting this reply from Joybot.\n\n"
                + "Why (optional): \n\n"
                + "My question:\n" + (question == null || question.isEmpty() ? "(none)" : question) + "\n\n"
                + "Joybot's reply:\n" + reply + "\n\n"
                + "Helper: " + helper + "\nApp: " + appVersion(c) + "\n";
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        i.putExtra(Intent.EXTRA_EMAIL, new String[]{CONTACT});
        i.putExtra(Intent.EXTRA_SUBJECT, "Joybot reply report");
        i.putExtra(Intent.EXTRA_TEXT, body);
        return i;
    }

    @NonNull
    private static String appVersion(@NonNull Context c) {
        try {
            return "Joy Creator " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "Joy Creator";
        }
    }
}
