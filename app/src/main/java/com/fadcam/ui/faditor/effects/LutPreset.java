package com.fadcam.ui.faditor.effects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class LutPreset {

    @NonNull public final String id;
    @NonNull public final String displayName;
    @NonNull public final String sourcePath;
    @Nullable public final String thumbnailPath;

    public LutPreset(@NonNull String id, @NonNull String displayName,
                     @NonNull String sourcePath, @Nullable String thumbnailPath) {
        this.id = id;
        this.displayName = displayName;
        this.sourcePath = sourcePath;
        this.thumbnailPath = thumbnailPath;
    }
}
