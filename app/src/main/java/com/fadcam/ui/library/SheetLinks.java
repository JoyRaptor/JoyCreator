package com.fadcam.ui.library;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.SheetName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Moving pictures in the Library (owner, 2026-11-12). A {@code <Name>.<C>x<R>.sprite.png} (or {@code .webp},
 * TEXTLAB_ROADMAP T17) plays as a loop on its card. A look with a sheet beside it ({@code Dust.text.json} +
 * {@code Dust.5x4.sprite.webp}) shows the sheet as its own picture, and the sheet is not listed a second time: it is the
 * look's preview, not a file of its own. A look with two (an old PNG and a new WebP) shows the newer, and neither is
 * listed.
 *
 * <p>Plain Java: tested by {@code tools/library-tests}.</p>
 */
public final class SheetLinks {

    private SheetLinks() {}

    /**
     * Link each look to its sheet and take the looks' sheets out of {@code items}.
     *
     * @return the folder paths of looks whose preview is missing or older than the look (to be made again)
     */
    public static List<String> link(List<LibraryItem> items) {
        List<String> stale = new ArrayList<>();
        Map<String, LibraryItem> sheets = new HashMap<>();
        List<LibraryItem> older = new ArrayList<>();
        for (LibraryItem it : items) {
            SheetName n = SheetName.parse(it.fileName);
            if (n == null || it.folderPath == null) continue;
            it.thumbSheet = it.fileName;
            String key = key(it.folderPath, n.base);
            LibraryItem had = sheets.get(key);
            if (had != null) older.add(had.modified > it.modified ? it : had);
            if (had == null || it.modified >= had.modified) sheets.put(key, it);
        }
        Set<LibraryItem> owned = new HashSet<>();
        for (LibraryItem it : items) {
            if (it.fileName == null || it.folderPath == null
                    || !it.fileName.toLowerCase(Locale.ROOT).endsWith(".text.json")) continue;
            LibraryItem sheet = sheets.get(key(it.folderPath, FileKind.baseOf(it.fileName)));
            if (sheet == null || sheet.modified + 2000 < it.modified) stale.add(it.folderPath);
            if (sheet == null) continue;
            it.thumb = sheet.uri;
            it.thumbSheet = sheet.fileName;
            owned.add(sheet);
        }
        for (LibraryItem o : older) {   // the other preview of a look that has two: never a card of its own
            LibraryItem chosen = sheets.get(key(o.folderPath, SheetName.parse(o.fileName).base));
            if (chosen != null && owned.contains(chosen)) owned.add(o);
        }
        items.removeAll(owned);
        return stale;
    }

    private static String key(String folderPath, String base) {
        int i = folderPath.lastIndexOf('/');
        return (i < 0 ? "" : folderPath.substring(0, i)) + "/" + base.toLowerCase(Locale.ROOT);
    }
}
