package com.fadcam.joyfolder.studio;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.joyfolder.Fingerprint;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;
import com.fadcam.ui.faditor.FaditorEditorActivity;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * "Share to Joy Creator": a picture, clip or sound from any app lands in the Joy Creator folder.
 *
 * <p>Where it lands follows the owner's rule: with a Studio project open, beside that project's
 * file; with nothing open, in the Assets folder if the user has one, otherwise the top. Code-like
 * names ("1000004521.png") become "From ChatGPT 2026-10-14 00.05.png"; a file already in the
 * folder is not copied again. Then one tap adds it to the open project at the playhead.</p>
 */
public class ShareInActivity extends AppCompatActivity {

    private static final int GROUND = 0xFF050507;
    private static final int PANEL = 0xF01C1C22;
    private static final int PANEL_2 = 0xFF2C2C35;
    private static final int TEXT = 0xFFE9E9EE;
    private static final int MUTED = 0xFFA1A1AC;
    private static final int CAREFUL = 0xFFFBBF24;

    private LinearLayout card;
    private TextView title, body;
    private LinearLayout buttons;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> finish());
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(10), dp(10), dp(10), dp(24));
        scrim.addView(card, lp);
        title = text("", 18, TEXT, true);
        body = text("", 14, MUTED, false);
        body.setPadding(0, dp(6), 0, 0);
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(0, dp(14), 0, 0);
        card.addView(title);
        card.addView(body);
        card.addView(buttons);
        setContentView(scrim);

        List<Uri> shared = sharedUris(getIntent());
        if (shared.isEmpty()) {
            show("Nothing to save", "That share didn't include a file Joy Creator can keep.", null);
            addButton("Close", true, v -> finish());
            return;
        }
        if (!JoyFolderHome.isReachable(this)) {
            boolean chosen = JoyFolderHome.isChosen(this);
            show(chosen ? "Joy Creator folder unavailable" : "Choose where your work lives first",
                    chosen ? "Nothing has been lost. Reconnect your Joy Creator folder, then share again."
                            : "Shared files are kept in your Joy Creator folder, so they're never lost. Choose it once, then share again.",
                    CAREFUL);
            addButton(chosen ? "Reconnect the folder" : "Choose my folder", true, v -> {
                startActivity(JoyFolderSetupActivity.intent(this));
                finish();
            });
            addButton("Not now", false, v -> finish());
            return;
        }
        show(shared.size() == 1 ? "Saving…" : "Saving " + shared.size() + " files…", "", null);
        String source = sourceAppLabel();
        JoyFolderHome.io().execute(() -> save(shared, source));
    }

    // ── Saving ───────────────────────────────────────────────────────────

    private static final class Saved {
        String name;
        Uri uri;
        String mime;
        boolean already;
    }

    private void save(List<Uri> shared, String source) {
        List<Saved> saved = new ArrayList<>();
        String problem = null;
        String projectId = ShareInbox.openProjectId();
        String projectName = ShareInbox.openProjectName();
        String dirShown = "";
        try {
            JoyFolder jf = JoyFolderHome.open(this);
            SafJoyFs saf = JoyFolderHome.fsOf(jf);
            if (jf == null || saf == null) throw new java.io.IOException("the folder can't be reached");
            String workRel = projectId == null ? null : StudioMirror.folderPathOf(this, projectId);
            String dir = jf.prepareDir(Placement.What.SHARE_IN, workRel, null, null);
            dirShown = dir.isEmpty() ? "your Joy Creator folder" : dir;
            long need = 0;
            for (Uri u : shared) need += Math.max(0, sizeOf(u));
            long free = freeBytes();
            if (free >= 0 && need > free) {
                throw new java.io.IOException("it needs " + Formatter.formatShortFileSize(this, need)
                        + " and the phone has " + Formatter.formatShortFileSize(this, free) + " free");
            }
            jf.index.scan(jf.fs);
            long now = System.currentTimeMillis();
            for (Uri u : shared) {
                Saved s = new Saved();
                s.mime = getContentResolver().getType(u);
                long size = sizeOf(u);
                String rel = null;
                if (size > 0) {
                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        if (in != null) rel = jf.index.find(jf.fs, null, Fingerprint.of(in, size));
                    }
                }
                if (rel != null) {
                    s.already = true;
                } else {
                    String ext = s.mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(s.mime);
                    String name = ReadableNames.forShareIn(displayName(u), source, now, TimeZone.getDefault(),
                            ext == null ? null : "." + ext);
                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        if (in == null) throw new java.io.IOException("couldn't read the shared file");
                        rel = jf.addFile(dir, name, in, "share-in");
                    }
                }
                s.name = Placement.nameOf(rel);
                s.uri = saf.documentUri(rel);
                if (s.mime == null && s.uri != null) s.mime = getContentResolver().getType(s.uri);
                saved.add(s);
            }
            jf.saveIndex();
        } catch (Exception e) {
            problem = e.getMessage();
        }
        final String err = problem;
        final String where = dirShown;
        runOnUiThread(() -> showResult(saved, err, where, projectId, projectName));
    }

    private void showResult(List<Saved> saved, String problem, String where, String projectId, String projectName) {
        buttons.removeAllViews();
        if (saved.isEmpty()) {
            show("Couldn't save that", "Nothing was changed: " + (problem == null ? "unknown problem" : problem) + ".", CAREFUL);
            addButton("Close", true, v -> finish());
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Saved s : saved) {
            sb.append(s.already ? "Already in your folder: " : "").append(s.name).append('\n');
        }
        if (problem != null) sb.append("\nSome files couldn't be saved: ").append(problem).append('.');
        show(saved.size() == 1 ? "Saved in " + where : saved.size() + " files saved in " + where, sb.toString().trim(), null);

        ArrayList<Uri> uris = new ArrayList<>();
        Uri firstVideo = null;
        for (Saved s : saved) {
            if (s.uri == null) continue;
            uris.add(s.uri);
            if (firstVideo == null && s.mime != null && s.mime.startsWith("video/")) firstVideo = s.uri;
        }
        if (projectId != null && !uris.isEmpty()) {
            String label = projectName == null || projectName.trim().isEmpty() ? "the open project" : "“" + projectName + "”";
            addButton("Add to " + label, true, v -> {
                ShareInbox.put(projectId, uris);
                Intent i = new Intent(this, FaditorEditorActivity.class)
                        .putExtra(FaditorEditorActivity.EXTRA_PROJECT_ID, projectId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
                finish();
            });
        } else if (firstVideo != null) {
            final Uri video = firstVideo;
            addButton("Start a project with it", true, v -> {
                Intent i = new Intent(this, FaditorEditorActivity.class)
                        .setData(video)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
                finish();
            });
        }
        addButton("Done", projectId == null && firstVideo == null, v -> finish());
    }

    // ── What was shared ──────────────────────────────────────────────────

    private static List<Uri> sharedUris(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null) out.add(u);
        }
        if (out.isEmpty()) {
            ClipData clip = intent.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) out.add(u);
                }
            }
        }
        return out;
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? "" : last;
    }

    private long sizeOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    /** The app that shared, by its name ("ChatGPT"), or null when Android doesn't say. */
    private String sourceAppLabel() {
        try {
            Uri ref = getReferrer();
            if (ref == null || !"android-app".equals(ref.getScheme()) || ref.getHost() == null) return null;
            if (ref.getHost().equals(getPackageName())) return null;
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(ref.getHost(), 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Free space when the folder is on phone storage; -1 when it can't be told (SD card). */
    private long freeBytes() {
        try {
            Uri tree = JoyFolderHome.treeUri(this);
            if (tree == null || !android.provider.DocumentsContract.getTreeDocumentId(tree).startsWith("primary:")) return -1;
            return new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ── Views ────────────────────────────────────────────────────────────

    private void show(String t, String b, Integer titleColor) {
        title.setText(t);
        title.setTextColor(titleColor == null ? TEXT : titleColor);
        body.setText(b);
        body.setVisibility(b == null || b.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void addButton(String label, boolean primary, View.OnClickListener onClick) {
        TextView b = text(label, 15, primary ? GROUND : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(13), dp(18), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? TEXT : PANEL_2);
        bg.setCornerRadius(dp(999));
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        b.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTooltipText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = buttons.getChildCount() == 0 ? 0 : dp(8);
        buttons.addView(b, lp);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
