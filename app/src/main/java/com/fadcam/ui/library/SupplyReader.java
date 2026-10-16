package com.fadcam.ui.library;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.fadcam.FLog;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.ui.faditor.text.FontLibrary;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Fonts the user imported. They live inside the app ({@code files/fonts}, {@link FontLibrary}) and are
 * not in the Joy Creator folder yet (the storage audit's step B7), but they are the user's own
 * supplies, so the Library lists them beside any supplies the folder holds.
 */
final class SupplyReader {

    private SupplyReader() {}

    @NonNull
    static List<LibraryItem> fonts(@NonNull Context c) {
        List<LibraryItem> out = new ArrayList<>();
        try {
            FontLibrary.init(c);
            for (String[] f : FontLibrary.imported()) {
                String path = f[0].startsWith("file:") ? f[0].substring(5) : f[0];
                File file = new File(path);
                LibraryItem it = new LibraryItem("font:" + path, LibraryKind.SUPPLIES, f[1],
                        file.lastModified(), file.length(), false);
                it.fileKind = FileKind.FONT;
                it.fileName = file.getName();
                it.fontPath = path;
                it.uri = Uri.fromFile(file).toString();
                out.add(it);
            }
        } catch (Exception e) {
            FLog.w("LibrarySupply", "font list failed", e);
        }
        return out;
    }
}
