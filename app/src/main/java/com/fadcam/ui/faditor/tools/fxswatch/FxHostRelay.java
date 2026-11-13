package com.fadcam.ui.faditor.tools.fxswatch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxParam;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.tools.FxPanel;

/**
 * The editor's {@link FxPanel.Host}, passed straight through, with one addition: after every
 * {@link #onFxChanged} (a slider moved, a colour was picked, a key was set) the card swatches
 * are told, so each redraws if its own values changed. One place for it, instead of a hook in
 * every control.
 */
public final class FxHostRelay implements FxPanel.Host {

    private final FxPanel.Host host;
    private final Runnable afterChange;

    public FxHostRelay(@NonNull FxPanel.Host host, @NonNull Runnable afterChange) {
        this.host = host;
        this.afterChange = afterChange;
    }

    @Override
    public void onFxChanged() {
        host.onFxChanged();
        afterChange.run();
    }

    @Override
    public void recordUndo(@NonNull String label, @NonNull Runnable redo, @NonNull Runnable undo) {
        host.recordUndo(label, redo, undo);
    }

    @Override
    public long playheadMs() {
        return host.playheadMs();
    }

    @Override
    public void seekTo(long ms) {
        host.seekTo(ms);
    }

    @Override
    public String previewCaveat() {
        return host.previewCaveat();
    }

    @Override
    public void editGradientInPreview(@Nullable FxStack stack, @Nullable FxInstance card,
                                      @Nullable FxParam param) {
        host.editGradientInPreview(stack, card, param);
    }

    @Override
    public boolean isEditingGradientInPreview(@Nullable FxInstance card,
                                              @Nullable FxParam param) {
        return host.isEditingGradientInPreview(card, param);
    }
}
