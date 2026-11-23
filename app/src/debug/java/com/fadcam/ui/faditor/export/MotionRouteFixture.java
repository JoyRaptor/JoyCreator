package com.fadcam.ui.faditor.export;

import com.fadcam.ui.faditor.motion.MotionShutter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.SpineTransform;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Disposable synthetic motion projects driven through the regular editor and export service. */
final class MotionRouteFixture {
    private MotionRouteFixture() {}

    static FaditorProject create(Context context) throws IOException {
        ProjectStorage storage = new ProjectStorage(context);
        var project = new FaditorProject(storage.uniqueName("TEST STU-7 route"));
        var timeline = project.getTimeline();
        File media = new File(context.getFilesDir(), "motion-route/" + project.getId());
        if (!media.mkdirs()) throw new IOException("Cannot create route fixture media directory");

        File first = copyAsset(context, media, "card1.mp4");
        File second = copyAsset(context, media, "card2.mp4");
        File rotated = new File(media, "rotated.mp4");
        long rotatedMs = rotate(first, rotated);
        long normalMs = durationMs(second);
        long cutMs = Math.min(1_000L, rotatedMs);
        long afterMs = Math.min(1_000L, normalMs);
        long endMs = cutMs + afterMs;
        timeline.addClip(clip(rotated, rotatedMs, cutMs));
        timeline.addClip(clip(second, normalMs, afterMs));

        File png = new File(media, "transparent.png");
        try (FileOutputStream out = new FileOutputStream(png)) {
            Bitmap art = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            try {
                for (int y = 0; y < 64; y++) {
                    for (int x = 0; x < 64; x++) {
                        if (x >= 6 && x < 58 && y >= 6 && y < 58) {
                            art.setPixel(x, y, x < 32 ? Color.MAGENTA : 0x8000FFFF);
                        }
                    }
                }
                if (!art.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("Cannot encode route fixture art");
                }
            } finally {
                art.recycle();
            }
        }
        String artUri = Uri.fromFile(png).toString();
        var title = new TextOverlayItem("C1 ZOOM", Color.WHITE, 0.5f, 0.2f, 0.08f, 0f);
        title.setTimeRange(0, endMs);
        title.addPropertyKeyframeAt(KeyframeSet.SCALE, 0, 0.08f);
        title.addPropertyKeyframeAt(KeyframeSet.SCALE, endMs - 1, 0.24f);
        timeline.addTextOverlay(title);

        var picture = TextOverlayItem.createImage(artUri, 0.36f, 0.55f, 0.15f);
        picture.setTimeRange(0, endMs);
        picture.setOpacity(0.5f);
        picture.addPropertyKeyframeAt(KeyframeSet.X, 0, 0.36f);
        picture.addPropertyKeyframeAt(KeyframeSet.X, endMs - 1, 0.62f);
        timeline.addTextOverlay(picture);

        var sheet = new SpriteSheet("c1-single-cell", "C1 sample", artUri);
        project.getSpriteSheets().add(sheet);
        var sprite = SpriteOverlayItem.create(sheet.getId());
        sprite.setCenter(0.24f, 0.78f);
        sprite.setSizeFraction(0.12f);
        sprite.setTimeRange(0, endMs);
        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
        sprite.addPropertyKeyframeAt(KeyframeSet.X, 0, 0.24f);
        sprite.addPropertyKeyframeAt(KeyframeSet.X, endMs - 1, 0.72f);
        timeline.addSpriteOverlay(sprite);

        var pip = clip(second, normalMs, afterMs);
        pip.setLayerId("video-c1-pip");
        pip.setOverlayStartMs(Math.min(250L, cutMs / 2));
        pip.setOverlayAudioEnabled(false);
        timeline.addOverlayClip(pip);

        var adjustment = new AdjustmentLayer();
        adjustment.setLayerId("adjustment-c1");
        adjustment.setStartMs(0);
        adjustment.setDurationMs(endMs);
        adjustment.getFx().add("invert");
        timeline.addAdjustmentLayer(adjustment);

        if (!storage.save(project)) throw new IOException("Could not save the route fixture");
        return project;
    }

