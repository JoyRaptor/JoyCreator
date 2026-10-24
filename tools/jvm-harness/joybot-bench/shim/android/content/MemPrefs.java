package android.content;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory SharedPreferences for the shim {@link Context}. */
final class MemPrefs implements SharedPreferences {

    private final Map<String, Object> map = new ConcurrentHashMap<>();

    @Override public Map<String, ?> getAll() { return new HashMap<>(map); }
    @Override public String getString(String key, String defValue) { Object v = map.get(key); return v instanceof String ? (String) v : defValue; }

    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = map.get(key);
        return v instanceof Set ? new HashSet<>((Set<String>) v) : defValues;
    }

    @Override public int getInt(String key, int defValue) { Object v = map.get(key); return v instanceof Integer ? (Integer) v : defValue; }
    @Override public long getLong(String key, long defValue) { Object v = map.get(key); return v instanceof Long ? (Long) v : defValue; }
    @Override public float getFloat(String key, float defValue) { Object v = map.get(key); return v instanceof Float ? (Float) v : defValue; }
    @Override public boolean getBoolean(String key, boolean defValue) { Object v = map.get(key); return v instanceof Boolean ? (Boolean) v : defValue; }
    @Override public boolean contains(String key) { return map.containsKey(key); }
    @Override public Editor edit() { return new Ed(); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

    private final class Ed implements Editor {
        private final Map<String, Object> put = new HashMap<>();
        private final Set<String> gone = new HashSet<>();
        private boolean clear;

        private Editor set(String k, Object v) {
            if (v == null) gone.add(k); else put.put(k, v);
            return this;
        }

        @Override public Editor putString(String key, String value) { return set(key, value); }
        @Override public Editor putStringSet(String key, Set<String> values) { return set(key, values == null ? null : new HashSet<>(values)); }
        @Override public Editor putInt(String key, int value) { return set(key, value); }
        @Override public Editor putLong(String key, long value) { return set(key, value); }
        @Override public Editor putFloat(String key, float value) { return set(key, value); }
        @Override public Editor putBoolean(String key, boolean value) { return set(key, value); }
        @Override public Editor remove(String key) { gone.add(key); return this; }
        @Override public Editor clear() { clear = true; return this; }

        @Override public boolean commit() {
            if (clear) map.clear();
            for (String k : gone) map.remove(k);
            map.putAll(put);
            return true;
        }

        @Override public void apply() { commit(); }
    }
}
