package dev.gf2log.app.capture

import dev.gf2log.app.capture.PreIdentityPolicy.Retention
import dev.gf2log.protocol.Gfl2PayloadDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class PreIdentityPolicyTest {
    @Test
    fun platoonDataIsAlwaysHeldUntilIdentity() {
        listOf(
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
        ).forEach { type ->
            assertEquals(Retention.REQUIRED, PreIdentityPolicy.retention(type, historyEnabled = true))
            assertEquals(Retention.REQUIRED, PreIdentityPolicy.retention(type, historyEnabled = false))
        }
    }

    @Test
    fun otherPayloadsAreHeldOnlyForEnabledHistory() {
        assertEquals(
            Retention.HISTORY_ONLY,
            PreIdentityPolicy.retention(Gfl2PayloadDecoder.TYPE_FORMATIONS, historyEnabled = true),
        )
        assertEquals(
            Retention.NONE,
            PreIdentityPolicy.retention(Gfl2PayloadDecoder.TYPE_WEAPONS, historyEnabled = false),
        )
    }

    @Test
    fun aProfileThatCouldNotIdentifyTheFlowIsNeverHeld() {
        // An account in no Platoon sends 21905 with Platoon ID 0 on every login.
        assertEquals(
            Retention.NONE,
            PreIdentityPolicy.retention(Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE, historyEnabled = true),
        )
    }

    @Test
    fun aFlowOfNoPlatoonNeverFillsTheBufferOrWarns() {
        val buffer = BoundedFlowPayloadBuffer<Int>(32, 128)
        val types = listOf(Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE) + List(40) { Gfl2PayloadDecoder.TYPE_WEAPONS }
        val overflows = types.count { type ->
            when (PreIdentityPolicy.retention(type, historyEnabled = false)) {
                Retention.REQUIRED -> buffer.offer(1, type) == BoundedFlowPayloadBuffer.OfferResult.OVERFLOW
                Retention.HISTORY_ONLY -> buffer.offerOptional(1, type) == BoundedFlowPayloadBuffer.OfferResult.OVERFLOW
                Retention.NONE -> false
            }
        }

        assertEquals(0, overflows)
        assertEquals(0, buffer.size())
    }
}