    static FaditorProject createSpine(Context context, boolean enabled, boolean animated,
                                      boolean transparentArt, boolean doubleSpeed,
                                      MotionShutter.Controls controls, boolean masterOff) throws IOException {
        ProjectStorage storage = new ProjectStorage(context);
        String label = !animated ? "motion only" : enabled ? "blur" : "sharp";
        if (transparentArt) label = "art " + label;
        if (doubleSpeed) label = "2x " + label;
        if (enabled && masterOff) label += " master off";
        if (!MotionShutter.Controls.NATURAL.equals(controls)) label += " preset " + controls.mixPercent();
        var project = new FaditorProject(storage.uniqueName("TEST STU-7 C2 " + label));
        project.setMotionBlurEnabled(enabled && !masterOff);
        File media = new File(context.getFilesDir(), "motion-route/" + project.getId());
        if (!media.mkdirs()) throw new IOException("Cannot create spine fixture media directory");
        File source = transparentArt ? createShutterArt(media) : copyAsset(context, media, "card2.mp4");
        long duration = transparentArt ? 1_000L : durationMs(source);
        long sourceSpan = doubleSpeed ? 2_000L : 1_000L;
        if (duration < sourceSpan) throw new IOException("Spine fixture source is too short");
        Clip clip = clip(source, duration, sourceSpan);
        if (doubleSpeed) clip.setSpeedMultiplier(2f);
        clip.setImageClip(transparentArt);
        if (transparentArt) clip.setAudioMuted(true);
        clip.setRigidMotionBlurEnabled(enabled);
        clip.setRigidMotionShutter(controls);
        if (animated) {
            clip.setSpineScale(0.38f);
            var keys = clip.getOrCreateSpineTransform();
            var x = keys.getOrCreate(SpineTransform.X);
            x.put(0, 0.24f, Easing.LINEAR);
            x.put(250, 0.24f, Easing.LINEAR);
            x.put(500, 0.76f, Easing.HOLD);
            // The 30 fps frame at 733.333 ms has a shutter that straddles this jump.
            x.put(735, 0.38f, Easing.LINEAR);
            var y = keys.getOrCreate(SpineTransform.Y);
            y.put(0, 0.38f, Easing.LINEAR);
            y.put(250, 0.38f, Easing.LINEAR);
            y.put(500, 0.62f, Easing.HOLD);
            y.put(735, 0.48f, Easing.LINEAR);
            var rotation = keys.getOrCreate(SpineTransform.ROTATION);
            rotation.put(0, 0f, Easing.LINEAR);
            rotation.put(250, 0f, Easing.LINEAR);
            rotation.put(500, 55f, Easing.HOLD);
            rotation.put(735, -12f, Easing.LINEAR);
        }
        project.getTimeline().addClip(clip);
        if (!storage.save(project)) throw new IOException("Could not save the spine fixture");
        return project;
    }

    static FaditorProject createLanePicture(Context context, boolean adjustmentAbove,
                                             boolean includePip, boolean includeTitle,
                                             boolean includeSprite) throws IOException {
        ProjectStorage storage = new ProjectStorage(context);
        var project = new FaditorProject(storage.uniqueName(
                "TEST STU-7 C4 picture " + (adjustmentAbove ? "under effect" : "over effect")));
        File media = new File(context.getFilesDir(), "motion-route/" + project.getId());
        if (!media.mkdirs()) throw new IOException("Cannot create lane fixture media directory");
        File source = copyAsset(context, media, "card2.mp4");
        long duration = durationMs(source);
        if (duration < 1_000L) throw new IOException("Lane fixture source is too short");
        var timeline = project.getTimeline();
        timeline.addClip(clip(source, duration, 1_000L));

        String uri = Uri.fromFile(createShutterArt(media)).toString();
        var picture = TextOverlayItem.createImage(uri, 0.46f, 0.50f, 0.68f);
        picture.setLayerId("text");
        picture.setTimeRange(0L, 999L);
        timeline.addTextOverlay(picture);
        if (includeTitle) {
            var title = new TextOverlayItem("ORDER TEST", Color.MAGENTA, 0.56f, 0.30f, 0.09f, 0f);
            title.setLayerId("text");
            title.setTimeRange(0L, 999L);
            timeline.addTextOverlay(title);
        }
        var adjustment = new AdjustmentLayer();
        adjustment.setLayerId("adjustment");
        adjustment.setStartMs(0L);
        adjustment.setDurationMs(1_000L);
        adjustment.getFx().add("invert");
        timeline.addAdjustmentLayer(adjustment);
        timeline.getOrCreateTrackFlags("text").zIndex = 4;
        timeline.getOrCreateTrackFlags("adjustment").zIndex = adjustmentAbove ? 8 : 2;
        if (includePip) {
            File overlaySource = copyAsset(context, media, "card1.mp4");
            long overlayDuration = durationMs(overlaySource);
            Clip pip = clip(overlaySource, overlayDuration, Math.min(800L, overlayDuration));
            pip.setLayerId("video");
            pip.setOverlayStartMs(100L);
            pip.setOverlayAudioEnabled(false);
            timeline.addOverlayClip(pip);
            timeline.getOrCreateTrackFlags("video").zIndex = 3;
        }

        if (includeSprite) {
            var atlas = new SpriteSheet("c4-sprite-cells", "C4 atlas", Uri.fromFile(writeSpriteAtlas(media)).toString());
            atlas.setGrid(2, 1);
            project.getSpriteSheets().add(atlas);
            var sprite = SpriteOverlayItem.create(atlas.getId());
            sprite.setLayerId("sprite");
            sprite.setTimeRange(0L, 999L);
            sprite.setCenter(0.71f, 0.60f);
            sprite.setSizeFraction(0.16f);
            sprite.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
            sprite.getFrameTrack().put(FrameTrack.Key.ofCell(367, 1));
            timeline.addSpriteOverlay(sprite);
            timeline.getOrCreateTrackFlags("sprite").zIndex = 4;
            timeline.getOrCreateTrackFlags("text").zIndex = 5;
        }

        if (!storage.save(project)) throw new IOException("Could not save the lane fixture");
        return project;
    }

