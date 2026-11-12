package com.fadcam.joyfolder.studio;

import android.content.Context;

import com.fadcam.FLog;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.android.JoyFolderHome;

import java.io.File;

/**
 * The entry points that give characters and fonts their copy in the Joy Creator folder
 * ({@link AvatarFolderCopy}, {@link FontFolderCopy}; work-loss audit F6): after a character is
 * saved, after a font is imported, and {@link #sync} (a back-fill for everything made before the
 * copies existed, and a restore of what the folder holds and the app does not).
 *
 * <p>Nothing here ever throws or blocks the caller: the work runs on {@link JoyFolderHome#io()}.</p>
 */
public final class SuppliesFolderCopy {

    private static final String TAG = "SuppliesCopy";

    private SuppliesFolderCopy() {}

    /** The app's own character library ({@code AvatarLibrary.libraryDir}). */
    public static File avatarLibrary(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "avatar_library");
    }

    /** The app's own imported fonts ({@code FontLibrary}). */
    public static File fontsDir(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "fonts");
    }

    /** A character was saved into the library: its folder copy follows. Any thread. */
    public static void avatarSaved(Context c, File entryDir) {
        Context app = c.getApplicationContext();
        if (!JoyFolderHome.isChosen(app)) return;
        JoyFolderHome.io().execute(() -> {
            try {
                JoyFolder jf = JoyFolderHome.open(app);
                if (jf == null) return;   // the next sync makes it
                jf.index.scan(jf.fs);
                AvatarFolderCopy.copyOne(jf, entryDir, AvatarFolderCopy.copiesByShortId(jf));
            } catch (Throwable t) {
                FLog.w(TAG, "Folder copy of a character not made; the next sync tries again", t);
            }
        });
    }

    /** A font was imported: its folder copy follows. Any thread. */
    public static void fontImported(Context c) {
        Context app = c.getApplicationContext();
        if (!JoyFolderHome.isChosen(app)) return;
        JoyFolderHome.io().execute(() -> {
            try {
                JoyFolder jf = JoyFolderHome.open(app);
                if (jf == null) return;
                FontFolderCopy.copyAll(jf, fontsDir(app));
            } catch (Throwable t) {
                FLog.w(TAG, "Folder copy of a font not made; the next sync tries again", t);
            }
        });
    }

    /**
     * Folder thread. Bring back what the folder has and the app does not, then give a copy to what
     * the app has and the folder does not. Never throws.
     *
     * @return how many characters and fonts came back into the app
     */
    public static int sync(Context c) {
        Context app = c.getApplicationContext();
        if (!JoyFolderHome.isChosen(app)) return 0;
        int back = 0;
        try {
            JoyFolder jf = JoyFolderHome.open(app);
            if (jf == null) return 0;
            jf.index.scan(jf.fs);
            back += AvatarFolderCopy.restore(jf, avatarLibrary(app));
            AvatarFolderCopy.backfill(jf, avatarLibrary(app));
            int fonts = FontFolderCopy.restore(jf, fontsDir(app));
            if (fonts > 0) {
                com.fadcam.ui.faditor.text.FontLibrary.invalidateTypefaces();
                back += fonts;
            }
            FontFolderCopy.copyAll(jf, fontsDir(app));
        } catch (Throwable t) {
            FLog.w(TAG, "Characters and fonts not synced with the folder this time", t);
        }
        return back;
    }

    /** True when the app already has a font of this name (so the folder's copy is not listed twice). */
    public static boolean hasLocalFont(Context c, String name) {
        return new File(fontsDir(c), name).isFile();
    }
}
