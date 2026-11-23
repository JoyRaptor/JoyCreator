package com.fadcam.ui.faditor.motion;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fadcam.R;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.faditor.StudioDialog;

import java.util.function.Supplier;

/** Displays a temporary export frame and releases it on dismiss. */
public final class MotionFrameInspector {
    private MotionFrameInspector() {}

    public static AlertDialog show(AppCompatActivity activity, Supplier<Bitmap> render) {
        var builder = StudioDialog.builder(activity);
        var context = builder.getContext();
        FrameLayout content = new FrameLayout(context);
        content.setMinimumHeight(Math.min(SheetKit.dp(context, 440),
                context.getResources().getDisplayMetrics().heightPixels / 2));
        var image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        content.addView(image, new FrameLayout.LayoutParams(-1, -1));
        var status = new TextView(context);
        status.setText(R.string.studio_motion_inspect_working);
        status.setGravity(Gravity.CENTER);
        content.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        var busy = new ProgressBar(context);
        content.addView(busy, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        AlertDialog dialog = builder.setTitle(R.string.studio_motion_inspect)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        Bitmap[] displayed = new Bitmap[1];
        Thread worker = new Thread(() -> {
            Bitmap frame;
            try {
                frame = render.get();
            } catch (RuntimeException e) {
                com.fadcam.FLog.w("MotionFrameInspector", "Frame render failed", e);
                frame = null;
            }
            final Bitmap result = frame;
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing() || activity.isDestroyed()) {
                    if (result != null) result.recycle();
                    return;
                }
                busy.setVisibility(View.GONE);
                if (result == null) {
                    status.setText(R.string.studio_motion_inspect_failed);
                    status.setGravity(Gravity.CENTER);
                    status.getLayoutParams().height = FrameLayout.LayoutParams.MATCH_PARENT;
                    status.requestLayout();
                    return;
                }
                status.setVisibility(View.GONE);
                displayed[0] = result;
                image.setImageBitmap(result);
            });
        }, "studio-motion-inspect");
        dialog.setOnDismissListener(ignored -> {
            worker.interrupt();
            image.setImageDrawable(null);
            if (displayed[0] != null) displayed[0].recycle();
        });
        dialog.show();
        worker.start();
        return dialog;
    }
}
