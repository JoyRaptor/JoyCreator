package android.content;

/**
 * SPEC-ZB-scoped shadow of the shared harness {@code Context} stub (run-clipwarp.sh ONLY).
 *
 * <p>Identical to {@code tools/jvm-harness/stubs/android/content/Context.java} plus the three
 * members the {@code ProjectStorage} compile closure needs ({@code getCacheDir} via
 * {@code FragmentedMp4Remuxer}, {@code getContentResolver} via {@code AssetResolver},
 * {@code startActivity} via {@code SlideCaptureEngine}). None of them is CALLED by the
 * clip serialise/deserialise paths the test exercises — they exist so javac can resolve
 * signatures. Like every harness stub, inert by design.
 */
public class Context {
    public static final int MODE_PRIVATE = 0;
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    /** FontLibrary is dragged in transitively by TextOverlayItem; it only needs the signature. */
    public java.io.File getFilesDir() { return new java.io.File("."); }
    /** run-anchor.sh could not compile at all without this — see AssetManager for the semantics. */
    public android.content.res.AssetManager getAssets() {
        return new android.content.res.AssetManager();
    }
    public java.io.File getCacheDir() { return new java.io.File("."); }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
    public void startActivity(Intent intent) {}
}
