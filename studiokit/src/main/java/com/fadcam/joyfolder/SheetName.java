package com.fadcam.joyfolder;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The moving-picture convention (owner, 2026-11-12): a picture named {@code <Name>.<C>x<R>.sprite.png} is a
 * sprite sheet of C frames across and R down, played left to right, top to bottom, as a loop. The grid is in
 * the name so any reader (the Library, a chip, another app) knows how to cut it without opening anything else.
 * A look's preview is its sheet beside it: {@code Dust.text.json} and {@code Dust.5x4.sprite.png}.
 *
 * <p>Pure Java (no Android) so it is tested on a plain JVM.</p>
 */
public final class SheetName {

    /** Frames a second when nothing else says. */
    public static final int FPS = 10;

    private static final Pattern NAME = Pattern.compile("^(.+)\\.(\\d{1,2})x(\\d{1,2})\\.sprite\\.(png|webp)$",
            Pattern.CASE_INSENSITIVE);

    public final String base;
    public final int cols, rows;

    private SheetName(String base, int cols, int rows) {
        this.base = base;
        this.cols = cols;
        this.rows = rows;
    }

    public int frames() {
        return cols * rows;
    }

    /** The grid in a file name, or null when the name is not a sheet's (or the grid is empty). */
    public static SheetName parse(String fileName) {
        if (fileName == null) return null;
        Matcher m = NAME.matcher(fileName);
        if (!m.matches()) return null;
        int c = Integer.parseInt(m.group(2)), r = Integer.parseInt(m.group(3));
        if (c < 1 || r < 1) return null;
        return new SheetName(m.group(1), c, r);
    }

    /** "Dust", 5, 4 → "Dust.5x4.sprite.png". */
    public static String of(String base, int cols, int rows) {
        return String.format(Locale.ROOT, "%s.%dx%d.sprite.png", base, cols, rows);
    }
}
