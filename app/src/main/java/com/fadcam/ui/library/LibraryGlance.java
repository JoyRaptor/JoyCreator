package com.fadcam.ui.library;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * What another screen (the lobby) may know about the Library without being the Library: how many
 * things it holds, and the newest sprite sheet and character. Read by the Library's own
 * {@link LibrarySource} and counted by its own {@link LibraryFilter}, so the lobby's "N ITEMS" is
 * the number the Library's All chip shows and can never disagree with it.
 *
 * <p>Off the main thread, like the Library itself; results arrive on the main thread.</p>
 */
public final class LibraryGlance {

    /** One look. {@code count} is -1 until the full read (folder and recordings) has landed. */
    public static final class Glance {
        public final int count;
        @Nullable public final LibraryItem newestSheet;
        @Nullable public final LibraryItem newestCharacter;

        /**
         * The newest sprite sheets, characters and VisLab looks (at most {@link #RECENT_PARTS}, newest
         * first), for the lobby's Recent work row. The Library's own read, not a second scan.
         */
        @NonNull public final List<LibraryItem> recentParts;

        Glance(int count, @Nullable LibraryItem newestSheet, @Nullable LibraryItem newestCharacter,
               @NonNull List<LibraryItem> recentParts) {
            this.count = count;
            this.newestSheet = newestSheet;
            this.newestCharacter = newestCharacter;
            this.recentParts = recentParts;
        }
    }

    /** How many sheets, characters and looks a glance carries for the lobby. */
    public static final int RECENT_PARTS = 10;

    /** True for a VisLab look of the Library's (a ".vis.json"), the test {@link LibraryOpener} opens it by. */
    public static boolean isVisLabLook(@NonNull LibraryItem it) {
        return it.kind == LibraryKind.SUPPLIES && it.folderPath != null
                && it.folderPath.toLowerCase(java.util.Locale.ROOT).endsWith(".vis.json");
    }

    /** True for a TextLab look of the Library's (a ".text.json"), opened in the TextLab room. */
    public static boolean isTextLabLook(@NonNull LibraryItem it) {
        return it.kind == LibraryKind.SUPPLIES && it.folderPath != null
                && it.folderPath.toLowerCase(java.util.Locale.ROOT).endsWith(".text.json");
    }

    public interface Listener {
        /** On the main thread: once after the fast in-app pass, again after the full read. */
        void onGlance(@NonNull Glance glance);
    }

    private final LibrarySource source;

    public LibraryGlance(@NonNull Context context) {
        source = new LibrarySource(context);
    }

    public void load(@NonNull Listener listener) {
        source.load((items, complete, folder) -> listener.onGlance(new Glance(
                complete ? LibraryFilter.allCount(new LibraryFilter().counts(items)) : -1,
                newest(items, LibraryKind.SPRITE_SHEETS),
                newest(items, LibraryKind.AVATARS),
                recentParts(items))));
    }

    @NonNull
    private static List<LibraryItem> recentParts(@NonNull List<LibraryItem> items) {
        List<LibraryItem> parts = new java.util.ArrayList<>();
        for (LibraryItem it : items) {
            if (it.kind == LibraryKind.SPRITE_SHEETS || it.kind == LibraryKind.AVATARS || isVisLabLook(it) || isTextLabLook(it)) {
                parts.add(it);
            }
        }
        java.util.Collections.sort(parts, LibraryFilter.comparator(LibraryFilter.Sort.NEWEST));
        return parts.size() > RECENT_PARTS ? new java.util.ArrayList<>(parts.subList(0, RECENT_PARTS)) : parts;
    }

    /** The whole Library, not just its count: the Joybot list action needs the items themselves. */
    public interface AllListener {
        /** On the main thread: once after the fast in-app pass, again (complete) after the full read. */
        void onAll(@NonNull List<LibraryItem> items, boolean complete);
    }

    /** Same read as {@link #load}, handing over the items; see {@link AllListener}. */
    public void loadAll(@NonNull AllListener listener) {
        source.load((items, complete, folder) -> listener.onAll(items, complete));
    }

    /** Stop delivering: the screen is going away. */
    public void cancel() {
        source.cancel();
    }

    /** Open it exactly as tapping its card in the Library would. */
    public static boolean open(@NonNull Activity activity, @NonNull LibraryItem item) {
        return LibraryOpener.open(activity, item);
    }

    @Nullable
    private static LibraryItem newest(@NonNull List<LibraryItem> items, @NonNull LibraryKind kind) {
        java.util.Comparator<LibraryItem> newestFirst = LibraryFilter.comparator(LibraryFilter.Sort.NEWEST);
        LibraryItem best = null;
        for (LibraryItem it : items) {
            if (it.kind != kind) continue;
            if (best == null || newestFirst.compare(it, best) < 0) best = it;
        }
        return best;
    }
}
