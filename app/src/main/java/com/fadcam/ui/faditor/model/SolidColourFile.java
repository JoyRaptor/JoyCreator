package com.fadcam.ui.faditor.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Writes a {@link SolidColour} still into the app's images folder (the same home as the Black clip's still and every
 * imported picture, so a project bundle and the export find it as they find any image).
 */
public final class SolidColourFile {
    private SolidColourFile() {}

    /** The still for this colour and frame shape, written if it is not there yet; null if it could not be written. */
    @Nullable
    public static Uri ensure(@NonNull Context c, int argb, float aspect) {
        int colour = SolidColour.opaque(argb);
        int[] wh = SolidColour.size(aspect);
        try {
            File dir = new File(c.getFilesDir(), "images");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, SolidColour.fileName(colour, wh[0], wh[1]));
            if (!f.exists() || f.length() == 0) {
                Bitmap bmp = Bitmap.createBitmap(wh[0], wh[1], Bitmap.Config.ARGB_8888);
                bmp.eraseColor(colour);
                File tmp = new File(dir, f.getName() + ".part");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                bmp.recycle();
                if (!tmp.renameTo(f)) {
                    tmp.delete();
                    return null;
                }
            }
            return Uri.fromFile(f);
        } catch (Exception e) {
            return null;
        }
    }
}
