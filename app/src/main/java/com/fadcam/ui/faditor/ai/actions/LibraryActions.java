package com.fadcam.ui.faditor.ai.actions;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.library.LibraryClock;
import com.fadcam.ui.library.LibraryFilter;
import com.fadcam.ui.library.LibraryFormat;
import com.fadcam.ui.library.LibraryGlance;
import com.fadcam.ui.library.LibraryItem;
import com.fadcam.ui.library.LibraryKind;
import com.fadcam.ui.library.LibraryKindStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "What have I got?" as an action (gap F2, app-wide host): the Library's own list, newest first, with
 * the Library's own search and kind filter ({@link LibraryFilter}).
 *
 * <p>The items come from the Library's own reader, through {@link LibraryGlance#loadAll}: the same
 * read the Library screen makes, so this never disagrees with what the screen shows.</p>
 */
final class LibraryActions {
    private LibraryActions() { }

    private static final String FAMILY = "library";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    /** The full read (folder and recordings) can take a while on a big folder; past this, the quick pass is used. */
    private static final long FULL_READ_WAIT_MS = 15000L;

    private static final List<String> KINDS = kindNames();

    static void register(@NonNull ActionRegistry<AppCtx> r) {
        r.add(ActionSpec.of("list_library", "List what is in the Library")
                .help("Lists what is in the Library, newest first: projects, drawings, sprite sheets, avatars,"
                        + " recordings, photos, exports, clips, images, audio, documents, fonts and supplies."
                        + " Use it when they ask what they have, or to find a thing by name before opening it."
                        + " kind shows only one kind; query shows only things whose name (or project's name)"
                        + " has those words. Each line gives the thing's name, kind, length or size and how long"
                        + " ago; the keys at the end say which thing each line is.")
                .wing("APP", FAMILY).effect(Effect.READ)
                .where("Lobby › Library")
                .choice("kind", false, "Only this kind of thing", KINDS)
                .number("limit", false, "How many to list, newest first (default 10)", 1.0, (double) MAX_LIMIT)
                .text("query", false, "Words to look for in the name")
                .build(), LibraryActions::listLibrary);

        r.add(ActionSpec.of("open_library_item", "Open something from the Library")
                .help("Opens a thing from the Library exactly as tapping it there does: a project in the"
                        + " Studio, a drawing in JoyBrush, a sprite sheet in Sprite Lab, a character in Avatar"
                        + " Studio, a video or picture in its viewer. item: its name or its key from"
                        + " list_library. Closes the chat; say what you're opening first.")
                .wing("APP", FAMILY).effect(Effect.NAVIGATE)
                .where("Lobby › Library › tap the thing")
                .text("item", true, "The thing's name, or its key from list_library")
                .build(), (ctx, a) -> openItem(ctx, a, false));

        r.add(ActionSpec.of("new_project_from", "Start a new project from a video")
                .help("Starts a new Studio project from a recording, clip or export in the Library, the way"
                        + " New project does with a picked video. Use for \"make a project from my last"
                        + " recording\", \"edit the screen recording from yesterday\". item: its name or key from"
                        + " list_library (call list_library first if you don't know it). Closes the chat.")
                .wing("APP", FAMILY).effect(Effect.NAVIGATE)
                .where("Lobby › Library › New project › pick the video")
                .text("item", true, "The video's name, or its key from list_library")
                .build(), (ctx, a) -> openItem(ctx, a, true));
    }

    /** The kinds a new project can start from: videos. */
    private static boolean isVideo(@NonNull LibraryItem it) {
        return (it.kind == LibraryKind.RECORDINGS || it.kind == LibraryKind.EXPORTS || it.kind == LibraryKind.CLIPS)
                && !it.isImage() && it.uri != null && !it.uri.isEmpty();
    }

    @NonNull
    private static ActionResult openItem(@NonNull AppCtx ctx, @NonNull Args a, boolean asNewProject) throws ActionError {
        String ref = a.str("item");
        if (ref == null) throw new ActionError("Which thing? Give its name or its key from list_library.");
        List<LibraryItem> all = readLibrary(ctx.app);
        if (asNewProject) {
            List<LibraryItem> videos = new ArrayList<>();
            for (LibraryItem it : all) if (isVideo(it)) videos.add(it);
            all = videos;
            if (all.isEmpty()) throw new ActionError("There are no recordings, clips or exports in the Library to start from.");
        }
        LibraryItem it = find(ctx.app, all, ref.trim());
        ctx.openItem = it;
        ctx.asNewProject = asNewProject;
        String name = it.name.isEmpty() ? "it" : "“" + it.name + "”";
        return ActionResult.unchanged(asNewProject ? "Starting a new project from " + name + "." : "Opening " + name + ".");
    }

