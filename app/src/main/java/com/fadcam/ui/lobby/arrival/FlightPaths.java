package com.fadcam.ui.lobby.arrival;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Joybot's flight paths: the six the owner drew (14 Nov) plus the dive and skim (his 7th, 15 Nov),
 * each normalised so it starts at (0, 0) and ends at (1, 1); a flight stretches one from the button to
 * his seat. Packed into {@code assets/joybot/paths.json} by {@code tools/joybot/pack_paths.py}.
 */
final class FlightPaths {

    private FlightPaths() {}

    private static List<float[]> paths;

    /** Every path as {u0, v0, u1, v1, …}; empty when the file is missing (he then hops). */
    @NonNull
    static synchronized List<float[]> get(@NonNull Context c) {
        if (paths != null) return paths;
        List<float[]> out = new ArrayList<>();
        try (InputStream in = c.getApplicationContext().getAssets().open("joybot/paths.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONArray a = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("paths");
            for (int i = 0; i < a.length(); i++) {
                JSONArray uv = a.getJSONObject(i).getJSONArray("uv");
                if (uv.length() < 2) continue;
                float[] p = new float[uv.length() * 2];
                for (int k = 0; k < uv.length(); k++) {
                    JSONArray q = uv.getJSONArray(k);
                    p[k * 2] = (float) q.getDouble(0);
                    p[k * 2 + 1] = (float) q.getDouble(1);
                }
                out.add(p);
            }
        } catch (Exception ignored) {
            // No paths in this build: he takes the short hop.
        }
        paths = Collections.unmodifiableList(out);
        return paths;
    }
}
