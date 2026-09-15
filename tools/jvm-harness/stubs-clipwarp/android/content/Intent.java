package android.content;

/**
 * SPEC-ZB-scoped stub (run-clipwarp.sh ONLY): signature holder for the Intent API the
 * {@code ProjectStorage} compile closure touches ({@code SlideCaptureEngine},
 * {@code SlideRenderActivity}). Inert: extras are accepted and forgotten, getters
 * return defaults. Shadows the SDK jar on this runner's sourcepath only.
 */
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_NO_ANIMATION = 0x00010000;

    public Intent() {}

    public Intent(Context ctx, Class<?> cls) {}

    public Intent addFlags(int flags) { return this; }

    public Intent putExtra(String k, String v) { return this; }
    public Intent putExtra(String k, int v) { return this; }
    public Intent putExtra(String k, long v) { return this; }
    public Intent putExtra(String k, boolean v) { return this; }
    public Intent putExtra(String k, double v) { return this; }

    public String getStringExtra(String k) { return null; }
    public int getIntExtra(String k, int d) { return d; }
    public long getLongExtra(String k, long d) { return d; }
    public boolean getBooleanExtra(String k, boolean d) { return d; }
    public double getDoubleExtra(String k, double d) { return d; }
}
