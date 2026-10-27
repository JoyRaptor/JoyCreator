package cc.joycreator.joybrush.androidkit.gl

/**
 * Requirement 5 after a history move (undo, redo, a cancelled media stroke): does a tile's restored look still agree with
 * its payload? Pure (no GL), so the rule is tested on the JVM.
 *
 * A look restored together with its payload agrees. A look restored ALONE agrees when the look it replaces was the agreed
 * one: the funnel writes a look-only change only to a tile whose payload it did not change, and only after that tile's
 * load settled agreement (a disagreeing payload is dropped as the tile loads), so both ends of such a change agree with the
 * same payload. Before 2026-10-27 every look-only restore was taken for a write that bypassed the funnel: the shading ring
 * a stroke redraws around itself is look-only, so undoing an oil stroke dropped its neighbours' paint (the owner's Note 9:
 * tiles showing a different moment than their neighbours, and a drop step that cleared redo).
 *
 * A look-only change whose replaced end never agreed (a real bypass, e.g. pixels moved over the paint) still disagrees.
 */
internal object MediaAgreement {
    /**
     * [hasPayload]: the tile has a payload now. [payloadInChange]: this history move restored the payload too.
     * [replacedLook]: the look texture the move took off the tile. [agreedBefore]: the agreed look before the move (null: none).
     */
    fun agrees(hasPayload: Boolean, payloadInChange: Boolean, replacedLook: Int, agreedBefore: Int?): Boolean =
        hasPayload && (payloadInChange || agreedBefore == replacedLook)
}
