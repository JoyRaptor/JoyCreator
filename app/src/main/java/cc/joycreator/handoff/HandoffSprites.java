package cc.joycreator.handoff;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Joybot's pictures for a hand-off, read from his own packed sheets ({@code assets/joybot/}, made by
 * {@code tools/joybot/pack_joybot.py}, the Joybot sprites lane). This only READS them, with their
 * frame lists from {@code acts.json}, so a repack is picked up without a change here.
 *
 * <p>The pulling frames are the owner's nine back-to-camera frames of 16 Nov
 * ({@code assets/handoff/joybot-pull.webp}), lined up by least difference as he does by eye. They sit
 * here until the sprites lane packs them with the rest.</p>
 */
final class HandoffSprites {

    static final class Strip {
        Bitmap picture;
        int cols = 1, rows = 1;
        /** The frames it plays, in order, round and round. */
        int[] seq = {0};
        float fps = 8f;
    }

    final Strip[] strips = new Strip[HandoffMoves.ACTS];
    /** His body inside a usual cell, as shares of the cell. */
    float focusL = .2667f, focusT = .1625f, focusR = .6833f, focusB = .9167f;

    float focusCx() { return (focusL + focusR) * .5f; }
    float focusCy() { return (focusT + focusB) * .5f; }
    /** His resting cell in dp for a body {@code bodyDp} tall, fitted as the chat's Joybot is. */
    float cellFor(float bodyDp) { return Math.min(bodyDp / (focusR - focusL), bodyDp / (focusB - focusT)); }
    /** From his body centre down to his hover peg, as a share of his cell. */
    float pegShare() { return focusB - focusCy(); }

    /** Off the main thread. Null when his pictures cannot be read: then there is no show, and nothing else changes. */
    @Nullable
    static HandoffSprites load(@NonNull Context app) {
        try {
            HandoffSprites s = new HandoffSprites();
            JSONObject root = new JSONObject(new String(read(app, "joybot/acts.json"), StandardCharsets.UTF_8));
            JSONArray focus = root.optJSONArray("focus");
            if (focus != null && focus.length() == 4) {
                s.focusL = (float) focus.getDouble(0); s.focusT = (float) focus.getDouble(1);
                s.focusR = (float) focus.getDouble(2); s.focusB = (float) focus.getDouble(3);
            }
            JSONArray acts = root.getJSONArray("acts");
            String[] names = new String[HandoffMoves.ACTS];
            names[HandoffMoves.ACT_SPIN] = "spin";
            names[HandoffMoves.ACT_OOPS] = "oops";
            names[HandoffMoves.ACT_SLAP] = "discarding-clip";
            names[HandoffMoves.ACT_SALUTE] = "salute";
            names[HandoffMoves.ACT_IDLE] = "idle";
            for (int i = 0; i < acts.length(); i++) {
                JSONObject a = acts.getJSONObject(i);
                String name = a.optString("name");
                for (int k = 0; k < names.length; k++) {
                    if (names[k] == null || !names[k].equals(name)) continue;
                    Strip st = new Strip();
                    st.cols = Math.max(1, a.optInt("cols", 8));
                    st.rows = Math.max(1, a.optInt("rows", 1));
                    st.fps = (float) a.optDouble("fps", 8.0);
                    int[] loop = ints(a.optJSONArray("loop")), in = ints(a.optJSONArray("in"));
                    // the salute plays through once from its start; the rest go round their loop
                    st.seq = k == HandoffMoves.ACT_SALUTE ? join(in, loop) : loop.length > 0 ? loop : new int[]{0};
                    st.picture = decode(app, "joybot/" + a.getString("file"));
                    s.strips[k] = st;
                }
            }
            Strip pull = new Strip();
            pull.cols = 3; pull.rows = 3; pull.fps = 8f;
            pull.seq = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 7, 6, 5, 4, 3, 2, 1};
            pull.picture = decode(app, "handoff/joybot-pull.webp");
            s.strips[HandoffMoves.ACT_PULL] = pull;
            for (Strip st : s.strips) if (st == null || st.picture == null) return null;
            return s;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    void recycle() {
        for (Strip st : strips) if (st != null && st.picture != null && !st.picture.isRecycled()) st.picture.recycle();
    }

    private static int[] ints(@Nullable JSONArray a) throws Exception {
        if (a == null) return new int[0];
        int[] out = new int[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.getInt(i);
        return out;
    }

    private static int[] join(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out.length > 0 ? out : new int[]{0};
    }

    @Nullable
    private static Bitmap decode(Context app, String path) throws Exception {
        byte[] bytes = read(app, path);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private static byte[] read(Context app, String path) throws Exception {
        try (InputStream in = app.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
