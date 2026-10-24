package android.content;

import java.util.HashMap;
import java.util.Map;

/** Runtime shim: an Intent is only ever built and handed to the shim Context, which ignores it. */
public class Intent {
    private String action;
    private android.net.Uri data;
    private int flags;
    private final Map<String, Object> extras = new HashMap<>();

    public Intent() { }
    public Intent(String action) { this.action = action; }
    public Intent(String action, android.net.Uri uri) { this.action = action; this.data = uri; }
    public Intent(Context context, Class<?> cls) { }

    public Intent setAction(String action) { this.action = action; return this; }
    public String getAction() { return action; }
    public Intent setData(android.net.Uri data) { this.data = data; return this; }
    public android.net.Uri getData() { return data; }
    public Intent setFlags(int flags) { this.flags = flags; return this; }
    public Intent addFlags(int flags) { this.flags |= flags; return this; }
    public int getFlags() { return flags; }
    public Intent setPackage(String p) { return this; }
    public Intent setClass(Context c, Class<?> cls) { return this; }

    public Intent putExtra(String name, String value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, int value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, long value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, boolean value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, float value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, double value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, String[] value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, java.io.Serializable value) { extras.put(name, value); return this; }

    public boolean hasExtra(String name) { return extras.containsKey(name); }
    public String getStringExtra(String name) { Object v = extras.get(name); return v instanceof String ? (String) v : null; }
    public int getIntExtra(String name, int def) { Object v = extras.get(name); return v instanceof Integer ? (Integer) v : def; }
    public long getLongExtra(String name, long def) { Object v = extras.get(name); return v instanceof Long ? (Long) v : def; }
    public boolean getBooleanExtra(String name, boolean def) { Object v = extras.get(name); return v instanceof Boolean ? (Boolean) v : def; }
}