    static FaditorProject createPicture(Context context, boolean enabled) throws IOException {
        ProjectStorage storage = new ProjectStorage(context);
        String variant = enabled ? "blur" : "sharp";
        var project = new FaditorProject(storage.uniqueName("TEST STU-7 C2 picture " + variant));
        project.setMotionBlurEnabled(enabled);
        File media = new File(context.getFilesDir(), "motion-route/" + project.getId());
        if (!media.mkdirs()) throw new IOException("Cannot create picture fixture media directory");
        File source = copyAsset(context, media, "card2.mp4");
        long duration = durationMs(source);
        if (duration < 1_000L) throw new IOException("Picture fixture is shorter than 1 second");
        project.getTimeline().addClip(clip(source, duration, 1_000L));

        String art = Uri.fromFile(createShutterArt(media)).toString();
        var under = TextOverlayItem.createImage(art, 0.45f, 0.62f, 0.56f);
        under.setTimeRange(0, 999);
        under.setOpacity(0.35f);
        project.getTimeline().addTextOverlay(under);

        var picture = TextOverlayItem.createImage(art, 0.24f, 0.28f, 0.55f);
        picture.setTimeRange(0, 899);
        picture.setOpacity(0.72f);
        picture.setRigidMotionBlurEnabled(enabled);
        var keys = picture.getKeyframes();
        var x = keys.getOrCreate(KeyframeSet.X);
        x.put(0, 0.24f, Easing.LINEAR);
        x.put(250, 0.24f, Easing.LINEAR);
        x.put(500, 0.76f, Easing.HOLD);
        x.put(735, 0.38f, Easing.LINEAR);
        var y = keys.getOrCreate(KeyframeSet.Y);
        y.put(0, 0.28f, Easing.LINEAR);
        y.put(250, 0.28f, Easing.LINEAR);
        y.put(500, 0.61f, Easing.HOLD);
        y.put(735, 0.43f, Easing.LINEAR);
        var rotation = keys.getOrCreate(KeyframeSet.ROTATION);
        rotation.put(0, -10f, Easing.LINEAR);
        rotation.put(250, -10f, Easing.LINEAR);
        rotation.put(500, 40f, Easing.HOLD);
        rotation.put(735, -20f, Easing.LINEAR);
        project.getTimeline().addTextOverlay(picture);
        if (!storage.save(project)) throw new IOException("Could not save the picture fixture");
        return project;
    }

    static FaditorProject createSprite(Context context, boolean enabled, boolean masterOff) throws IOException {
        ProjectStorage storage = new ProjectStorage(context);
        String variant = enabled ? "blur" : "sharp";
        var project = new FaditorProject(storage.uniqueName("TEST STU-7 C2 sprite " + variant + (enabled && masterOff ? " master off" : "")));
        project.setMotionBlurEnabled(enabled && !masterOff);
        File media = new File(context.getFilesDir(), "motion-route/" + project.getId());
        if (!media.mkdirs()) throw new IOException("Cannot create sprite fixture media directory");
        File source = copyAsset(context, media, "card2.mp4");
        long duration = durationMs(source);
        if (duration < 1_000L) throw new IOException("Sprite fixture is shorter than 1 second");
        project.getTimeline().addClip(clip(source, duration, 1_000L));

        File art = writeSpriteAtlas(media);

        var sheet = new SpriteSheet("c2-sprite-cells", "C2 shutter cells", Uri.fromFile(art).toString());
        sheet.setGrid(2, 1);
        project.getSpriteSheets().add(sheet);
        var sprite = SpriteOverlayItem.create(sheet.getId());
        sprite.setCenter(0.22f, 0.58f);
        sprite.setSizeFraction(0.26f);
        sprite.setTimeRange(0, 899);
        sprite.setOpacity(0.82f);
        sprite.setRigidMotionBlurEnabled(enabled);
        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
        sprite.getFrameTrack().put(FrameTrack.Key.ofCell(367, 1)); // exposure at 366.667 ms crosses it
        var x = sprite.getKeyframes().getOrCreate(KeyframeSet.X);
        x.put(0, 0.22f, Easing.LINEAR);
        x.put(220, 0.22f, Easing.LINEAR);
        x.put(620, 0.75f, Easing.LINEAR);
        project.getTimeline().addSpriteOverlay(sprite);
        if (!storage.save(project)) throw new IOException("Could not save the sprite fixture");
        return project;
    }

