package ceui.pixiv.nana7mi.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MembershipPolicyTest {

    @Test
    fun `attribute accepts explicit premium only for matching positive uid`() {
        val uid = 31660292L
        assertEquals(true, MembershipPolicy.attribute(reportedUid = uid, expectedUid = uid, reportedIsPremium = true))
        assertEquals(false, MembershipPolicy.attribute(reportedUid = uid, expectedUid = uid, reportedIsPremium = false))
        assertNull(MembershipPolicy.attribute(reportedUid = uid, expectedUid = uid, reportedIsPremium = null))
    }

    @Test
    fun `attribute rejects mismatched, absent, or non-positive uids`() {
        val uid = 31660292L
        assertNull(MembershipPolicy.attribute(reportedUid = 999L, expectedUid = uid, reportedIsPremium = false))
        assertNull(MembershipPolicy.attribute(reportedUid = 0L, expectedUid = uid, reportedIsPremium = false))
        assertNull(MembershipPolicy.attribute(reportedUid = null, expectedUid = uid, reportedIsPremium = false))
        // Both 0 must NOT match when expectedUid is not a valid positive uid.
        assertNull(MembershipPolicy.attribute(reportedUid = 0L, expectedUid = 0L, reportedIsPremium = false))
        assertNull(MembershipPolicy.attribute(reportedUid = -1L, expectedUid = -1L, reportedIsPremium = false))
    }

    @Test
    fun `merge overwrites stored membership only when attributed signal is non-null`() {
        assertEquals(true, MembershipPolicy.merge(stored = true, attributedPremium = null))
        assertEquals(false, MembershipPolicy.merge(stored = false, attributedPremium = null))
        assertNull(MembershipPolicy.merge(stored = null, attributedPremium = null))
        assertEquals(false, MembershipPolicy.merge(stored = true, attributedPremium = false))
        assertEquals(true, MembershipPolicy.merge(stored = false, attributedPremium = true))
        assertEquals(true, MembershipPolicy.merge(stored = null, attributedPremium = true))
    }
}
