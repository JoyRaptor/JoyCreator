package com.fadcam.ui.faditor.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class StudioVoiceRecipeTest {
    // Each 100 ms frame alternates between a specified room floor and a spoken burst.
    // Opposite stereo polarity exercises the analysis independently of speaker summing.
    private static byte[] sample(int channels, int floor, int speech, int floorPeriod) {
        byte[] pcm = new byte[40 * 800 * channels * 2];
        for (int i = 0; i < 40 * 800; i++) {
            int frame = i / 800;
            int amplitude = frame % 4 < 2 ? floor : speech;
            int period = frame % 4 < 2 ? floorPeriod : 24;
            int v = (int)(amplitude * Math.sin(i * 2 * Math.PI / period));
            for (int ch = 0; ch < channels; ch++) {
                int j = (i * channels + ch) * 2;
                int value = ch == 0 ? v : -v;
                pcm[j] = (byte)value;
                pcm[j+1] = (byte)(value >> 8);
            }
        }
        return pcm;
    }

    @Test public void cleanAndSilentRecordingsStayOriginal() {
        assertEquals(StudioVoiceRecipe.Strength.ORIGINAL, StudioVoiceRecipe.assess(sample(1,0,10000,8),1));
        assertEquals(StudioVoiceRecipe.Strength.ORIGINAL, StudioVoiceRecipe.assess(new byte[8000],1));
    }

    @Test public void phaseInvertedStereoHasTheSameDecisionAsMono() {
        assertEquals(StudioVoiceRecipe.Strength.STRONG,StudioVoiceRecipe.assess(sample(1,700,9000,8),1));
        assertEquals(StudioVoiceRecipe.Strength.STRONG,StudioVoiceRecipe.assess(sample(2,700,9000,8),2));
    }

    @Test public void bassNoiseUsesTheSpeechPreservingMix() {
        assertEquals(StudioVoiceRecipe.Strength.GENTLE,StudioVoiceRecipe.assess(sample(1,700,9000,100),1));
    }

    @Test public void shortSampleAndIntermittentFloorAreConservative() {
        assertEquals(StudioVoiceRecipe.Strength.GENTLE,StudioVoiceRecipe.assess(new byte[1600],1));
        double[] levels = {-55,-54,-52,-43,-42,-41,-41,-40,-39,-38,-37,-36,-28,-26,-24,-22,-20,-18,-17,-15};
        assertEquals(StudioVoiceRecipe.Strength.GENTLE,StudioVoiceRecipe.assessLevels(levels));
    }

    @Test public void denseCleanSpeechWithShortRoomToneStaysOriginal() {
        double[] levels = {-120,-119,-51,-50,-49,-44,-41,-38,-35,-33,-30,-29,-27,-25,-23,-21,-20,-19,-18,-16};
        assertEquals(StudioVoiceRecipe.Strength.ORIGINAL, StudioVoiceRecipe.assessLevels(levels));
    }
}
