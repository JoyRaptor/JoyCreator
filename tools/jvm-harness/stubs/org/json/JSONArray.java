package org.json;

/** Harness stub — see {@link JSONObject}. */
public class JSONArray {
    public JSONArray() { }
    public JSONArray(String source) throws JSONException { }
    public JSONArray put(Object value) { return this; }
    public int length() { return 0; }
    public JSONObject optJSONObject(int index) { return null; }
    @Override public String toString() { return "[]"; }
}
