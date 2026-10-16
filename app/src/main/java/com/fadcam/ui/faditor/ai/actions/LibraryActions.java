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
