package com.fadcam.joyfolder.studio;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.FolderIndex;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Space": where the room on the phone goes, and a deliberate way to get some back.
 *
 * <p>The owner's "free up space" (INBOX 2026-09-09): show sizes and let people reclaim space on purpose, rather
 * than asking at import time. Three parts: the Joy Creator folder by kind (and its Trash, emptied in the existing
 * Recently deleted screen); what the app keeps inside itself, with its rebuildable caches clearable; and the
 * biggest files in the folder, each saying whether any project uses it, so an unused one can go to Trash.</p>
 *
 * <p>Clearing caches refuses while an export runs or a project is open: those are the moments a cache is in use.</p>
 */
public class SpaceActivity extends AppCompatActivity {

    private static final int GROUND = 0xFF050507;
    private static final int PANEL = 0xFF111114;
    private static final int PANEL_2 = 0xFF1C1C22;
    private static final int TEXT = 0xFFE9E9EE;
    private static final int MUTED = 0xFFA1A1AC;
    private static final int CAREFUL = 0xFFFBBF24;

    /** Rebuilt when needed: waveforms, scrub audio, thumbnails, video copies for smooth playback, export parts. */
    private static final String[] CACHE_DIRS_IN_FILES = {
            "faditor/remuxed", "faditor/chunks", "export_silence", "waveform_legacy", "waveform_bands",
            "pcmscrub", "vidthumb",
    };

    private LinearLayout col;
    private boolean busy;

