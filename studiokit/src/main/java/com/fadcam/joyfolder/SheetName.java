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
 * <p>The sheet may be a PNG or a WebP ({@code Dust.5x4.sprite.webp}, about a fifth of the room; TEXTLAB_ROADMAP T17).
 * Every reader takes both; when a look has both beside it, the newer one is its preview.</p>
 *
 * <p>Pure Java (no Android) so it is tested on a plain JVM.</p>
 */
public final class SheetName {

    /** Frames a second when nothing else says. */
    public static final int FPS = 10;

    private static final Pattern NAME = Pattern.compile("^(.+)\\.(\\d{1,2})x(\\d{1,2})\\.sprite\\.(png|webp)$",
            Pattern.CASE_INSENSITIVE);

    /** The two picture kinds a sheet may be. */
    public static final String PNG = "png", WEBP = "webp";

    public final String base;
    public final int cols, rows;
    /** {@link #PNG} or {@link #WEBP}, lower case. */
    public final String ext;

    private SheetName(String base, int cols, int rows, String ext) {
        this.base = base;
        this.cols = cols;
        this.rows = rows;
        this.ext = ext;
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
        return new SheetName(m.group(1), c, r, m.group(4).toLowerCase(Locale.ROOT));
    }

    /** "Dust", 5, 4 → "Dust.5x4.sprite.png". */
    public static String of(String base, int cols, int rows) {
        return of(base, cols, rows, PNG);
    }

    /** "Dust", 5, 4, "webp" → "Dust.5x4.sprite.webp". */
    public static String of(String base, int cols, int rows, String ext) {
        return String.format(Locale.ROOT, "%s.%dx%d.sprite.%s", base, cols, rows, ext);
    }
}
