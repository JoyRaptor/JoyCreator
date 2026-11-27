package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * DEBUG BUILDS ONLY (TXT-TRANSFER device check, 11-27): one 4 s project that exercises a TextLab title on Studio's
 * text-effects GL export. The title wears an animated TextLab look (Magma), carries its own effects (glow, hue), is
 * corner-pinned, moves and turns on keyframes, and grows 1x to 1.8x between 2.0 and 2.6 s (the title picture is
 * redrawn at a new size: the "resize"). A hue adjustment with a feathered, turned shape mask sits above it. Exported
 * whole, and as a range from 2.5 s (a "seek": the title starts mid-animation), on the old and the new build, so
 * decoded frames can be compared.
 */
final class TitleTransferFixture {

    private TitleTransferFixture() {}

    static FaditorProject create(Context context) throws Exception {
        ProjectStorage storage = new ProjectStorage(context);
        var project = new FaditorProject(storage.uniqueName("TEST TXT-TRANSFER title"));
        File media = new File(context.getFilesDir(), "txt-transfer/" + project.getId());
        if (!media.mkdirs()) throw new IOException("Cannot create the title fixture's media folder");
        File source = copyAsset(context, media, "card2.mp4");
        long duration = durationMs(source);
        var clip = new Clip(Uri.fromFile(source), duration);
        clip.setOutPointMs(Math.min(duration, 4_000L));
        var timeline = project.getTimeline();
        timeline.addClip(clip);

        String look = readAsset(context, "textlab/looks/magma.text.json");
        var title = new TextOverlayItem("JOY TITLE", Color.WHITE, 0.30f, 0.40f, 0.10f, 0f);
        title.setTimeRange(0, 3_999);
        title.setTextLook(cc.joycreator.textlab.TitleLook.of("asset:magma", "Magma", look));
        title.getOrCreateFx().add("glow");
        title.getOrCreateFx().add("hue");
        title.setCornerPin(new float[]{0.06f, 0.02f, -0.04f, 0.08f, 0.02f, -0.05f, -0.06f, 0.00f});
        var x = title.getKeyframes().getOrCreate(KeyframeSet.X);
        x.put(0, 0.30f, Easing.LINEAR);
        x.put(3_999, 0.66f, Easing.LINEAR);
        var y = title.getKeyframes().getOrCreate(KeyframeSet.Y);
        y.put(0, 0.40f, Easing.LINEAR);
        y.put(3_999, 0.58f, Easing.LINEAR);
        var r = title.getKeyframes().getOrCreate(KeyframeSet.ROTATION);
        r.put(0, -8f, Easing.LINEAR);
        r.put(3_999, 14f, Easing.LINEAR);
        var s = title.getKeyframes().getOrCreate(KeyframeSet.SCALE);
        s.put(0, 1f, Easing.LINEAR);
        s.put(2_000, 1f, Easing.LINEAR);
        s.put(2_600, 1.8f, Easing.LINEAR);
        timeline.addTextOverlay(title);

        var adjustment = new AdjustmentLayer();
        adjustment.setStartMs(0L);
        adjustment.setDurationMs(4_000L);
        adjustment.getFx().add("hue");
        var mask = adjustment.getCompositing().addShape();
        mask.cx = 0.42f;
        mask.cy = 0.60f;
        mask.w = 0.55f;
        mask.h = 0.50f;
        mask.rotationDeg = 12f;
        mask.feather = 0.10f;
        timeline.addAdjustmentLayer(adjustment);

        if (!storage.save(project)) throw new IOException("Could not save the title fixture");
        return project;
    }

    private static String readAsset(Context context, String path) throws IOException {
        try (InputStream in = context.getAssets().open(path); var out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8_192];
            for (int n = in.read(b); n >= 0; n = in.read(b)) out.write(b, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static File copyAsset(Context context, File directory, String name) throws IOException {
        File file = new File(directory, name);
        try (var in = context.getAssets().open("demo/" + name); var out = new FileOutputStream(file)) {
            byte[] b = new byte[8_192];
            for (int n = in.read(b); n >= 0; n = in.read(b)) out.write(b, 0, n);
        }
        return file;
    }

    private static long durationMs(File input) throws IOException {
        var extractor = new MediaExtractor();
        try {
            extractor.setDataSource(input.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/"))
                    return extractor.getTrackFormat(i).getLong(MediaFormat.KEY_DURATION) / 1000;
            }
            throw new IOException("No video track");
        } finally {
            extractor.release();
        }
    }
}
