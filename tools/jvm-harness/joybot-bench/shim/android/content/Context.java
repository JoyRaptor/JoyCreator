package android.content;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime shim: the app's Context on the PC. Files live under one folder per benchmark run, assets
 * come from the app's own assets folder, settings are kept in memory, and services the agent
 * starts (the foreground notification) are recorded and otherwise ignored.
 */
public class Context {
    public static final int MODE_PRIVATE = 0;

    private final File files;
    private final File assets;
    private final Map<String, SharedPreferences> prefs = new ConcurrentHashMap<>();

    public Context(File files, File assets) {
        this.files = files;
        this.assets = assets;
        files.mkdirs();
    }

    public Context getApplicationContext() { return this; }
    public File getFilesDir() { return files; }
    public File getCacheDir() { File f = new File(files.getParentFile(), "cache"); f.mkdirs(); return f; }
    public File getNoBackupFilesDir() { return getFilesDir(); }

    public File getExternalFilesDir(String type) {
        File f = new File(files.getParentFile(), type == null ? "external" : "external/" + type);
        f.mkdirs();
        return f;
    }

    public File getDir(String name, int mode) { File f = new File(files.getParentFile(), "app_" + name); f.mkdirs(); return f; }
    public String getPackageName() { return "com.fadcam"; }
    public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(assets); }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs.computeIfAbsent(name, n -> new MemPrefs());
    }

    public ComponentName startService(Intent service) { return null; }
    public ComponentName startForegroundService(Intent service) { return null; }
    public boolean stopService(Intent service) { return true; }
    public void sendBroadcast(Intent intent) { }
}
