package com.fadcam.ui.faditor.compositor;

import com.fadcam.ui.faditor.motion.ItemMotionSampler;

public final class RecordedAvatarStampGlTest {
    public static void main(String[] args) {
        int width = 1280;
        int height = 720;
        // A rotated, non-square and mirrored box catches the top-left to y-up conversion.
        var placement = new ItemMotionSampler.Affine(-320, 150, 80, 180, 610, 220);
        float[] matrix = new float[16];
        require(placement.writeClipTransform(matrix, width, height),
                "finite placement");
        if (args.length == 1 && "--wrong-y".equals(args[0])) {
            matrix[5] = -matrix[5];
        }
        for (int index = 0; index < 4; index++) {
            int u = index & 1;
            int v = index / 2;
            int x = 2 * u - 1;
            int y = 1 - 2 * v;
            double clipX = matrix[0] * x + matrix[4] * y + matrix[12];
            double clipY = matrix[1] * x + matrix[5] * y + matrix[13];
            require(Math.abs((clipX + 1) * width / 2 - placement.x(u, v)) < 0.001,
                    "avatar corners retain editor x");
            require(Math.abs((1 - clipY) * height / 2 - placement.y(u, v)) < 0.001,
                    "avatar corners retain top-left editor y");
        }
        require(!new ItemMotionSampler.Affine(Double.NaN, 0, 0, 1, 0, 0)
                        .writeClipTransform(matrix, width, height),
                "invalid taps leave the caller on its sharp path");
        System.out.println("RecordedAvatarStampGlTest PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
