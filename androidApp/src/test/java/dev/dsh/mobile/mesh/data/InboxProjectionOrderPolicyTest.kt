package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxProjectionOrderPolicyTest {
    @Test
    fun `newer inbox projection supersedes prior snapshot`() {
        assertTrue(shouldApplyInboxProjection(previousSeq = 12, incomingSeq = 13))
    }

    @Test
    fun `late older baseline cannot erase live queued items`() {
        assertFalse(shouldApplyInboxProjection(previousSeq = 13, incomingSeq = 12))
    }

    @Test
    fun `host switch or new generation accepts authoritative lower inbox baseline`() {
        val watermark = InboxProjectionWatermarks()
        assertTrue(watermark.accept("s", 100))
        assertFalse(watermark.accept("s", 20))
        watermark.reset()
        assertTrue(watermark.accept("s", 20))
        assertFalse(watermark.accept("s", 19))
    }

    @Test
    fun `initial and legacy unwatermarked queue snapshots remain accepted`() {
        assertTrue(shouldApplyInboxProjection(previousSeq = null, incomingSeq = 12))
        assertTrue(shouldApplyInboxProjection(previousSeq = null, incomingSeq = null))
        assertTrue(shouldApplyInboxProjection(previousSeq = 12, incomingSeq = null))
    }
}
