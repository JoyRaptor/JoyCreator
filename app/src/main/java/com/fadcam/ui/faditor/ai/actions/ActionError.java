package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

/**
 * Something the person or the model has to fix before an action can run ("No text layer called
 * 'Title'"). The message is shown as-is, so it is written in plain words.
 */
public final class ActionError extends Exception {
    public ActionError(@NonNull String plainWords) {
        super(plainWords);
    }
}
