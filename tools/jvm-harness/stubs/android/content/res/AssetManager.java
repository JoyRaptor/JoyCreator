package android.content.res;

import java.io.IOException;
import java.io.InputStream;

/**
 * Harness stub. {@link #open} always throws, which is the honest answer off-device: there are no
 * APK assets to read. Callers that treat a missing asset as "fall back" (LutManager) therefore
 * exercise their fallback, and any caller that assumed the asset EXISTS fails loudly rather than
 * quietly receiving an empty stream — the same discipline the other stubs follow.
 */
public class AssetManager {
    public InputStream open(String name) throws IOException {
        throw new IOException("harness stub: no assets (" + name + ")");
    }
    public String[] list(String path) throws IOException {
        return new String[0];
    }
}
