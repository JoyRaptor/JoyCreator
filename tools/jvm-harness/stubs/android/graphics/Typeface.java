package android.graphics;

/**
 * Harness stub — see {@code org.json.JSONObject} for why the stub tree exists at all.
 *
 * <p>CaptionStyle resolves a font here, which is the last Android reference in the transcript
 * package's compile closure. Nothing off-device draws, so the instances are opaque markers: they
 * are distinguishable from each other (identity) and nothing else, which is all a caller that
 * merely chooses one and hands it on can legitimately need.</p>
 */
public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;

    public static final Typeface DEFAULT = new Typeface();
    public static final Typeface DEFAULT_BOLD = new Typeface();
    public static final Typeface SANS_SERIF = new Typeface();
    public static final Typeface SERIF = new Typeface();
    public static final Typeface MONOSPACE = new Typeface();

    public static Typeface create(Typeface family, int style) { return family; }
    public static Typeface create(String familyName, int style) { return DEFAULT; }
    public static Typeface createFromFile(String path) { return DEFAULT; }
}
