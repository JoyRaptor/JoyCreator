package android.content;

/** Harness stub — see {@code org.json.JSONObject}. Inert: reads return their fallback. */
public interface SharedPreferences {
    String getString(String key, String defValue);
    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        void apply();
    }
}
