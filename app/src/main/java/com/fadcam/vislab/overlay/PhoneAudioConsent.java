package com.fadcam.vislab.overlay;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

/**
 * Asks Android's own question ("Start recording or casting?") so the floating visualiser may hear
 * the phone's sound, and hands the answer to {@link VisOverlayService}. See-through and gone at
 * once: the overlay cannot ask itself (a service has no screen to ask from).
 */
public class PhoneAudioConsent extends ComponentActivity {

    private ActivityResultLauncher<Intent> ask;

    @Override
    protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        ask = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                VisOverlayService.phoneAudioAllowed(this, r.getResultCode(), r.getData());
            } else {
                VisOverlayService.phoneAudioRefused(this);
            }
            finish();
        });
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            finish();
            return;
        }
        // Recreated mid-question (a turn): the answer still comes back to the launcher above.
        if (saved == null) ask.launch(mpm.createScreenCaptureIntent());
    }
}
