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

        Glance(int count, @Nullable LibraryItem newestSheet, @Nullable LibraryItem newestCharacter) {
            this.count = count;
            this.newestSheet = newestSheet;
            this.newestCharacter = newestCharacter;
        }
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
                newest(items, LibraryKind.AVATARS))));
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
