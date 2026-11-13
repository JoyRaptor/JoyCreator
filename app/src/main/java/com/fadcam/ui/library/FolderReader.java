package com.fadcam.ui.library;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.FolderIndex;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.android.SafJoyFs;
import com.fadcam.joyfolder.studio.SuppliesFolderCopy;
import com.fadcam.utils.RecordingStoragePaths;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything in the Joy Creator folder, read through its index rather than by guessing at folder
 * names: every file the index holds becomes a row, sorted into a kind by {@link LibraryClassifier}.
 * This is the storage audit's step B2, done for the new screen only (the classic one is untouched).
 *
 * <p>Two kinds are left to {@link WorkReader}, because the app's working copy is the one that opens:
 * drawings (the lobby already brings folder-only drawings into Joy Brush), and projects the app
 * already has. A project that is only in the folder (after a reinstall) is kept, marked
 * {@link LibraryItem#needsRestore}, and is brought back when opened.</p>
 *
 * <p>Folder I/O: run on {@link JoyFolderHome#io()}, the one thread all folder work shares.</p>
 */
public final class FolderReader {

    private static final String TAG = "LibraryFolder";
    private static final Pattern PROJECT_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    enum State { READ, NOT_CHOSEN, UNREACHABLE }

    static final class Result {
        final State state;
        final List<LibraryItem> items;

        Result(State state, List<LibraryItem> items) {
            this.state = state;
            this.items = items;
        }
    }

    private FolderReader() {}

    @NonNull
    static Result read(@NonNull Context c, @NonNull Set<String> projectIdsInApp) {
        List<LibraryItem> out = new ArrayList<>();
        if (!JoyFolderHome.isChosen(c)) return new Result(State.NOT_CHOSEN, out);
        JoyFolder jf = JoyFolderHome.open(c);
        SafJoyFs saf = JoyFolderHome.fsOf(jf);
        if (jf == null || saf == null) return new Result(State.UNREACHABLE, out);
        try {
            jf.index.scan(jf.fs);   // read-only: the index is a cache, and nothing here saves it
        } catch (Exception e) {
            FLog.w(TAG, "folder scan failed", e);
            return new Result(State.UNREACHABLE, out);
        }
        for (FolderIndex.Item fi : jf.index.items()) {
            try {
                // A character's folder copy and a font the app already lists are the same thing, not a second one.
                FileKind kind = FileKind.of(Placement.nameOf(fi.path));
                if (kind == FileKind.AVATAR) continue;
                if (kind == FileKind.FONT && SuppliesFolderCopy.hasLocalFont(c, Placement.nameOf(fi.path))) continue;
                LibraryItem it = toItem(jf, saf, fi, projectIdsInApp);
                if (it != null) out.add(it);
            } catch (Exception e) {
                FLog.w(TAG, "skipping " + fi.path, e);
            }
        }
        List<String> stale = linkSheets(out);
        // a look with no moving preview, or one older than the look (edited by hand): make it a new one
        if (!stale.isEmpty()) cc.joycreator.textlab.TextLabPreviews.get(c).remake(stale);
        return new Result(State.READ, out);
    }

    /**
     * Moving pictures (owner, 2026-11-12). A {@code <Name>.<C>x<R>.sprite.png} (or {@code .webp}) plays as a loop on its card. A
     * look with a sheet beside it ({@code Dust.text.json} + {@code Dust.5x4.sprite.png}) shows the sheet as its
     * own picture, and the sheet is not listed a second time: it is the look's preview, not a file of its own.
     */
    @NonNull
    static List<String> linkSheets(@NonNull List<LibraryItem> items) {
        List<String> stale = new ArrayList<>();
        java.util.Map<String, LibraryItem> sheets = new java.util.HashMap<>();
        List<LibraryItem> older = new ArrayList<>();
        for (LibraryItem it : items) {
            com.fadcam.joyfolder.SheetName n = com.fadcam.joyfolder.SheetName.parse(it.fileName);
            if (n == null || it.folderPath == null) continue;
            it.thumbSheet = it.fileName;
            String key = dirOf(it.folderPath) + "/" + n.base.toLowerCase(java.util.Locale.ROOT);
            LibraryItem had = sheets.get(key);
            // a look with an old PNG preview and a new WebP one: the newer is its preview, and neither is listed
            if (had != null) older.add(had.modified > it.modified ? it : had);
            if (had == null || it.modified >= had.modified) sheets.put(key, it);
        }
        java.util.Set<LibraryItem> owned = new java.util.HashSet<>();
        for (LibraryItem it : items) {
            if (it.fileName == null || it.folderPath == null
                    || !it.fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".text.json")) continue;
            LibraryItem sheet = sheets.get(dirOf(it.folderPath) + "/" + FileKind.baseOf(it.fileName).toLowerCase(java.util.Locale.ROOT));
            if (sheet == null || sheet.modified + 2000 < it.modified) stale.add(it.folderPath);
            if (sheet == null) continue;
            it.thumb = sheet.uri;
            it.thumbSheet = sheet.fileName;
            owned.add(sheet);
        }
        for (LibraryItem o : older) {   // the other preview of a look that has two: never a card of its own
            String key = dirOf(o.folderPath) + "/" + com.fadcam.joyfolder.SheetName.parse(o.fileName).base.toLowerCase(java.util.Locale.ROOT);
            LibraryItem chosen = sheets.get(key);
            if (chosen != null && owned.contains(chosen)) owned.add(o);
        }
        items.removeAll(owned);
        return stale;
    }

    @NonNull
    private static String dirOf(@NonNull String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    @Nullable
    private static LibraryItem toItem(@NonNull JoyFolder jf, @NonNull SafJoyFs saf,
                                      @NonNull FolderIndex.Item fi, @NonNull Set<String> inApp)
            throws Exception {
        String file = Placement.nameOf(fi.path);
        if (fi.plugin) {
            LibraryItem it = new LibraryItem("folder:" + fi.path, LibraryKind.SUPPLIES, file,
                    fi.modified, -1, LibraryClassifier.isSharedIn(fi.origin));
            it.fileKind = FileKind.PLUGIN;
            it.folderPath = fi.path;
            return it;
        }
        FileKind fk = FileKind.of(file);
        if (fk == FileKind.DRAWING) return null;
        String projectId = null;
        if (fk == FileKind.PROJECT) {
            projectId = readProjectId(jf, fi.path);
            if (projectId == null || inApp.contains(projectId)) return null;
        }
        LibraryKind kind = LibraryClassifier.kindOf(file, fi.origin, captureOf(file));
        LibraryItem it = new LibraryItem("folder:" + fi.path, kind, FileKind.baseOf(file),
                fi.modified, fi.size, LibraryClassifier.isSharedIn(fi.origin));
        it.fileKind = fk;
        it.fileName = file;
        it.folderPath = fi.path;
        Uri doc = saf.documentUri(fi.path);   // the scan cached every id: no further query
        it.uri = doc == null ? null : doc.toString();
        if (fk == FileKind.VIDEO || fk == FileKind.IMAGE) it.thumb = it.uri;
        if (projectId != null) {
            it.projectId = projectId;
            it.needsRestore = true;
        }
        return it;
    }

    /** What the recorder's readable name says made the file ("Screen recording 2026-…"). */
    @NonNull
    public static LibraryClassifier.Capture captureOf(@NonNull String fileName) {
        RecordingStoragePaths.JoyKind k = RecordingStoragePaths.JoyKind.ofName(fileName);
        if (k == null) return LibraryClassifier.Capture.NONE;
        switch (k) {
            case PHOTO:
            case SELFIE:
            case SCREENSHOT: return LibraryClassifier.Capture.PHOTO;
            case CONVERTED:
            case MERGED:     return LibraryClassifier.Capture.STUDIO_OUTPUT;
            default:         return LibraryClassifier.Capture.RECORDING;
        }
    }

    /** A project's id, from the first few KB of its file (where the Studio writes it). */
    @Nullable
    private static String readProjectId(@NonNull JoyFolder jf, @NonNull String rel) {
        try (InputStream in = jf.fs.open(rel)) {
            byte[] head = new byte[8192];
            int n = in.read(head);
            if (n <= 0) return null;
            Matcher m = PROJECT_ID.matcher(new String(head, 0, n, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
