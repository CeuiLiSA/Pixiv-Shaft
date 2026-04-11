package ceui.lisa.fragments

import ceui.lisa.models.UserPreviewsBean

/**
 * In-memory handoff for the recommended-user list that the Feed tab's
 * horizontal preview has already fetched, so that [FragmentRecmdUser]
 * can show the same set of users the user just saw on the Feed page —
 * without a second round trip, and without packing the multi-MB
 * UserPreviewsBean graph through Intent extras (which easily exceeds
 * the ~1MB binder transaction limit and crashes on Android 15, #820).
 *
 * Matches the [ceui.pixiv.ui.detail.ArtworksMap] pattern: producer
 * drops a snapshot under a unique key and passes the key via Intent,
 * consumer removes it once on the other side.
 */
object RecmdUserMap {
    @JvmField
    val store = hashMapOf<String, RecmdUserSnapshot>()
}

class RecmdUserSnapshot(
    @JvmField val items: List<UserPreviewsBean>,
    @JvmField val nextUrl: String?,
)
