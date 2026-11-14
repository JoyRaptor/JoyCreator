package com.fadcam.ui.library;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A picture copied in another app, as a third way in beside share and import (SHELL-25; the owner's 09-24 plan:
 * "one Add image entry"). The picture goes where a share into Joy Creator goes ({@code ShareInActivity}): the
 * Assets folder or the top of the Joy Creator folder, with a readable name and no second copy, so it shows in the
 * Library at once and Studio can use it.
 *
 * <p>Only a copied picture FILE is taken (a clip item with a link the phone can read as an image). Copied text is
 * left alone. Android 12 and later show their own "pasted from your clipboard" notice: reading happens only when
 * the person taps Paste.</p>
 */
public final class ClipboardPicture {

    private ClipboardPicture() {}

    /** The first copied picture's link, or null when the clipboard holds no picture. Main thread. */
    @Nullable
    public static Uri find(@NonNull Context c) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData clip;
        try {
            clip = cm.getPrimaryClip();
        } catch (SecurityException e) {
            return null;   // not in front: Android refuses, and there is nothing to paste
        }
        if (clip == null) return null;
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri u = clip.getItemAt(i).getUri();
            if (u == null) continue;
            String type = null;
            try {
                type = c.getContentResolver().getType(u);
            } catch (Exception ignored) {
                // a link we may not read: skip it
            }
            if (type == null && clip.getDescription() != null) {
                for (int m = 0; m < clip.getDescription().getMimeTypeCount(); m++) {
                    String t = clip.getDescription().getMimeType(m);
                    if (t != null && t.startsWith("image/")) { type = t; break; }
                }
            }
            if (type != null && type.startsWith("image/")) return u;
        }
        return null;
    }

    /** File the picture the way a share does. */
    public static void fileIt(@NonNull Activity a, @NonNull Uri picture) {
        Intent in = new Intent(a, com.fadcam.joyfolder.studio.ShareInActivity.class);
        in.setAction(Intent.ACTION_SEND);
        in.setType("image/*");
        in.putExtra(Intent.EXTRA_STREAM, picture);
        in.setClipData(ClipData.newRawUri("picture", picture));
        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(in);
    }
}
