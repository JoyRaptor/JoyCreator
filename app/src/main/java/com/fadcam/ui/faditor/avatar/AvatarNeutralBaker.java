package com.fadcam.ui.faditor.avatar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A4 editor slice: renders a rig's NEUTRAL pose (all drivers 0) to a
 * transparent PNG by driving {@link PuppetPreviewView} OFFSCREEN through its
 * public API — the same view that draws the studio preview and the recorder
 * bubble, so the bake is pixel-faithful to every live surface (preview==bake,
 * the doctrine). Used by the editor's insert-avatar-from-library path: the
 * baked PNG becomes a 1-cell sprite sheet the existing sprite pipeline
 * places/renders/exports with ZERO new compositor surface; the later
 * bake-to-keyframes slice upgrades the placed item to a live puppet.
 */
public final class AvatarNeutralBaker {

    private AvatarNeutralBaker() {}

    /** Asset-name convention linking a baked sheet back to its rig (the
     *  live-render upgrade finds the rig by this marker + the rig id inside). */
    @NonNull
    public static String neutralAssetName(@NonNull AvatarRig rig) {
        String id = rig.getId();
        return "avatar-" + (id.length() > 8 ? id.substring(0, 8) : id) + "-neutral.png";
    }

    /**
     * Bake {@code rig} at neutral into {@code dest} ({@code sizePx} square,
     * transparent background). Sheets resolve from {@code sheets} (library
     * bundle defs — file:// uris decode directly). Returns false on any
     * failure; never throws, nothing half-written survives.
     */
    public static boolean bakeNeutralPng(@NonNull Context ctx, @NonNull AvatarRig rig,
                                         @NonNull List<SpriteSheet> sheets,
                                         @NonNull File dest, int sizePx) {
        Map<String, SpriteSheetRenderer> renderers = new HashMap<>();
        Bitmap bmp = null;
        try {
            PuppetPreviewView view = new PuppetPreviewView(ctx);
            view.setCleanRender(true); // no studio crosshair in the bake
            view.setBackgroundColor(0x00000000);
            view.bind(rig, sheetId -> {
                if (renderers.containsKey(sheetId)) return renderers.get(sheetId);
                SpriteSheet s = byId(sheets, sheetId);
                SpriteSheetRenderer r = s != null ? SpriteSheetRenderer.load(ctx, s) : null;
                renderers.put(sheetId, r);
                return r;
            });
            view.setSheetLookup(id -> byId(sheets, id));
            view.setResolved(PuppetPoseResolver.resolve(
                    rig, new HashMap<>(), new PuppetPoseResolver.DiscreteState()));

            int spec = View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY);
            view.measure(spec, spec);
            view.layout(0, 0, sizePx, sizePx);
            bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bmp));

            File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
            }
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            return tmp.renameTo(dest);
        } catch (Exception e) {
            return false;
        } finally {
            if (bmp != null) bmp.recycle();
            for (SpriteSheetRenderer r : renderers.values()) {
                if (r != null) r.recycle();
            }
        }
    }

    @Nullable
    private static SpriteSheet byId(@NonNull List<SpriteSheet> sheets, @NonNull String id) {
        for (SpriteSheet s : sheets) {
            if (s.getId().equals(id)) return s;
        }
        return null;
    }
}
