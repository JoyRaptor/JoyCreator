package android.content;

/** Harness stub — see {@code org.json.JSONObject}. */
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
}