    static FaditorProject createTitle(Context context, boolean enabled) throws IOException {
        ProjectStorage storage = new ProjectStorage(context);
        String variant = enabled ? "blur" : "sharp";
        var project = new FaditorProject(storage.uniqueName("TEST STU-7 C2 title " + variant));
        project.setMotionBlurEnabled(enabled);
        File media = new File(context.getFilesDir(), "motion-route/" + project.getId());
        if (!media.mkdirs()) throw new IOException("Cannot create title fixture media directory");
        File source = copyAsset(context, media, "card2.mp4");
        long duration = durationMs(source);
        if (duration < 1_000L) throw new IOException("Title fixture is shorter than 1 second");
        project.getTimeline().addClip(clip(source, duration, 1_000L));

        var under = TextOverlayItem.createImage(Uri.fromFile(createShutterArt(media)).toString(),
                0.55f, 0.54f, 0.56f);
        under.setTimeRange(0, 999);
        under.setOpacity(0.3f);
        project.getTimeline().addTextOverlay(under);

        var title = new TextOverlayItem("STUDIO\nMotion Blur", Color.WHITE, 0.22f, 0.34f, 0.12f, 0f);
        title.setTimeRange(0, 899);
        title.setOpacity(0.82f);
        title.setRigidMotionBlurEnabled(enabled);
        var x = title.getKeyframes().getOrCreate(KeyframeSet.X);
        x.put(0, 0.22f, Easing.LINEAR);
        x.put(250, 0.22f, Easing.LINEAR);
        x.put(500, 0.76f, Easing.HOLD);
        x.put(735, 0.39f, Easing.LINEAR);
        var y = title.getKeyframes().getOrCreate(KeyframeSet.Y);
        y.put(0, 0.34f, Easing.LINEAR);
        y.put(250, 0.34f, Easing.LINEAR);
        y.put(500, 0.59f, Easing.HOLD);
        y.put(735, 0.45f, Easing.LINEAR);
        var r = title.getKeyframes().getOrCreate(KeyframeSet.ROTATION);
        r.put(0, -10f, Easing.LINEAR);
        r.put(250, -10f, Easing.LINEAR);
        r.put(500, 30f, Easing.HOLD);
        r.put(735, -20f, Easing.LINEAR);
        project.getTimeline().addTextOverlay(title);
        if (!storage.save(project)) throw new IOException("Could not save the title fixture");
        return project;
    }

    static FaditorProject createPip(Context context, boolean enabled) throws IOException {
        var storage = new ProjectStorage(context);
        String variant = enabled ? "blur" : "sharp";
        var project = new FaditorProject(storage.uniqueName("TEST STU-7 C2 pip " + variant));
        project.setMotionBlurEnabled(enabled);
        File media = new File(context.getFilesDir(), "motion-route/" + project.getId());
        if (!media.mkdirs()) throw new IOException("Cannot create PiP fixture media directory");
        File base = copyAsset(context, media, "card2.mp4");
        File insert = copyAsset(context, media, "card1.mp4");
        long duration = durationMs(base);
        long insertDuration = durationMs(insert);
        if (duration < 1_000L || insertDuration < 1_000L) {
            throw new IOException("PiP fixture sources must be at least one second");
        }
        project.getTimeline().addClip(clip(base, duration, 1_000L));
        var pip = clip(insert, insertDuration, 1_000L);
        pip.setLayerId("video-c2-pip");
        pip.setOverlayStartMs(0L);
        pip.setOverlayAudioEnabled(false);
        pip.setRigidMotionBlurEnabled(enabled);
        var keys = new KeyframeSet();
        var x = keys.getOrCreate(KeyframeSet.X);
        x.put(0, 0.24f, Easing.LINEAR);
        x.put(250, 0.24f, Easing.LINEAR);
        x.put(500, 0.76f, Easing.HOLD);
        x.put(735, 0.39f, Easing.LINEAR);
        var y = keys.getOrCreate(KeyframeSet.Y);
        y.put(0, 0.32f, Easing.LINEAR);
        y.put(250, 0.32f, Easing.LINEAR);
        y.put(500, 0.59f, Easing.HOLD);
        y.put(735, 0.43f, Easing.LINEAR);
        var rotation = keys.getOrCreate(KeyframeSet.ROTATION);
        rotation.put(0, -10f, Easing.LINEAR);
        rotation.put(250, -10f, Easing.LINEAR);
        rotation.put(500, 40f, Easing.HOLD);
        rotation.put(735, -20f, Easing.LINEAR);
        keys.getOrCreate(KeyframeSet.SCALE).put(0, 0.42f, Easing.LINEAR);
        keys.getOrCreate(KeyframeSet.OPACITY).put(0, 0.8f, Easing.LINEAR);
        pip.setOverlayTransform(keys);
        project.getTimeline().addOverlayClip(pip);
        if (!storage.save(project)) throw new IOException("Could not save the PiP fixture");
        return project;
    }

