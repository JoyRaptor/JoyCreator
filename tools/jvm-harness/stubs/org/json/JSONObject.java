package org.json;

/**
 * Harness stub for Android's org.json.
 *
 * <p>NOT a JSON implementation and never used as one. It exists so javac can resolve the
 * transcript package off-device: CaptionAnimator reaches CaptionStyle, whose byId() reaches
 * CaptionStyleStore, which persists through org.json and SharedPreferences. Without these the
 * whole file set fails to compile over a dependency no assertion touches.</p>
 *
 * <p><b>Deliberately inert.</b> Every accessor returns its fallback, so a test that started
 * depending on real serialisation would read empty strings and zeroes and FAIL loudly, rather
 * than quietly passing against a half-implementation.</p>
 */
public class JSONObject {
    public JSONObject() { }
    public JSONObject(String source) { }
    public JSONObject put(String name, Object value) throws JSONException { return this; }
    public JSONObject put(String name, int value) throws JSONException { return this; }
    public JSONObject put(String name, long value) throws JSONException { return this; }
    public JSONObject put(String name, double value) throws JSONException { return this; }
    public JSONObject put(String name, boolean value) throws JSONException { return this; }
    public String optString(String name, String fallback) { return fallback; }
    public int optInt(String name, int fallback) { return fallback; }
    public long optLong(String name, long fallback) { return fallback; }
    public double optDouble(String name, double fallback) { return fallback; }
    public boolean optBoolean(String name, boolean fallback) { return fallback; }
    public boolean has(String name) { return false; }
    @Override public String toString() { return "{}"; }
}
