package com.fadcam.ui.library;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.fadcam.FLog;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gathers everything the Library shows, never on the main thread, in two passes:
 *
 * <ol>
 *   <li><b>Inside the app</b> (fast): projects, drawings, sprite sheets, characters, fonts. Painted at
 *       once, so the screen is never an empty wait.</li>
 *   <li><b>The Joy Creator folder and the recordings index</b> (slower: the folder is walked on the
 *       folder's own thread, and a cold recordings index scans the disk). Merged in when it lands.</li>
 * </ol>
 *
 * A newer {@link #load} makes an older one's late results be dropped rather than painted.
 */
final class LibrarySource {

    /** Where the folder stood on the last full read, for the notice under the title. */
    enum FolderState { UNKNOWN, READ, NOT_CHOSEN, UNREACHABLE }

    interface Listener {
        /** On the main thread. {@code complete} is false for the first, app-only pass. */
        void onItems(@NonNull List<LibraryItem> items, boolean complete, @NonNull FolderState folder);
    }

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "library-read");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int generation;

    LibrarySource(@NonNull Context context) {
        this.app = context.getApplicationContext();
    }

    /** Stop delivering: the screen is going away. */
    void cancel() {
        generation++;
    }

    void load(@NonNull Listener listener) {
        final int gen = ++generation;
        WORK.execute(() -> {
            final ProjectStorage storage = new ProjectStorage(app);
            final List<ProjectStorage.ProjectSummary> projects = listProjects(storage);
            final List<LibraryItem> rest = readRestOfApp(storage, projects);
            final Set<String> projectIds = new HashSet<>();
            for (ProjectStorage.ProjectSummary p : projects) projectIds.add(p.id);
            post(gen, listener, withProjects(projects, rest), false, FolderState.UNKNOWN);

            JoyFolderHome.io().execute(() -> {
                FolderReader.Result folder;
                try {
                    folder = FolderReader.read(app, projectIds);
                } catch (Throwable t) {
                    FLog.w("LibrarySource", "folder read failed", t);
                    folder = new FolderReader.Result(FolderReader.State.UNREACHABLE, new ArrayList<>());
                }
                final FolderReader.Result f = folder;
                WORK.execute(() -> {
                    if (gen != generation) return;
                    // The project cards are made again: their layers and running time were read
                    // meanwhile (below), on this same single thread, so this merge sees them.
                    List<LibraryItem> all = withProjects(projects, rest);
                    all.addAll(f.items);
                    all.addAll(CaptureReader.read(app, f.state == FolderReader.State.READ));
                    post(gen, listener, all, true, stateOf(f.state));
                });
            });
            // While the folder is walked on its own thread: each project's layers and running time
            // (slow on first sight, remembered after; see ProjectFacts).
            if (gen == generation) ProjectFacts.read(storage, projects);
        });
    }

    @NonNull
    private static List<ProjectStorage.ProjectSummary> listProjects(@NonNull ProjectStorage storage) {
        try {
            return storage.listProjects();
        } catch (Throwable t) {
            FLog.w("LibrarySource", "projects unreadable", t);
            return new ArrayList<>();
        }
    }

    /** Everything inside the app except the projects themselves: their sheets and characters, drawings, fonts. */
    @NonNull
    private List<LibraryItem> readRestOfApp(@NonNull ProjectStorage storage,
                                            @NonNull List<ProjectStorage.ProjectSummary> projects) {
        List<LibraryItem> out = new ArrayList<>();
        try {
            out.addAll(PartsReader.read(app, storage, projects));
        } catch (Throwable t) {
            FLog.w("LibrarySource", "sheets and characters unreadable", t);
        }
        out.addAll(WorkReader.drawings(app));
        out.addAll(SupplyReader.fonts(app));
        return out;
    }

    /** A fresh list: the project cards (with whatever facts are known now), then the rest. */
    @NonNull
    private List<LibraryItem> withProjects(@NonNull List<ProjectStorage.ProjectSummary> projects,
                                           @NonNull List<LibraryItem> rest) {
        List<LibraryItem> out = new ArrayList<>(WorkReader.projects(app, projects));
        out.addAll(rest);
        return out;
    }

    private void post(int gen, @NonNull Listener l, @NonNull List<LibraryItem> items, boolean complete,
                      @NonNull FolderState state) {
        final List<LibraryItem> frozen = Collections.unmodifiableList(items);
        main.post(() -> {
            if (gen == generation) l.onItems(frozen, complete, state);
        });
    }

    @NonNull
    private static FolderState stateOf(@NonNull FolderReader.State s) {
        switch (s) {
            case READ:       return FolderState.READ;
            case NOT_CHOSEN: return FolderState.NOT_CHOSEN;
            default:         return FolderState.UNREACHABLE;
        }
    }
}
