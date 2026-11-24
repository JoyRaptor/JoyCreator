package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What a timeline bar says about an item (NAMING_DESIGN 2026-10-14): the person's own name when it
 * has one, else the automatic label it always had ("IMG", "VIZ", a text's words). A name is a pure
 * label, so an empty or blank one means "no name" and the automatic label stays. Pure Java so the
 * harness pins the rule ({@code run-itemname.sh}).
 */
public final class ItemLabel {
    private ItemLabel() {}

    @NonNull
    public static String of(@Nullable String name, @NonNull String automatic) {
        if (name == null) return automatic;
        String t = name.trim();
        return t.isEmpty() ? automatic : t;
    }
}
