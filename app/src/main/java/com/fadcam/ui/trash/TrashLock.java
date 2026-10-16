package com.fadcam.ui.trash;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.fadcam.SharedPreferencesManager;
import com.guardanis.applock.AppLock;
import com.guardanis.applock.dialogs.UnlockDialogBuilder;

/**
 * The app lock, kept as the classic Trash keeps it: with the lock on, nothing deleted shows until it
 * is unlocked, and cancelling the unlock closes the screen.
 */
final class TrashLock {

    private TrashLock() {}

    static void gate(@NonNull FragmentActivity a, @NonNull View content, @NonNull Runnable onCancel) {
        boolean locked = SharedPreferencesManager.getInstance(a).isAppLockEnabled() && AppLock.isEnrolled(a);
        if (!locked) {
            content.setVisibility(View.VISIBLE);
            return;
        }
        content.setVisibility(View.INVISIBLE);
        new UnlockDialogBuilder(a)
                .onUnlocked(() -> content.setVisibility(View.VISIBLE))
                .onCanceled(onCancel::run)
                .show();
    }
}
