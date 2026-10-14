package com.fadcam.joyfolder.studio;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.FolderIndex;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.android.JoyFolderHome;

/**
 * "Your Joy Creator folder": choose it, reconnect it, bring projects back from it.
 *
 * <p>One screen, three states: not chosen yet; chosen and working; chosen but unreachable (SD
 * card out, permission revoked). Built in code in the app's own language (old black, zinc greys,
 * flat pills) so it needs no layout or string resources.</p>
 */
public class JoyFolderSetupActivity extends AppCompatActivity {

    private static final int GROUND = 0xFF050507;
    private static final int PANEL = 0xFF111114;
    private static final int PANEL_2 = 0xFF1C1C22;
    private static final int TEXT = 0xFFE9E9EE;
    private static final int MUTED = 0xFFA1A1AC;
    private static final int CAREFUL = 0xFFFBBF24;

    private TextView where, detail, progress;
    private LinearLayout consent, actions;
    private TextView consentText;
    private boolean busy;

    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri tree = result.getData() == null ? null : result.getData().getData();
                if (result.getResultCode() == RESULT_OK && tree != null) onPicked(tree);
            });

    public static Intent intent(Context c) {
        return new Intent(c, JoyFolderSetupActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(GROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(GROUND);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(col);

        TextView close = text("‹ Back", 15, MUTED, false);
        close.setPadding(0, dp(4), 0, dp(12));
        close.setOnClickListener(v -> finish());
        tooltip(close, "Back");
        col.addView(close);

        col.addView(text("Your Joy Creator folder", 26, TEXT, true));
        TextView why = text("Everything you make is kept in a folder you choose, so removing the app never loses "
                + "your work. You can open it in any file manager, back it up, or move it to a new phone.", 15, MUTED, false);
        why.setPadding(0, dp(8), 0, dp(16));
        col.addView(why);

        LinearLayout status = panel();
        where = text("", 16, TEXT, true);
        detail = text("", 14, MUTED, false);
        detail.setPadding(0, dp(4), 0, 0);
        status.addView(where);
        status.addView(detail);
        col.addView(status);

        consent = panel();
        consentText = text("", 14, TEXT, false);
        consent.addView(consentText);
        LinearLayout consentButtons = row();
        consentButtons.addView(pill("Just the projects", false, v -> startSync(false)));
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        consentButtons.addView(gap);
        consentButtons.addView(pill("Copy them too", true, v -> startSync(true)));
        consent.addView(consentButtons);
        consent.setVisibility(View.GONE);
        col.addView(consent);

        progress = text("", 14, MUTED, false);
        progress.setPadding(0, dp(12), 0, 0);
        col.addView(progress);

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(16), 0, 0);
        col.addView(actions);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy) refresh();
    }

    // ── States ───────────────────────────────────────────────────────────

    private void refresh() {
        actions.removeAllViews();
        Uri tree = JoyFolderHome.treeUri(this);
        if (tree == null) {
            where.setText("Not chosen yet");
            detail.setText("Right now your projects only live inside the app. If the app is removed, they go with it.");
            actions.addView(pill("Make my Joy Creator folder", true, v -> pick(
                    "In the next screen, tap the new-folder button, name it Joy Creator, then tap Use this folder.")));
            actions.addView(spacer());
            actions.addView(pill("I already have one", false, v -> pick(
                    "Find your Joy Creator folder, open it, then tap Use this folder.")));
            return;
        }
        where.setText(JoyFolderHome.describe(tree));
        if (!JoyFolderHome.isReachable(this)) {
            where.setTextColor(CAREFUL);
            detail.setText("Joy Creator can't reach this folder right now. Nothing has been lost. If it's on an SD card, "
                    + "put the card back; otherwise reconnect it below.");
            actions.addView(pill("Reconnect the folder", true, v -> pick("Find your Joy Creator folder and tap Use this folder.")));
            return;
        }
        where.setTextColor(TEXT);
        detail.setText("Checking…");
        JoyFolderHome.io().execute(() -> {
            int inFolder = countProjects();
            long atRisk = JoyFolderHome.copyMediaAllowed(this) ? 0 : StudioMirror.bytesAtRisk(this);
            com.fadcam.joyfolder.Suggestions.Suggestion offer;
            try {
                offer = StudioMirror.suggestion(this, dismissedSuggestions());
            } catch (Exception e) {
                offer = null;
            }
            final com.fadcam.joyfolder.Suggestions.Suggestion earned = offer;
            runOnUiThread(() -> {
                detail.setText(inFolder == 1 ? "1 project is kept here." : inFolder + " projects are kept here.");
                if (atRisk > 0 && consent.getVisibility() != View.VISIBLE) showConsent(atRisk, inFolder == 0);
                if (earned != null && !isFinishing()) addSuggestionRow(earned);
            });
        });
        addRecordingsRow();
        actions.addView(pill("Save all projects into the folder now", false,
                v -> startSync(JoyFolderHome.copyMediaAllowed(this))));
        actions.addView(spacer());
        actions.addView(pill("Bring back projects from the folder", false, v -> restoreOnly()));
        actions.addView(spacer());
        actions.addView(pill("Use a different folder", false, v -> pick(
                "Pick the folder to use from now on. Files in the old folder stay where they are.")));
    }

    private int countProjects() {
        JoyFolder jf = JoyFolderHome.open(this);
        if (jf == null) return 0;
        try {
            jf.index.scan(jf.fs);
        } catch (Exception e) {
            return 0;
        }
        int n = 0;
        for (FolderIndex.Item it : jf.index.items()) {
            if (FileKind.of(Placement.nameOf(it.path)) == FileKind.PROJECT) n++;
        }
        return n;
    }

    private void pick(String hint) {
        progress.setText(hint);
        try {
            picker.launch(JoyFolderHome.pickerIntent());
        } catch (android.content.ActivityNotFoundException e) {
            progress.setText("This phone has no folder picker, so a folder can't be chosen here.");
        }
    }

    private void onPicked(Uri tree) {
        if (isStorageRoot(tree)) {
            progress.setText("That's the top of your storage itself. Make a folder there called Joy Creator, then pick that folder.");
            return;
        }
        Uri previous = JoyFolderHome.treeUri(this);
        try {
            JoyFolderHome.choose(this, tree);
        } catch (SecurityException e) {
            progress.setText("Android didn't allow Joy Creator to keep using that folder. Try another one.");
            return;
        }
        carryRecorderAlong(previous);
        busy = true;
        progress.setText("Setting up your folder…");
        JoyFolderHome.io().execute(() -> {
            JoyFolder jf = JoyFolderHome.open(this);
            if (jf == null) {
                runOnUiThread(() -> {
                    busy = false;
                    progress.setText("Couldn't write to that folder. Pick one on your phone's storage or SD card.");
                    refresh();
                });
                return;
            }
            long atRisk = StudioMirror.bytesAtRisk(this);
            runOnUiThread(() -> {
                if (atRisk > 0 && !JoyFolderHome.copyMediaAllowed(this)) {
                    busy = false;   // the consent buttons must answer
                    refresh();
                    showConsent(atRisk, true);
                    progress.setText("");
                } else {
                    startSync(JoyFolderHome.copyMediaAllowed(this));
                }
            });
        });
    }

    private void showConsent(long bytes, boolean firstTime) {
        String free = freeSpace();
        consentText.setText("Your projects use " + Formatter.formatShortFileSize(this, bytes)
                + " of pictures, clips and sounds that only exist inside the app. Copy them into your folder too, "
                + "so nothing is missing if the app is removed?"
                + (free == null ? "" : " You have " + free + " free.")
                + (firstTime ? "" : "\n\nYour projects are already in the folder; this only adds their media."));
        consent.setVisibility(View.VISIBLE);
    }

    private void startSync(boolean copyMedia) {
        consent.setVisibility(View.GONE);
        JoyFolderHome.setCopyMediaAllowed(this, copyMedia);
        busy = true;
        progress.setText("Copying your projects into the folder…");
        JoyFolderHome.io().execute(() -> {
            int restored = 0;
            String problem = null;
            try {
                restored = StudioMirror.restoreMissing(this, name -> runOnUiThread(() -> progress.setText("Bringing back " + name + "…")));
            } catch (Exception e) {
                problem = e.getMessage();
            }
            StudioMirror.Result r = StudioMirror.mirrorAll(this, name -> runOnUiThread(() -> progress.setText("Saving " + name + "…")));
            final int back = restored;
            final String err = problem;
            runOnUiThread(() -> {
                busy = false;
                StringBuilder sb = new StringBuilder("Done. ");
                sb.append(r.projects == 1 ? "1 project is" : r.projects + " projects are").append(" in your folder.");
                if (back > 0) sb.append(back == 1 ? " 1 project was brought back from it." : " " + back + " projects were brought back from it.");
                if (r.filesCopied > 0) sb.append(" Copied ").append(r.filesCopied).append(r.filesCopied == 1 ? " file (" : " files (")
                        .append(Formatter.formatShortFileSize(this, r.bytesCopied)).append(").");
                if (err != null || !r.problems.isEmpty()) {
                    sb.append("\n\n").append(r.problems.size() + (err == null ? 0 : 1))
                            .append(" item(s) couldn't be copied. They are still safe inside the app; the next save tries again.");
                }
                progress.setText(sb.toString());
                refresh();
            });
        });
    }

    private void restoreOnly() {
        busy = true;
        progress.setText("Looking for projects in your folder…");
        JoyFolderHome.io().execute(() -> {
            int n;
            String msg;
            try {
                n = StudioMirror.restoreMissing(this, name -> runOnUiThread(() -> progress.setText("Bringing back " + name + "…")));
                msg = n == 0 ? "Every project in the folder is already in the app."
                        : n == 1 ? "1 project brought back. It's with your other projects." : n + " projects brought back. They're with your other projects.";
            } catch (Exception e) {
                msg = "Couldn't read the folder: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                busy = false;
                progress.setText(m);
                refresh();
            });
        });
    }

    // ── Recordings and exports ───────────────────────────────────────────
    //
    // The recorder's own "Storage Location" setting already saves recordings into a folder the user
    // picks, and the Library already lists that folder. Choosing the Joy Creator folder points that
    // same setting here, so recordings and exports land in the folder (and survive an uninstall).

    private com.fadcam.SharedPreferencesManager recorderPrefs() {
        return com.fadcam.SharedPreferencesManager.getInstance(this);
    }

    private boolean recordingsHere() {
        com.fadcam.SharedPreferencesManager p = recorderPrefs();
        return com.fadcam.SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(p.getStorageMode())
                && com.fadcam.utils.RecordingStoragePaths.isJoyFolder(this, p.getCustomStorageUri());
    }

    private void setRecordingsHere(boolean here) {
        com.fadcam.SharedPreferencesManager p = recorderPrefs();
        Uri tree = JoyFolderHome.treeUri(this);
        if (here && tree != null) {
            p.setCustomStorageUri(tree.toString());
            p.setStorageMode(com.fadcam.SharedPreferencesManager.STORAGE_MODE_CUSTOM);
        } else {
            p.setCustomStorageUri(null);
            p.setStorageMode(com.fadcam.SharedPreferencesManager.STORAGE_MODE_INTERNAL);
        }
        sendBroadcast(new Intent(com.fadcam.Constants.ACTION_STORAGE_LOCATION_CHANGED));
    }

    /**
     * After a folder is chosen: a recorder still saving inside the app (the default) or into the
     * previous Joy Creator folder follows the new one. A different place the user picked on purpose
     * is left alone; the screen offers to switch.
     */
    private void carryRecorderAlong(Uri previousJoy) {
        com.fadcam.SharedPreferencesManager p = recorderPrefs();
        String custom = p.getCustomStorageUri();
        boolean internal = !com.fadcam.SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(p.getStorageMode())
                || custom == null || custom.isEmpty();
        boolean wasOldJoy = previousJoy != null && previousJoy.toString().equals(custom);
        if (internal || wasOldJoy) setRecordingsHere(true);
    }

    private void addRecordingsRow() {
        LinearLayout box = panel();
        boolean here = recordingsHere();
        com.fadcam.SharedPreferencesManager p = recorderPrefs();
        String elsewhere = com.fadcam.SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(p.getStorageMode())
                && p.getCustomStorageUri() != null && !here
                ? JoyFolderHome.describe(Uri.parse(p.getCustomStorageUri())) : null;
        box.addView(text("Recordings and exports", 15, TEXT, true));
        TextView line = text(here ? "New recordings and exports are saved here."
                : elsewhere != null ? "New recordings are saved in " + elsewhere + "."
                : "New recordings and exports are kept inside the app, and are lost if the app is removed.",
                14, here ? MUTED : CAREFUL, false);
        line.setPadding(0, dp(4), 0, 0);
        box.addView(line);
        LinearLayout buttons = row();
        buttons.addView(pill(here ? "Keep them inside the app instead" : "Save them here", !here, v -> {
            setRecordingsHere(!here);
            progress.setText(!here ? "New recordings and exports will be saved in your Joy Creator folder."
                    : "New recordings and exports will be kept inside the app.");
            refresh();
        }));
        box.addView(buttons);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) box.getLayoutParams();
        lp.topMargin = 0;
        lp.bottomMargin = dp(12);
        actions.addView(box);
    }

    // ── Earned folders ───────────────────────────────────────────────────
    // Folders are earned: once 20 recordings (or exports) pile up at the top, offer their folder
    // once. "Not now" is remembered per kind and never asked again.

    private static final String PREFS = "joy_folder";
    private static final String DISMISSED = "dismissed_suggestions";

    private java.util.Set<com.fadcam.joyfolder.FolderRoles.Role> dismissedSuggestions() {
        java.util.Set<com.fadcam.joyfolder.FolderRoles.Role> out =
                java.util.EnumSet.noneOf(com.fadcam.joyfolder.FolderRoles.Role.class);
        for (String n : getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(DISMISSED, java.util.Collections.emptySet())) {
            try {
                out.add(com.fadcam.joyfolder.FolderRoles.Role.valueOf(n));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    private void dismissSuggestion(com.fadcam.joyfolder.FolderRoles.Role role) {
        java.util.Set<String> names = new java.util.HashSet<>(
                getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(DISMISSED, java.util.Collections.emptySet()));
        names.add(role.name());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(DISMISSED, names).apply();
    }

    private void addSuggestionRow(com.fadcam.joyfolder.Suggestions.Suggestion s) {
        boolean recordings = s.role == com.fadcam.joyfolder.FolderRoles.Role.RECORDINGS;
        String folder = s.role.defaultName;
        String kind = recordings ? "recordings" : "exports";
        if (actions.findViewWithTag("earned") != null) return;   // two refreshes raced
        LinearLayout box = panel();
        box.setTag("earned");
        box.addView(text(s.files.size() + " " + kind + " at the top", 15, TEXT, true));
        TextView line = text("Put them in a " + folder + " folder? New " + kind + " will go there too. "
                + "Every project still finds its files.", 14, MUTED, false);
        line.setPadding(0, dp(4), 0, 0);
        box.addView(line);
        LinearLayout buttons = row();
        buttons.addView(pill("Not now", false, v -> {
            dismissSuggestion(s.role);
            actions.removeView(box);
        }));
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        buttons.addView(gap);
        buttons.addView(pill("Make " + folder + " folder", true, v -> {
            com.fadcam.SharedPreferencesManager p = recorderPrefs();
            if (p.isRecordingInProgress() || p.isScreenRecordingInProgress()) {
                progress.setText("Finish the recording that's running first, then try again.");
                return;
            }
            busy = true;
            progress.setText("Moving " + s.files.size() + " " + kind + " into " + folder + "…");
            JoyFolderHome.io().execute(() -> {
                String msg;
                try {
                    int moved = StudioMirror.fileAway(this, s);
                    msg = "Moved " + moved + " " + kind + " into " + folder + ". New " + kind + " will go there too.";
                } catch (Exception e) {
                    msg = "Couldn't finish moving: " + e.getMessage() + ". Nothing was lost.";
                }
                // The Library and the recorder pick the new folder up.
                sendBroadcast(new Intent(com.fadcam.Constants.ACTION_STORAGE_LOCATION_CHANGED));
                final String done = msg;
                runOnUiThread(() -> {
                    busy = false;
                    progress.setText(done);
                    refresh();
                });
            });
        }));
        box.addView(buttons);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) box.getLayoutParams();
        lp.topMargin = 0;
        lp.bottomMargin = dp(12);
        actions.addView(box, 0);
    }

    private static boolean isStorageRoot(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            return id.endsWith(":");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String freeSpace() {
        Uri tree = JoyFolderHome.treeUri(this);
        if (tree == null) return null;
        try {
            if (!DocumentsContract.getTreeDocumentId(tree).startsWith("primary:")) return null;
            StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
            return Formatter.formatShortFileSize(this, fs.getAvailableBytes());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── Views ────────────────────────────────────────────────────────────

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
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

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.END);
        r.setPadding(0, dp(12), 0, 0);
        return r;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(10)));
        return v;
    }

    /** A flat pill. Primary is light on dark; secondary is a zinc grey. */
    private TextView pill(String label, boolean primary, View.OnClickListener onClick) {
        TextView b = text(label, 15, primary ? GROUND : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? TEXT : PANEL_2);
        bg.setCornerRadius(dp(999));
        b.setBackground(bg);
        b.setOnClickListener(v -> {
            if (!busy) onClick.onClick(v);
        });
        b.setClickable(true);
        b.setFocusable(true);
        tooltip(b, label);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private static void tooltip(View v, String label) {
        v.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.setTooltipText(label);
    }
}