    private static File writeSpriteAtlas(File media) throws IOException {
        File art = new File(media, "shutter-cells.png");
        Bitmap bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(art)) {
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            canvas.drawRect(10, 8, 119, 84, paint);
            paint.setColor(0x80FF4020);
            canvas.drawRect(10, 87, 78, 122, paint);
            paint.setColor(Color.CYAN);
            canvas.drawRect(138, 8, 247, 84, paint);
            paint.setColor(0x8000E0FF);
            canvas.drawRect(138, 87, 206, 122, paint);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Could not write shutter sprite cells");
            }
        } finally {
            bitmap.recycle();
        }
        return art;
    }

    private static File createShutterArt(File directory) throws IOException {
        File file = new File(directory, "shutter-art.png");
        Bitmap bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(file)) {
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint();
            // Opaque black/white transitions distinguish linear and encoded averages.
            for (int x = 96; x < 864; x += 24) {
                paint.setColor(((x - 96) / 24) % 2 == 0 ? Color.WHITE : Color.BLACK);
                canvas.drawRect(x, 54, x + 24, 270, paint);
            }
            paint.setColor(0x80FFFFFF);
            canvas.drawRect(96, 324, 384, 486, paint);
            paint.setColor(0x8000FFFF);
            canvas.drawRect(576, 324, 864, 486, paint);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Could not write shutter test art");
            }
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private static Clip clip(File file, long duration, long trim) {
        var clip = new Clip(Uri.fromFile(file), duration);
        clip.setOutPointMs(trim);
        return clip;
    }

    private static File copyAsset(Context context, File directory, String name) throws IOException {
        File file = new File(directory, name);
        try (var in = context.getAssets().open("demo/" + name);
             var out = new FileOutputStream(file)) {
            byte[] bytes = new byte[8_192];
            for (int length = in.read(bytes); length >= 0; length = in.read(bytes)) {
                out.write(bytes, 0, length);
            }
        }
        return file;
    }

    private static long durationMs(File input) throws IOException {
        var extractor = new MediaExtractor();
        try {
            extractor.setDataSource(input.getAbsolutePath());
            return extractor.getTrackFormat(videoTrack(extractor)).getLong(MediaFormat.KEY_DURATION) / 1000;
        } finally {
            extractor.release();
        }
    }

    private static long rotate(File input, File output) throws IOException {
        var extractor = new MediaExtractor();
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int video = videoTrack(extractor);
            var format = extractor.getTrackFormat(video);
            extractor.selectTrack(video);
            var muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try {
                // Use actual source metadata rather than a Studio transform for this route test.
                format.setInteger(MediaFormat.KEY_ROTATION, 0);
                muxer.setOrientationHint(90);
                int destination = muxer.addTrack(format);
                muxer.start();
                int size = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                        ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 1_048_576;
                var buffer = ByteBuffer.allocate(Math.max(size, 1_048_576));
                var info = new MediaCodec.BufferInfo();
                for (int length; (length = extractor.readSampleData(buffer, 0)) >= 0; ) {
                    int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    info.set(0, length, extractor.getSampleTime(), flags);
                    muxer.writeSampleData(destination, buffer, info);
                    extractor.advance();
                }
                muxer.stop();
            } finally {
                muxer.release();
            }
            return format.getLong(MediaFormat.KEY_DURATION) / 1000;
        } finally {
            extractor.release();
        }
    }

    private static int videoTrack(MediaExtractor extractor) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        throw new IOException("Route fixture has no video track");
    }
}
