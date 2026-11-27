package cc.joycreator.avatar.ui;

import cc.joycreator.avatar.file.CharacterDoc;

/** The room's stand-in drawing ({@link RestPose}) for places outside this package that wrap it (Studio's Perform pop-up). */
public final class PoseRenderers {
    private PoseRenderers() {}

    /** The character standing still, one drawing of each set: hits, bounds and parts as the room's stage knows them. */
    public static PoseRenderer rest(CharacterDoc doc) {
        return new RestPose(doc);
    }
}