    public static Intent intent(Context c) {
        return new Intent(c, SpaceActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(GROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(GROUND);
        scroll.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ── Reading ──────────────────────────────────────────────────────────

    private static final class Report {
        boolean folder;
        String folderWhere;
        final Map<String, Long> byKind = new LinkedHashMap<>();
        long folderTotal, trash;
        final List<FolderIndex.Item> biggest = new ArrayList<>();
        final List<Boolean> biggestUsed = new ArrayList<>();
        long working, caches, otherInside;
    }

    private void load() {
        col.removeAllViews();
        header();
        col.addView(text("Measuring…", 14, MUTED, false));
        final Context app = getApplicationContext();
        JoyFolderHome.io().execute(() -> {
            Report r = measure(app);
            runOnUiThread(() -> {
                if (!isFinishing()) paint(r);
            });
        });
    }

    private static Report measure(Context c) {
        Report r = new Report();
        for (String k : new String[]{"Projects", "Drawings", "Recordings", "Exports", "Pictures, clips and sounds", "Other"}) {
            r.byKind.put(k, 0L);
        }
        JoyFolder jf = JoyFolderHome.open(c);
        SafJoyFs saf = JoyFolderHome.fsOf(jf);
        if (jf != null) {
            r.folder = true;
            r.folderWhere = JoyFolderHome.describe(JoyFolderHome.treeUri(c));
            try {
                jf.index.scan(jf.fs);
                List<FolderIndex.Item> items = new ArrayList<>(jf.index.items());
                for (FolderIndex.Item it : items) {
                    String k = kindOf(it);
                    r.byKind.put(k, r.byKind.get(k) + it.size);
                    r.folderTotal += it.size;
                }
                for (JoyFolder.TrashItem t : jf.trashItems()) r.trash += Math.max(0, jf.trashSize(t.name));
                items.sort((a, b) -> Long.compare(b.size, a.size));
                Set<String> used = StudioMirror.usedByAnyProject(c);
                for (FolderIndex.Item it : items) {
                    if (r.biggest.size() >= 8) break;
                    FileKind kind = FileKind.of(Placement.nameOf(it.path));
                    if (kind == FileKind.PROJECT || kind == FileKind.DRAWING) continue;   // the work itself
                    r.biggest.add(it);
                    boolean u = saf != null && used.contains(StudioMirror.usageKey(saf.documentUri(it.path).toString()));
                    r.biggestUsed.add(u);
                }
            } catch (Exception ignored) {
            }
        }
        for (String d : CACHE_DIRS_IN_FILES) r.caches += sizeOf(new File(c.getFilesDir(), d));
        r.caches += sizeOf(c.getCacheDir());
        if (c.getExternalCacheDir() != null) r.caches += sizeOf(c.getExternalCacheDir());
        r.working = sizeOf(new File(c.getFilesDir(), "faditor/projects"));
        File jb = c.getExternalFilesDir("joybrush");
        if (jb != null) r.working += sizeOf(jb);
        long inside = sizeOf(c.getFilesDir());
        File ext = c.getExternalFilesDir(null);
        if (ext != null) inside += sizeOf(ext);
        long cachesInFiles = 0;
        for (String d : CACHE_DIRS_IN_FILES) cachesInFiles += sizeOf(new File(c.getFilesDir(), d));
        r.otherInside = Math.max(0, inside - cachesInFiles - r.working);
        return r;
    }

    private static String kindOf(FolderIndex.Item it) {
        String name = Placement.nameOf(it.path);
        FileKind k = FileKind.of(name);
        if (k == FileKind.PROJECT) return "Projects";
        if (k == FileKind.DRAWING) return "Drawings";
        if ("export".equals(it.origin)) return "Exports";
        if ("recording".equals(it.origin) || com.fadcam.utils.RecordingStoragePaths.JoyKind.ofName(name) != null) return "Recordings";
        if (k == FileKind.VIDEO || k == FileKind.AUDIO || k == FileKind.IMAGE) return "Pictures, clips and sounds";
        return "Other";
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += sizeOf(k);
        return n;
    }

    // ── Showing ──────────────────────────────────────────────────────────

    private void header() {
        TextView back = text("‹ Back", 15, MUTED, false);
        back.setPadding(0, dp(4), 0, dp(12));
        back.setOnClickListener(v -> finish());
        tip(back, "Back");
        col.addView(back);
        col.addView(text("Space", 26, TEXT, true));
        TextView why = text("Where the room on your phone goes, and a way to get some back on purpose.", 15, MUTED, false);
        why.setPadding(0, dp(8), 0, dp(8));
        col.addView(why);
    }

    private void paint(Report r) {
        col.removeAllViews();
        header();

        // 1. The folder
        LinearLayout folder = panel();
        folder.addView(text(r.folder ? "Your Joy Creator folder · " + size(r.folderTotal) : "Your Joy Creator folder", 15, TEXT, true));
        if (!r.folder) {
            folder.addView(line("Can't be reached right now. Nothing has been lost."));
        } else {
            folder.addView(line(r.folderWhere));
            for (Map.Entry<String, Long> e : r.byKind.entrySet()) {
                if (e.getValue() > 0) folder.addView(row(e.getKey(), size(e.getValue())));
            }
            if (r.trash > 0) {
                folder.addView(row("In Trash", size(r.trash)));
                folder.addView(line("Empty it in Settings → Storage Location → Recently deleted."));
            }
        }
        col.addView(folder);

        // 2. Inside the app
        LinearLayout inside = panel();
        inside.addView(text("Inside the app · " + size(r.working + r.caches + r.otherInside), 15, TEXT, true));
        inside.addView(row("Working copies of projects and drawings", size(r.working)));
        inside.addView(row("Older recordings and app files", size(r.otherInside)));
        inside.addView(row("Rebuildable (previews, waveforms, playback copies)", size(r.caches)));
        inside.addView(line("Clearing the rebuildable part is safe: it's made again when needed, which takes a little longer the first time."));
        if (r.caches > 0) {
            LinearLayout b = buttons();
            b.addView(pill("Clear " + size(r.caches), true, v -> clearCaches()));
            inside.addView(b);
        }
        col.addView(inside);

        // 3. Biggest files
        if (!r.biggest.isEmpty()) {
            LinearLayout big = panel();
            big.addView(text("Biggest files in your folder", 15, TEXT, true));
            big.addView(line("Tap one no project uses to move it to Trash."));
            for (int i = 0; i < r.biggest.size(); i++) {
                FolderIndex.Item it = r.biggest.get(i);
                boolean used = r.biggestUsed.get(i);
                TextView t = text(Placement.nameOf(it.path) + "  ·  " + size(it.size)
                        + (used ? "" : "  ·  no project uses it"), 14, used ? TEXT : CAREFUL, false);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                t.setPadding(0, dp(10), 0, 0);
                if (!used) {
                    t.setOnClickListener(v -> confirmTrash(it));
                    tip(t, "Move " + Placement.nameOf(it.path) + " to Trash");
                }
                big.addView(t);
            }
            // Unused files, for the bulk button below.
            List<FolderIndex.Item> unused = new ArrayList<>();
            for (int i = 0; i < r.biggest.size(); i++) {
                if (!r.biggestUsed.get(i)) unused.add(r.biggest.get(i));
            }
            if (!unused.isEmpty()) {
                LinearLayout unusedRow = buttons();
                unusedRow.addView(pill("Move unused to Trash…", false, v -> confirmTrashUnused(unused)));
                big.addView(unusedRow);
            }
            col.addView(big);
        }
    }

    // ── Doing ────────────────────────────────────────────────────────────

    private void clearCaches() {
        if (busy) return;
        if (com.fadcam.ui.faditor.export.ExportService.isRunning(this)) {
            Toast.makeText(this, "An export is running. Clear this once it's done.", Toast.LENGTH_LONG).show();
            return;
        }
        if (ShareInbox.openProjectId() != null) {
            Toast.makeText(this, "Close the project you have open first, then clear this.", Toast.LENGTH_LONG).show();
            return;
        }
        busy = true;
        final Context app = getApplicationContext();
        JoyFolderHome.io().execute(() -> {
            long freed = 0;
            for (String d : CACHE_DIRS_IN_FILES) freed += emptyDir(new File(app.getFilesDir(), d));
            freed += emptyDir(app.getCacheDir());
            if (app.getExternalCacheDir() != null) freed += emptyDir(app.getExternalCacheDir());
            final long f = freed;
            runOnUiThread(() -> {
                busy = false;
                Toast.makeText(this, "Freed " + size(f) + ".", Toast.LENGTH_LONG).show();
                load();
            });
        });
    }

    /** Delete what's inside a folder (not the folder itself). Returns the bytes removed. */
    private static long emptyDir(File dir) {
        long freed = 0;
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return 0;
        for (File k : kids) {
            if (k.isDirectory()) {
                freed += emptyDir(k);
                k.delete();
            } else {
                long n = k.length();
                if (k.delete()) freed += n;
            }
        }
        return freed;
    }

    private void confirmTrash(FolderIndex.Item it) {
        String name = Placement.nameOf(it.path);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Move “" + name + "” to Trash?")
                .setMessage("No project uses it. You can put it back from Settings → Storage Location → Recently deleted.")
                .setPositiveButton("Move to Trash", (d, w) -> {
                    final Context app = getApplicationContext();
                    JoyFolderHome.io().execute(() -> {
                        String msg;
                        try {
                            java.util.List<String> one = new ArrayList<>();
                            one.add(it.path);
                            msg = StudioMirror.trashFiles(app, one) == 1 ? "Moved to Trash." : "It was already gone.";
                        } catch (Exception e) {
                            msg = "Couldn't move it: " + e.getMessage();
                        }
                        final String m = msg;
                        runOnUiThread(() -> {
                            Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                            load();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Several unused files at once: tick the ones to send to Trash, then move them in one go. */
    private void confirmTrashUnused(List<FolderIndex.Item> unused) {
        String[] labels = new String[unused.size()];
        boolean[] ticked = new boolean[unused.size()];
        for (int i = 0; i < unused.size(); i++) {
            FolderIndex.Item it = unused.get(i);
            labels[i] = Placement.nameOf(it.path) + " · " + size(it.size);
            ticked[i] = true;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Files no project uses")
                .setMultiChoiceItems(labels, ticked, (d, which, isChecked) -> ticked[which] = isChecked)
                .setPositiveButton("Move to Trash", (d, w) -> {
                    List<String> paths = new ArrayList<>();
                    for (int i = 0; i < unused.size(); i++) {
                        if (ticked[i]) paths.add(unused.get(i).path);
                    }
                    if (paths.isEmpty()) {
                        Toast.makeText(this, "No files were ticked.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final Context app = getApplicationContext();
                    JoyFolderHome.io().execute(() -> {
                        String msg;
                        try {
                            int n = StudioMirror.trashFiles(app, paths);
                            msg = (n == 1 ? "1 file" : n + " files")
                                    + " moved to Trash. Put them back from Settings → Storage Location → Recently deleted.";
                        } catch (Exception e) {
                            msg = "Couldn't move them all: " + e.getMessage() + ". Nothing was lost.";
                        }
                        final String m = msg;
                        runOnUiThread(() -> {
                            Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                            load();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Look ─────────────────────────────────────────────────────────────

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    private TextView line(String s) {
        TextView t = text(s, 13, MUTED, false);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private View row(String label, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, 0);
        TextView l = text(label, 14, TEXT, false);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 14, MUTED, false);
        v.setGravity(Gravity.END);
        r.addView(v);
        return r;
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(14));
        p.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        p.setLayoutParams(lp);
        return p;
    }

    private LinearLayout buttons() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.END);
        r.setPadding(0, dp(12), 0, 0);
        return r;
    }

    private TextView pill(String label, boolean primary, View.OnClickListener onClick) {
        TextView b = text(label, 15, primary ? GROUND : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? TEXT : PANEL_2);
        bg.setCornerRadius(dp(999));
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        tip(b, label);
        return b;
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

    private static void tip(View v, String label) {
        v.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.setTooltipText(label);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
