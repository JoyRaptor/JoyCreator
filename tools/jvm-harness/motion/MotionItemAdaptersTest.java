import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.PipPlacement;
import com.fadcam.ui.faditor.model.SpaceLink;
import com.fadcam.ui.faditor.model.SpineTransform;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.motion.ItemMotionSampler;
import com.fadcam.ui.faditor.motion.MotionItemAdapters;
import com.fadcam.ui.faditor.motion.SweptBounds;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.transform.TransformQuad;

public final class MotionItemAdaptersTest {
    private static final int W = 1280, H = 720;

    public static void main(String[] args) {
        var parent = new TextOverlayItem("title", -1, .4f, .5f, .2f, 0f);
        parent.setTimeRange(100, 200);
        parent.getKeyframes().getOrCreate(KeyframeSet.X).put(0, .4f, Easing.LINEAR);
        parent.getKeyframes().getOrCreate(KeyframeSet.X).put(1, .8f, Easing.LINEAR);
        parent.getKeyframes().getOrCreate(KeyframeSet.SCALE_X).put(0, 1f, Easing.LINEAR);
        parent.getKeyframes().getOrCreate(KeyframeSet.SCALE_X).put(1, 2f, Easing.LINEAR);
        parent.setFlipH(true);
        parent.setRotationPivot(0f, 0f);

        var sprite = new SpriteOverlayItem("sprite", "sheet");
        sprite.setCenter(.55f, .5f);
        sprite.setSizeFraction(.15f);
        sprite.setTimeRange(100, 200);
        var link = SpaceLink.capture(parent, 100, W / (float) H);
        link.parentRef = parent;
        sprite.setSpaceLink(link);

        var text = MotionItemAdapters.textOrImage(parent, W, H, H * 2f, H);
        var child = MotionItemAdapters.sprite(sprite, W, H, 2f);
        for (long ms = 98; ms <= 106; ms++) {
            float width = H * 2 * parent.animatedSizeFraction(ms) * parent.animatedScaleX(ms);
            float height = H * parent.animatedSizeFraction(ms) * parent.animatedScaleY(ms);
            float[] q = new float[8];
            TransformQuad.renderQuad(parent.animatedCenterX(ms) * W, parent.animatedCenterY(ms) * H,
                    width, height, null, parent.animatedRotation(ms), parent.mirrorSignX(),
                    parent.mirrorSignY(), parent.mirrorSignX() * parent.pivotOffsetFromCentreX(width),
                    parent.mirrorSignY() * parent.pivotOffsetFromCentreY(height), q);
            corners(q, text.at(ms * 1000), "text/image at whole milliseconds");
            close(sprite.animatedCenterX(ms) * W, child.at(ms * 1000).x(.5, .5),
                    "sprite/parent at whole milliseconds");
        }

        double quarter = Boolean.getBoolean("motion.wrongRoundedItem")
                ? child.at(100_000).x(.5, .5) : child.at(100_250).x(.5, .5);
        close(.65f * W, quarter, "sprite follows fractional parent motion");
        close(.5 * W, text.at(100_250).x(.5, .5), "text fractional key");
        close(-360, text.at(100_250).xx(), "text reflection and fractional scale");

        // Canvas titles ignore image mirroring/pivot and use their measured layout canvas.
        var title = Boolean.getBoolean("motion.wrongTitlePlacement") ? text
                : MotionItemAdapters.title(parent, W, H, W / 2, H, 200, 100);
        var titlePose = title.at(100_250);
        close(.5 * W / 2, titlePose.x(.5, .5), "upright title stays centered at fractional time");
        close(.5 * H, titlePose.y(.5, .5), "title layout center");
        close(100, titlePose.xx(), "title width uses the layout to output ratio");
        close(100, titlePose.yy(), "title height stays at fixed font size");

        var clip = new Clip(null, 600_000);
        var pipKeys = new KeyframeSet();
        pipKeys.getOrCreate(KeyframeSet.X).put(0, .2f, Easing.LINEAR);
        pipKeys.getOrCreate(KeyframeSet.X).put(1, .6f, Easing.LINEAR);
        clip.setOverlayTransform(pipKeys);
        var pip = MotionItemAdapters.pip(clip, W, H, 600, 800, .5f, .5f, .35f);
        float[] size = PipPlacement.fitSize(W, H, 600, 800, .35f);
        var matrix = PipPlacement.quadMatrix(W, H, .2f * W, .5f * H, size[0], size[1], 0);
        float[] legacyPip = PipPlacement.cornerPixel(matrix, W, H, -1, 1);
        close(legacyPip[0], pip.at(0).x(0, 0), "zero-time PiP x");
        close(legacyPip[1], pip.at(0).y(0, 0), "zero-time PiP y");
        close(.3f * W, pip.at(250).x(.5, .5), "fractional PiP key");

        clip.getOrCreateSpineTransform().getOrCreate(SpineTransform.X).put(0, 0f, Easing.LINEAR);
        clip.getOrCreateSpineTransform().getOrCreate(SpineTransform.X).put(1, 1f, Easing.LINEAR);
        var spine = MotionItemAdapters.spine(clip, 300_000, W, H);
        float[] pose = new float[SpineTransform.POSE];
        clip.spinePoseAt(0L, pose);
        close(pose[SpineTransform.CX] * W, spine.at(300_000).x(.5, .5), "whole spine clock");
        close(.25 * W, spine.at(300_250).x(.5, .5), "fractional spine clock");

        ItemMotionSampler rotated = t -> t == 1 ? new ItemMotionSampler.Affine(0, -20, 40, 0, 21, 12)
                : new ItemMotionSampler.Affine(40, 0, 0, 20, -4, 11);
        var box = SweptBounds.ofSamples(rotated, new long[]{0, 1, 2}, 80, 90, 2);
        if (!box.equals(new SweptBounds(0, 9, 38, 54))) throw new AssertionError("sample scissor " + box);
        checkContains(box, rotated.at(1));
        System.out.println("MotionItemAdaptersTest: text, image, sprite, parent, PiP, spine and scissor passed");
    }

    private static void checkContains(SweptBounds box, ItemMotionSampler.Affine a) {
        for (int u = 0; u <= 1; u++) {
            for (int v = 0; v <= 1; v++) {
                if (a.x(u, v) < box.left() || a.y(u, v) < box.top()
                        || a.x(u, v) > box.right() || a.y(u, v) > box.bottom()) {
                    throw new AssertionError("scissor missed an interior-time corner");
                }
            }
        }
    }

    private static void corners(float[] q, ItemMotionSampler.Affine got, String label) {
        for (int i = 0; i < 4; i++) {
            int u = (i == 0 || i == 3) ? 0 : 1;
            int v = i < 2 ? 0 : 1;
            close(q[2 * i], got.x(u, v), label + " x");
            close(q[2 * i + 1], got.y(u, v), label + " y");
        }
    }

    private static void close(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 0.002) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
