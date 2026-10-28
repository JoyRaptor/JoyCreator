package com.fadcam.vislab.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.avatar.AvatarRig;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import java.util.Collections;
import java.util.List;

/**
 * The open project's Sprite Lab sheets and avatar rigs, for a look that shows a Studio sprite or
 * a performing avatar (roadmap R9). Told by whoever holds the project: the editor when its
 * visualizers are bound, an export when it starts (its own process).
 */
public final class StudioArt {

    @NonNull private static volatile List<SpriteSheet> sheets = Collections.emptyList();
    @NonNull private static volatile List<AvatarRig> rigs = Collections.emptyList();

    private StudioArt() {}

    public static void set(@Nullable List<SpriteSheet> s, @Nullable List<AvatarRig> r) {
        sheets = s != null ? s : Collections.emptyList();
        rigs = r != null ? r : Collections.emptyList();
    }

    @Nullable
    static SpriteSheet sheet(@Nullable String id) {
        if (id == null) return null;
        for (SpriteSheet s : sheets) if (id.equals(s.getId())) return s;
        return null;
    }

    @Nullable
    static AvatarRig rig(@Nullable String id) {
        if (id == null) return null;
        for (AvatarRig r : rigs) if (id.equals(r.getId())) return r;
        return null;
    }
}
