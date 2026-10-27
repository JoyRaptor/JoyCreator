package cc.joycreator.joybrush.androidkit.gl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaAgreementTest {
    @Test fun aLookRestoredWithItsPayloadAgrees() {
        assertTrue(MediaAgreement.agrees(hasPayload = true, payloadInChange = true, replacedLook = 7, agreedBefore = null))
    }

    @Test fun undoingAStrokesShadingRingKeepsTheNeighboursPaint() {
        // The stroke redrew tile k's look 5 -> 9 (ring only); 9 agreed with k's unchanged payload. Undo takes 9 off.
        assertTrue(MediaAgreement.agrees(hasPayload = true, payloadInChange = false, replacedLook = 9, agreedBefore = 9))
    }

    @Test fun aLookThatNeverAgreedStillLosesItsPayload() {
        // Pixels moved over the paint outside the funnel (look 9 never agreed; 5 did): moving history off 9 is no proof.
        assertFalse(MediaAgreement.agrees(hasPayload = true, payloadInChange = false, replacedLook = 9, agreedBefore = 5))
        assertFalse(MediaAgreement.agrees(hasPayload = true, payloadInChange = false, replacedLook = 9, agreedBefore = null))
    }

    @Test fun noPayloadNothingToAgree() {
        assertFalse(MediaAgreement.agrees(hasPayload = false, payloadInChange = true, replacedLook = 9, agreedBefore = 9))
    }
}
