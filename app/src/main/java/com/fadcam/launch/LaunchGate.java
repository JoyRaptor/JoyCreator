package com.fadcam.launch;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.FLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The launch switch (L37 / SHELL-40): hides features that are not proven yet, so testers meet only solid ones.
 *
 * <p>One list file decides it: {@code app/src/main/assets/launch/hidden_features.json}. It has a master switch,
 * {@code "hide_unproven"}, and a list of short feature keys under {@code "hidden"}. A hidden feature loses every door
 * a person could open it by (lobby room, chip or word, Settings row and its search words, Joybot's ideas and help
 * topics for it); its code stays exactly as it is, so taking a key out of the list brings it straight back.
 * Nothing is removed. Turning {@code hide_unproven} off shows everything, whatever the list says.</p>
 *
 * <p>Keys in use (the table of what each one hides, and the owner's proposed list, is in
 * {@code tasks/audits/launch_hide_list_20261115.md}):</p>
 * <ul>
 *   <li>Lobby rooms: {@code room_studio, room_capture, room_sprite_lab, room_avatar, room_viz_lab, room_textlab,
 *       room_joybrush}</li>
 *   <li>Lobby New chips: {@code new_recording, new_project, new_character, new_import}</li>
 *   <li>Lobby floor words: {@code floor_detect, floor_remote, floor_bits, floor_packs, floor_setup}</li>
 *   <li>Settings hub rows: {@code setting_} plus the row's key in lower case, e.g. {@code setting_motion_lab},
 *       {@code setting_smart_detection}, {@code setting_remote}</li>
 *   <li>Classic Settings "coming soon" gadgets: {@code gadget_} plus its id (compass, sound_meter, sensor_dashboard,
 *       speedometer, clinometer, pedometer, metal_detector, parking_marker, qr_generator), or {@code gadget_stubs}
 *       for all nine</li>
 * </ul>
 *
 * <p>Read once per process from the APK (tiny file); any thread.</p>
 */
public final class LaunchGate {

    private static final String FILE = "launch/hidden_features.json";
    private static volatile Set<String> hidden;

    /**
     * Hidden in the Play build whatever the list file or its master switch says, because Google Play would
     * object, not because they are unproven (launch bug hunt 11-15): the "look for a new version" switch,
     * since a Play install updates through Play and the GitHub download is a different app (B4). Remote
     * Studio was here too until its pairing-code lock landed (B3, lifted 11-15).
     */
    static final String[] PLAY_HIDDEN = {"setting_update_check"};

    private LaunchGate() {}

    /** True when {@code key} is on the list and the master switch is on. */
    public static boolean hidden(@NonNull Context c, @NonNull String key) {
        return load(c).contains(key);
    }

    /** Every hidden key (empty when the master switch is off). */
    @NonNull
    public static Set<String> hiddenKeys(@NonNull Context c) {
        return load(c);
    }

    /** The nine "coming soon" gadgets of the classic Settings and Capture's side menu (SHELL-15). */
    public static final String[] GADGETS = {"compass", "sound_meter", "sensor_dashboard", "speedometer", "clinometer",
            "pedometer", "metal_detector", "parking_marker", "qr_generator"};

    /** True when this gadget's row is on the launch list (by its own key or by {@code gadget_stubs} for all nine). */
    public static boolean gadgetHidden(@NonNull Context c, @NonNull String gadget) {
        return hidden(c, "gadget_" + gadget) || hidden(c, "gadget_stubs");
    }

    /**
     * Hides the rows of listed gadgets under {@code root}, found by their layout ids ({@code group_mini_<id>} in the
     * classic Settings, {@code row_mini_app_<id>} in Capture's side menu). The rows stay in the layout; only their
     * visibility changes.
     */
    public static void hideGadgetRows(@NonNull android.view.View root) {
        Context c = root.getContext();
        if (hiddenKeys(c).isEmpty()) return;
        android.content.res.Resources res = c.getResources();
        for (String g : GADGETS) {
            if (!gadgetHidden(c, g)) continue;
            String group = "sensor_dashboard".equals(g) ? "group_mini_sensor" : "group_mini_" + g;
            for (String name : new String[]{group, "row_mini_app_" + g}) {
                int id = res.getIdentifier(name, "id", c.getPackageName());
                if (id == 0) continue;
                android.view.View v = root.findViewById(id);
                if (v != null) v.setVisibility(android.view.View.GONE);
            }
        }
    }

    @NonNull
    private static Set<String> load(@NonNull Context c) {
        Set<String> h = hidden;
        if (h != null) return h;
        synchronized (LaunchGate.class) {
            if (hidden != null) return hidden;
            Set<String> h2 = read(c.getApplicationContext());
            if (com.fadcam.BuildConfig.PLAY_BUILD) {
                h2 = new HashSet<>(h2);
                Collections.addAll(h2, PLAY_HIDDEN);
                h2 = Collections.unmodifiableSet(h2);
            }
            hidden = h2;
            LaunchList.set(hidden);   // the plain-Java side Joybot's help, tips and ideas read
            return hidden;
        }
    }

    @NonNull
    private static Set<String> read(@NonNull Context c) {
        try (InputStream in = c.getAssets().open(FILE)) {
            byte[] all = new byte[in.available()];
            int n = 0, r;
            while (n < all.length && (r = in.read(all, n, all.length - n)) > 0) n += r;
            return parse(new String(all, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // No file, or one that cannot be read: hide nothing. A launch switch must never take a door away by mistake.
            FLog.w("LaunchGate", "launch list unreadable, nothing hidden", e);
            return Collections.emptySet();
        }
    }

    /** The list file's keys, or nothing when its master switch is off. Plain Java: tested without Android. */
    @NonNull
    static Set<String> parse(@NonNull String json) throws org.json.JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.optBoolean("hide_unproven", false)) return Collections.emptySet();
        JSONArray list = o.optJSONArray("hidden");
        Set<String> out = new HashSet<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                Object item = list.get(i);
                // An entry is a key, or {"key": ..., "why": ...} so the reason can sit beside it.
                String key = item instanceof JSONObject ? ((JSONObject) item).optString("key", "") : String.valueOf(item);
                key = key.trim();
                if (!key.isEmpty()) out.add(key);
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
