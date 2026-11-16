package cc.joycreator.textlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.android.JoyFolderHome;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * A look's own picture behind the words (stage 11, LAB-03). The picture is copied into the Joy Creator folder when it
 * is picked (where imports go) and the look keeps its path under {@link #KEY}, so the room, a reopened look, the
 * previews and every export draw the same picture. Before this the picture lived only in the open room and a
 * reopened or exported look showed black.
 */
final class TlPhoto {

    private static final String TAG = "TextLab";
    /** On a look: the picture's path in the Joy Creator folder. */
    static final String KEY = "photo";
    /** Stage 11 is "your picture". */
    static final int STAGE = 11;
    private static final int MAX_SIDE = 2048;

    private TlPhoto() {}

    /** The look's picture path when it shows one, else "". */
    @NonNull
    static String of(@Nullable JSONObject look) {
        return look != null && look.optInt("stage", 1) == STAGE ? look.optString(KEY, "") : "";
    }

    /** Folder thread: copy a picked picture into the folder; its path there, or null when there is no folder. */
    @WorkerThread
    @Nullable
    static String importPicture(@NonNull Context c, @NonNull Uri uri, @NonNull String name) {
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            if (jf == null) return null;
            String dir = jf.prepareDir(Placement.What.IMPORT, null, null, null);
            String rel;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                rel = jf.addFile(dir, name, in, "import");
            }
            jf.saveIndex();
            return rel;
        } catch (Exception e) {
            Log.w(TAG, "picture not copied into the folder", e);
            return null;
        }
    }

    /** Folder thread: the picture at {@code rel}, at most 2048 px a side; null when it is gone or unreadable. */
    @WorkerThread
    @Nullable
    static Bitmap load(@NonNull Context c, @NonNull String rel) {
        if (rel.isEmpty()) return null;
        try {
            JoyFolder jf = JoyFolderHome.open(c);
            if (jf == null || jf.fs.stat(rel) == null) return null;
            return decode(jf.readAll(rel));
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "look's picture not read: " + rel, e);
            return null;
        }
    }

    /**
     * Any thread but the folder's own: the look's picture, read on the folder thread (null when it shows none or
     * the picture cannot be read in time). For a GL thread that is about to draw the look.
     */
    @Nullable
    static Bitmap waitFor(@NonNull Context c, @Nullable JSONObject look) {
        String rel = of(look);
        if (rel.isEmpty()) return null;
        try {
            return JoyFolderHome.io().submit(() -> load(c, rel)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            Log.w(TAG, "look's picture not read in time: " + rel, e);
            return null;
        }
    }

    @Nullable
    static Bitmap decode(@NonNull byte[] bytes) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > MAX_SIDE) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
    }
}
