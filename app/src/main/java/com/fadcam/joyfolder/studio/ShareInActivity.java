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
        JoyFolderHome.io().execute(() -> {
            int big = 0;
            long bigBytes = 0;
            for (Uri u : shared) {
                long s = sizeOf(u);
                if (s > LARGE_BYTES) {
                    big++;
                    bigBytes += s;
                }
            }
            if (big == 0) {
                save(shared, source, BringInDialog.Choice.COPY);
            } else {
                final int n = big;
                final long bytes = bigBytes;
                runOnUiThread(() -> askAboutLarge(shared, source, n, bytes));
            }
        });
    }

    // ── Large files: the same Copy / Move / Leave rule as Add ────────────

    /** Above this a file from outside is not copied by default (same limit as the editor's Add). */
    static final long LARGE_BYTES = 25L * 1024 * 1024;

    private void askAboutLarge(List<Uri> shared, String source, int n, long bytes) {
        buttons.removeAllViews();
        String size = Formatter.formatShortFileSize(this, bytes);
        show(n == 1 ? "This is a large file (" + size + ")" : n + " of these are large files (" + size + ")",
                "Copy keeps a second copy in your Joy Creator folder, so it's safe if the original goes. "
                        + "Move does that, then removes the original from where it was. "
                        + "Leave keeps it where it is: it is linked, not copied, so it is not protected by your folder.",
                null);
        addButton("Copy into my folder", true, v -> startSaving(shared, source, BringInDialog.Choice.COPY));
        addButton("Move into my folder", false, v -> startSaving(shared, source, BringInDialog.Choice.MOVE));
        addButton("Leave where it is", false, v -> startSaving(shared, source, BringInDialog.Choice.LEAVE));
        addButton("Cancel", false, v -> finish());
    }

    private void startSaving(List<Uri> shared, String source, BringInDialog.Choice large) {
        buttons.removeAllViews();
        show(shared.size() == 1 ? "Saving…" : "Saving " + shared.size() + " files…", "", null);
        JoyFolderHome.io().execute(() -> save(shared, source, large));
    }

    /** Keep a link to a shared file past this screen; false when its app only lent it for now. */
    private boolean keepLink(Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return true;
        } catch (RuntimeException e) {
            return "file".equals(u.getScheme());
        }
    }

    // ── Saving ───────────────────────────────────────────────────────────

    private static final class Saved {
        String name;
        Uri uri;
        String mime;
        boolean already;
        /** Left where it was: the project links to the original. */
        boolean linked;
        /** Asked to leave it, but its app only lent it for now, so it was copied. */
        boolean copiedInstead;
    }

    private void save(List<Uri> shared, String source, BringInDialog.Choice large) {
        List<Saved> saved = new ArrayList<>();
        // Original link to its copy's link: an original goes only once its copy is confirmed whole.
        java.util.Map<String, String> toRemove = new java.util.LinkedHashMap<>();
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
            for (Uri u : shared) {
                long s = sizeOf(u);
                if (large == BringInDialog.Choice.LEAVE && s > LARGE_BYTES) continue;   // linked, not copied
                need += Math.max(0, s);
            }
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
                boolean isLarge = size > LARGE_BYTES;
                if (isLarge && large == BringInDialog.Choice.LEAVE) {
                    if (keepLink(u)) {
                        s.linked = true;
                        s.uri = u;
                        s.name = displayName(u);
                        saved.add(s);
                        continue;
                    }
                    s.copiedInstead = true;   // its app only lent it: a link would die, so copy
                }
                if (size > 0) {
                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        if (in != null) rel = jf.index.find(jf.fs, null, Fingerprint.of(in, size));
                    }
                }
                // "Already in the folder" is judged by size plus three samples. Before a MOVE deletes the
                // original on the strength of it, it has to be true byte for byte (work-loss audit 2026-11-10).
                if (rel != null && isLarge && large == BringInDialog.Choice.MOVE) {
                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        if (in == null || !StudioMirror.sameBytes(jf, in, rel)) rel = null;
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
                    // A source that stops part-way just ends its stream: a short copy, and nothing threw.
                    // A short copy is not a copy (work-loss audit 2026-11-10).
                    com.fadcam.joyfolder.JoyFs.Entry made = jf.fs.stat(rel);
                    if (size > 0 && (made == null || made.size != size)) {
                        jf.fs.delete(rel);
                        jf.index.remove(rel);
                        throw new java.io.IOException("the copy of " + displayName(u) + " came out incomplete");
                    }
                }
                s.name = Placement.nameOf(rel);
                s.uri = saf.documentUri(rel);
                if (s.mime == null && s.uri != null) s.mime = getContentResolver().getType(s.uri);
                saved.add(s);
                if (isLarge && large == BringInDialog.Choice.MOVE && s.uri != null) toRemove.put(u.toString(), s.uri.toString());
            }
            jf.saveIndex();
        } catch (Exception e) {
            problem = e.getMessage();
        }
        // Move: the copy has landed and the index is saved; only now do the originals go.
        List<String> stuck = toRemove.isEmpty() ? new ArrayList<>() : OriginalsRemover.remove(this, toRemove);
        if (!stuck.isEmpty()) {
            problem = (problem == null ? "" : problem + ". ")
                    + "The copy is safe, but these originals couldn't be removed, so delete them yourself: "
                    + android.text.TextUtils.join(", ", stuck);
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
            if (s.linked) {
                sb.append("   Large file, left where it is: linked, not copied, so your folder doesn't protect it.\n");
            } else if (s.copiedInstead) {
                sb.append("   Large file: the app that shared it only lends it, so it was copied instead of linked.\n");
            }
        }
        if (problem != null) sb.append("\nNote: ").append(problem).append('.');
        boolean allLinked = true;
        for (Saved s : saved) allLinked &= s.linked;
        show(allLinked ? (saved.size() == 1 ? "Left where it is" : saved.size() + " files left where they are")
                        : saved.size() == 1 ? "Saved in " + where : saved.size() + " files saved in " + where,
                sb.toString().trim(), null);

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
        if ("file".equals(u.getScheme()) && u.getPath() != null) {
            long len = new java.io.File(u.getPath()).length();
            if (len > 0) return len;
        }
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        // A provider that doesn't report a size: ask the file itself.
        try (android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(u, "r")) {
            if (fd != null && fd.getLength() > 0) return fd.getLength();
        } catch (Exception ignored) {
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
