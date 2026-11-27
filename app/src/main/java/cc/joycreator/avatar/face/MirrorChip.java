package cc.joycreator.avatar.face;

import com.fadcam.ui.faditor.avatar.face.FaceTuning;

/**
 * The Mirror chip, as the pilot's tick: ON = the character moves like your reflection (the default). The face engine's own flag
 * says the other thing ({@code FaceTuning.mirror} true = like someone facing you), so the chip is its opposite. (The owner's take,
 * 11-27: the room's chip read OFF while the character reflected him, and ON switched it to facing.)
 */
public final class MirrorChip {
    private MirrorChip() {}

    public static boolean on(FaceTuning t) {
        return t == null || !t.mirror;
    }

    public static void set(FaceTuning t, boolean on) {
        t.mirror = !on;
    }
}
