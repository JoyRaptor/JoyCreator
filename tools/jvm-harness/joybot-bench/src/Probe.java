import java.io.File;

/** Smoke check: the real agent's tool list loads on the PC. Prints name + family per tool. */
public final class Probe {
    public static void main(String[] a) throws Exception {
        File root = new File(a.length > 0 ? a[0] : "tools/jvm-harness/out-joybot-runs/probe");
        android.content.Context ctx = new android.content.Context(new File(root, "files"),
                new File(System.getProperty("bench.assets")));
        java.lang.reflect.Method m = Class.forName("com.fadcam.ui.faditor.ai.joybot.JoybotAgent")
                .getDeclaredMethod("nativeTools");
        m.setAccessible(true);
        org.json.JSONArray t = (org.json.JSONArray) m.invoke(null);
        for (int i = 0; i < t.length(); i++) {
            org.json.JSONObject fn = t.getJSONObject(i).getJSONObject("function");
            String d = fn.optString("description").replace('\n', ' ');
            System.out.println(fn.getString("name") + " :: " + (d.length() > 110 ? d.substring(0, 110) : d)
                    + " :: " + fn.optJSONObject("parameters"));
        }
        System.out.println(t.length() + " tools");
        System.exit(0);
    }
}
