package org.json;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Runtime shim of Android's org.json.JSONObject, keeping its behaviour where the app leans on it:
 * insertion order, put(name, null) removes, optString of JSON null is "null", numbers and strings
 * coerce both ways, "/" is written as "\/", whole doubles print without ".0".
 */
public class JSONObject {

    public static final Object NULL = new Object() {
        @Override public boolean equals(Object o) { return o == this || o == null; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    };

    private final LinkedHashMap<String, Object> map;

    public JSONObject() { map = new LinkedHashMap<>(); }

    public JSONObject(Map<?, ?> copyFrom) {
        this();
        for (Map.Entry<?, ?> e : copyFrom.entrySet()) {
            String key = (String) e.getKey();
            if (key == null) throw new NullPointerException("key == null");
            map.put(key, wrap(e.getValue()));
        }
    }

    public JSONObject(JSONTokener readFrom) throws JSONException {
        Object object = readFrom.nextValue();
        if (object instanceof JSONObject) map = ((JSONObject) object).map;
        else throw JSON.typeMismatch(object, "JSONObject");
    }

    public JSONObject(String json) throws JSONException { this(new JSONTokener(json)); }

    public JSONObject(JSONObject copyFrom, String[] names) throws JSONException {
        this();
        for (String name : names) {
            Object value = copyFrom.opt(name);
            if (value != null) map.put(name, value);
        }
    }

    public int length() { return map.size(); }

    public JSONObject put(String name, boolean value) throws JSONException { map.put(checkName(name), value); return this; }
    public JSONObject put(String name, double value) throws JSONException { map.put(checkName(name), JSON.checkDouble(value)); return this; }
    public JSONObject put(String name, int value) throws JSONException { map.put(checkName(name), value); return this; }
    public JSONObject put(String name, long value) throws JSONException { map.put(checkName(name), value); return this; }

    public JSONObject put(String name, Object value) throws JSONException {
        if (value == null) { map.remove(name); return this; }
        if (value instanceof Number) JSON.checkDouble(((Number) value).doubleValue());
        map.put(checkName(name), value);
        return this;
    }

    public JSONObject putOpt(String name, Object value) throws JSONException {
        if (name == null || value == null) return this;
        return put(name, value);
    }

    public JSONObject accumulate(String name, Object value) throws JSONException {
        Object current = map.get(checkName(name));
        if (current == null) return put(name, value);
        if (current instanceof JSONArray) { ((JSONArray) current).checkedPut(value); }
        else { JSONArray array = new JSONArray(); array.checkedPut(current); array.checkedPut(value); map.put(name, array); }
        return this;
    }

    String checkName(String name) throws JSONException {
        if (name == null) throw new JSONException("Names must be non-null");
        return name;
    }

    public Object remove(String name) { return map.remove(name); }
    public boolean isNull(String name) { Object value = map.get(name); return value == null || value == NULL; }
    public boolean has(String name) { return map.containsKey(name); }

    public Object get(String name) throws JSONException {
        Object result = map.get(name);
        if (result == null) throw new JSONException("No value for " + name);
        return result;
    }

    public Object opt(String name) { return name == null ? null : map.get(name); }

    public boolean getBoolean(String name) throws JSONException {
        Object object = get(name);
        Boolean result = JSON.toBoolean(object);
        if (result == null) throw JSON.typeMismatch(name, object, "boolean");
        return result;
    }
    public boolean optBoolean(String name) { return optBoolean(name, false); }
    public boolean optBoolean(String name, boolean fallback) { Boolean r = JSON.toBoolean(opt(name)); return r != null ? r : fallback; }

    public double getDouble(String name) throws JSONException {
        Object object = get(name);
        Double result = JSON.toDouble(object);
        if (result == null) throw JSON.typeMismatch(name, object, "double");
        return result;
    }
    public double optDouble(String name) { return optDouble(name, Double.NaN); }
    public double optDouble(String name, double fallback) { Double r = JSON.toDouble(opt(name)); return r != null ? r : fallback; }

    public int getInt(String name) throws JSONException {
        Object object = get(name);
        Integer result = JSON.toInteger(object);
        if (result == null) throw JSON.typeMismatch(name, object, "int");
        return result;
    }
    public int optInt(String name) { return optInt(name, 0); }
    public int optInt(String name, int fallback) { Integer r = JSON.toInteger(opt(name)); return r != null ? r : fallback; }

    public long getLong(String name) throws JSONException {
        Object object = get(name);
        Long result = JSON.toLong(object);
        if (result == null) throw JSON.typeMismatch(name, object, "long");
        return result;
    }
    public long optLong(String name) { return optLong(name, 0L); }
    public long optLong(String name, long fallback) { Long r = JSON.toLong(opt(name)); return r != null ? r : fallback; }

    public String getString(String name) throws JSONException {
        Object object = get(name);
        String result = JSON.toString(object);
        if (result == null) throw JSON.typeMismatch(name, object, "String");
        return result;
    }
    public String optString(String name) { return optString(name, ""); }
    public String optString(String name, String fallback) { String r = JSON.toString(opt(name)); return r != null ? r : fallback; }

    public JSONArray getJSONArray(String name) throws JSONException {
        Object object = get(name);
        if (object instanceof JSONArray) return (JSONArray) object;
        throw JSON.typeMismatch(name, object, "JSONArray");
    }
    public JSONArray optJSONArray(String name) { Object o = opt(name); return o instanceof JSONArray ? (JSONArray) o : null; }

    public JSONObject getJSONObject(String name) throws JSONException {
        Object object = get(name);
        if (object instanceof JSONObject) return (JSONObject) object;
        throw JSON.typeMismatch(name, object, "JSONObject");
    }
    public JSONObject optJSONObject(String name) { Object o = opt(name); return o instanceof JSONObject ? (JSONObject) o : null; }

    public JSONArray toJSONArray(JSONArray names) throws JSONException {
        JSONArray result = new JSONArray();
        if (names == null) return null;
        int length = names.length();
        if (length == 0) return null;
        for (int i = 0; i < length; i++) result.put(opt(JSON.toString(names.opt(i))));
        return result;
    }

    public Iterator<String> keys() { return map.keySet().iterator(); }
    public Set<String> keySet() { return map.keySet(); }
    public JSONArray names() { return map.isEmpty() ? null : new JSONArray(new java.util.ArrayList<>(map.keySet())); }

    @Override
    public String toString() {
        try {
            JSONStringer stringer = new JSONStringer();
            writeTo(stringer);
            return stringer.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    public String toString(int indentSpaces) throws JSONException {
        JSONStringer stringer = new JSONStringer(indentSpaces);
        writeTo(stringer);
        return stringer.toString();
    }

    void writeTo(JSONStringer stringer) throws JSONException {
        stringer.object();
        for (Map.Entry<String, Object> entry : map.entrySet()) stringer.key(entry.getKey()).value(entry.getValue());
        stringer.endObject();
    }

    public static String numberToString(Number number) throws JSONException {
        if (number == null) throw new JSONException("Number must be non-null");
        double doubleValue = number.doubleValue();
        JSON.checkDouble(doubleValue);
        if (number.equals(-0d)) return "-0";
        long longValue = number.longValue();
        if (doubleValue == (double) longValue) return Long.toString(longValue);
        return number.toString();
    }

    public static String quote(String data) {
        if (data == null) return "\"\"";
        try {
            JSONStringer stringer = new JSONStringer();
            stringer.open(JSONStringer.Scope.NULL, "");
            stringer.value(data);
            stringer.close(JSONStringer.Scope.NULL, JSONStringer.Scope.NULL, "");
            return stringer.toString();
        } catch (JSONException e) {
            throw new AssertionError();
        }
    }

    public static Object wrap(Object o) {
        if (o == null) return NULL;
        if (o instanceof JSONArray || o instanceof JSONObject) return o;
        if (o.equals(NULL)) return o;
        try {
            if (o instanceof Collection) return new JSONArray((Collection<?>) o);
            if (o.getClass().isArray()) return new JSONArray(o);
            if (o instanceof Map) return new JSONObject((Map<?, ?>) o);
            if (o instanceof Boolean || o instanceof Byte || o instanceof Character || o instanceof Double
                    || o instanceof Float || o instanceof Integer || o instanceof Long || o instanceof Short
                    || o instanceof String) return o;
            if (o.getClass().getPackage() != null && o.getClass().getPackage().getName().startsWith("java.")) return o.toString();
        } catch (Exception ignored) { }
        return null;
    }
}