    /** By key; then the exact name; then names containing the words. Several → an error naming them. */
    @NonNull
    private static LibraryItem find(@NonNull Context app, @NonNull List<LibraryItem> all, @NonNull String ref) throws ActionError {
        for (LibraryItem it : all) if (ref.equals(it.key)) return it;
        String want = ref.toLowerCase(Locale.ROOT);
        List<LibraryItem> exact = new ArrayList<>(), part = new ArrayList<>();
        for (LibraryItem it : all) {
            String n = it.name.toLowerCase(Locale.ROOT);
            if (n.equals(want)) exact.add(it);
            else if (!want.isEmpty() && n.contains(want)) part.add(it);
        }
        List<LibraryItem> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) {
            throw new ActionError("Nothing in the Library is called \"" + ref + "\". Call list_library to see the names.");
        }
        StringBuilder sb = new StringBuilder(hit.size() + " things match \"" + ref + "\":");
        for (int i = 0; i < Math.min(8, hit.size()); i++) {
            sb.append("\n").append(line(app, hit.get(i))).append(" = ").append(hit.get(i).key);
        }
        throw new ActionError(sb.append("\nAsk which one, or use its key.").toString());
    }

    // ── handler ──────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult listLibrary(@NonNull AppCtx ctx, @NonNull Args a) throws ActionError {
        LibraryKind kind = null;
        String kindName = a.choice("kind", KINDS);
        if (kindName != null) {
            try {
                kind = LibraryKind.valueOf(kindName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ActionError("list_library needs a kind of thing: " + KINDS + ".");
            }
        }
        Long whole = a.whole("limit");
        int limit = whole == null ? DEFAULT_LIMIT : (int) Math.max(1L, Math.min((long) MAX_LIMIT, whole));
        String query = a.str("query");

        LibraryFilter filter = new LibraryFilter();
        filter.setKind(kind);
        filter.setQuery(query);
        List<LibraryItem> found = filter.apply(readLibrary(ctx.app));

        String scope = (kind == null ? "" : " (" + ctx.app.getString(LibraryKindStyle.label(kind)) + ")")
                + (query == null ? "" : " matching \"" + query + "\"");
        if (found.isEmpty()) {
            return ActionResult.unchanged("Nothing in the Library" + scope + ".");
        }

        int shown = Math.min(limit, found.size());
        String head = shown < found.size()
                ? "The " + shown + " newest of " + found.size() + " things"
                : found.size() + (found.size() == 1 ? " thing" : " things");
        StringBuilder summary = new StringBuilder(head).append(scope).append(", newest first:");
        StringBuilder keys = new StringBuilder("Keys for the next action (name, then key):");
        for (int i = 0; i < shown; i++) {
            LibraryItem it = found.get(i);
            summary.append('\n').append(i + 1).append(". ").append(line(ctx.app, it));
            keys.append('\n').append(it.name.isEmpty() ? "Untitled" : it.name).append(" = ").append(it.key);
        }
        return ActionResult.unchanged(summary.toString()).withDetail(keys.toString());
    }

    /** One readable line: name, kind, length or size, when. */
    @NonNull
    private static String line(@NonNull Context app, @NonNull LibraryItem it) {
        List<String> parts = new ArrayList<>();
        parts.add(it.name.isEmpty() ? "Untitled" : it.name);
        parts.add(app.getString(LibraryKindStyle.label(it.kind)));
        String length = it.durationMs > 0 ? LibraryClock.of(it.durationMs) : "";
        String size = LibraryFormat.size(app, it.size);
        if (!length.isEmpty()) parts.add(length);
        else if (!size.isEmpty()) parts.add(size);
        parts.add(LibraryFormat.ago(app, it.modified));
        if (it.sharedIn) parts.add("shared in");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(sb.length() > 0 ? " · " : "").append(p);
        return sb.toString();
    }

    // ── the Library's own read ───────────────────────────────────────────────

    /**
     * What the Library screen shows, read the way the screen reads it (LibraryGlance.loadAll). Called
     * off the main thread: waits for the full read (folder and recordings), which arrives on the main
     * thread; if it is late, the quick in-app pass is used.
     */
    @NonNull
    private static List<LibraryItem> readLibrary(@NonNull Context app) throws ActionError {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new ActionError("The Library is read off the main thread; ask again from the chat.");
        }
        final Read read = new Read();
        LibraryGlance glance = new LibraryGlance(app);
        glance.loadAll(read::offer);
        try {
            return read.await();
        } finally {
            glance.cancel();
        }
    }

    /** Hands the Library's items from the main thread to the action's thread. */
    private static final class Read {
        private final Object lock = new Object();
        private List<LibraryItem> items;
        private boolean complete;

        /** On the main thread. */
        void offer(@NonNull List<LibraryItem> got, boolean done) {
            synchronized (lock) {
                items = new ArrayList<>(got);
                complete = done;
                lock.notifyAll();
            }
        }

        @NonNull
        List<LibraryItem> await() throws ActionError {
            long end = System.currentTimeMillis() + FULL_READ_WAIT_MS;
            synchronized (lock) {
                while (!complete) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    try {
                        lock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (items == null) throw new ActionError("The Library isn't ready yet. Try again in a moment.");
                return items;
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @NonNull
    private static List<String> kindNames() {
        List<String> out = new ArrayList<>();
        for (LibraryKind k : LibraryKind.values()) out.add(k.name());
        return out;
    }
}
