package cc.joycreator.avatar.file;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The characters that ship inside the app (assets/avatar/). Topps is the demo and opens ready to play (owner's ruling). */
public final class CharacterAssets {
    public static final String TOPPS = "avatar/topps.character.json";
    public static final String PIP = "avatar/pip.character.json";

    private CharacterAssets() {}

    public static CharacterDoc load(Context c, String assetPath) throws IOException, org.json.JSONException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        try (InputStream in = c.getAssets().open(assetPath)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return CharacterDoc.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }
}
