package ceui.pixiv.ui.common

import android.content.Context
import android.view.View
import android.view.ViewParent
import androidx.annotation.VisibleForTesting
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.banner.BannerAction
import ceui.pixiv.banner.BannerCategory
import ceui.pixiv.banner.BannerPriority
import ceui.pixiv.banner.BannerRequest
import ceui.pixiv.banner.host.InAppBanners
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.services.appServices
import ceui.pixiv.utils.NetworkStateManager
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds artwork card thumbnail failure detector and banner trigger.
 *
 * Rules:
 * - Pops once per process ([hasPopped]).
 * - Resets only when network changes ([NetworkStateManager.networkState]).
 * - Trigger threshold: visible artwork cards > 0 and failed / visible >= 2/3.
 * - Pops banner with action button navigating to network test (shaft://network-test).
 */
object FeedThumbnailFailureNotifier {

    private const val TAG = "FeedThumbFailure"
    private const val BANNER_ID = "feed-thumbnail-load-failed"
    const val DEEP_LINK = "shaft://network-test"
    private const val BANNER_AUTO_DISMISS_MS = 10_000L

    @Volatile
    private var hasPopped = false

    @Volatile
    private var pendingTrigger = false

    private val networkObserved = AtomicBoolean(false)
    private var lastNetworkType: NetworkStateManager.NetworkType? = null

    /**
     * Registers network state listener to reset [hasPopped] on network change.
     */
    fun init(networkStateManager: NetworkStateManager) {
        if (!networkObserved.compareAndSet(false, true)) return
        networkStateManager.networkState.observeForever { type ->
            val prev = lastNetworkType
            lastNetworkType = type
            // Skip initial sticky emission (prev == null)
            if (prev != null && prev != type) {
                Timber.tag(TAG).i("Network changed (%s -> %s), resetting thumbnail failure banner state", prev, type)
                hasPopped = false
                pendingTrigger = false
            }
        }
    }

    /**
     * Called when [InAppBanners] becomes ready. If a trigger was pending, fires it now.
     */
    fun onBannerReady(context: Context) {
        Timber.tag(TAG).d("onBannerReady: pendingTrigger=%s, hasPopped=%s", pendingTrigger, hasPopped)
        if (pendingTrigger && !hasPopped) {
            triggerBanner(context)
        }
    }

    fun canPop(): Boolean = !hasPopped

    /**
     * Finds the nearest enclosing [RecyclerView] for [view].
     */
    fun findParentRecyclerView(view: View): RecyclerView? {
        var p: ViewParent? = view.parent
        while (p != null) {
            if (p is RecyclerView) return p
            p = p.parent
        }
        return null
    }

    /**
     * Inspects visible artwork cards in [recyclerView] and pops banner if failure threshold is reached.
     */
    fun checkAndNotify(recyclerView: RecyclerView) {
        if (hasPopped) return

        val context = recyclerView.context
        if (!networkObserved.get()) {
            runCatching {
                context.appServices().networkStateManager.let(::init)
            }
        }

        val childCount = recyclerView.childCount
        if (childCount == 0) return

        var visibleCards = 0
        var failedCards = 0

        for (i in 0 until childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child)

            // Artwork card with thumbnail check: either holder has IllustFeedItem or view has illust_image
            val isArtworkCard = (holder as? FeedCell<*, *>)?.itemOrNull is IllustFeedItem ||
                    child.findViewById<View>(R.id.illust_image) != null
            if (!isArtworkCard) continue

            // Check if vertically visible in recyclerView viewport
            if (child.bottom <= 0 || child.top >= recyclerView.height) {
                continue
            }

            visibleCards++
            val thumbView = child.findViewById<View>(R.id.illust_image)
            val isFailed = child.getTag(R.id.tag_feed_thumbnail_load_failed) == true ||
                    thumbView?.getTag(R.id.tag_feed_thumbnail_load_failed) == true
            if (isFailed) {
                failedCards++
            }
        }

        Timber.tag(TAG).d("checkAndNotify: visibleCards=%d, failedCards=%d, hasPopped=%s", visibleCards, failedCards, hasPopped)

        if (shouldTrigger(visibleCards, failedCards)) {
            triggerBanner(context)
        }
    }

    /**
     * Trigger rule: visible cards > 0 and failed / visible >= 2/3.
     */
    fun shouldTrigger(visibleCards: Int, failedCards: Int): Boolean {
        return visibleCards > 0 && failedCards > 0 && (failedCards * 3 >= visibleCards * 2)
    }

    private fun triggerBanner(context: Context) {
        if (hasPopped) return

        if (!InAppBanners.isReady()) {
            Timber.tag(TAG).w("InAppBanners is not ready yet, mark pendingTrigger = true")
            pendingTrigger = true
            return
        }

        hasPopped = true
        pendingTrigger = false
        Timber.tag(TAG).i("Thumbnail load failure reached 2/3 on screen, popping network test banner")
        runCatching {
            InAppBanners.manager.enqueue(
                BannerRequest.Text(
                    id = BANNER_ID,
                    title = context.getString(R.string.feed_thumbnail_failure_banner_title),
                    action = BannerAction(
                        label = context.getString(R.string.nav_network_test_entry),
                        deepLink = DEEP_LINK,
                    ),
                    dedupKey = BANNER_ID,
                    priority = BannerPriority.HIGH,
                    category = BannerCategory.System,
                    autoDismissMillis = BANNER_AUTO_DISMISS_MS,
                    deepLink = DEEP_LINK,
                )
            )
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to enqueue feed thumbnail failure banner")
            hasPopped = false // Allow retry on failure
        }
    }

    @VisibleForTesting
    fun resetForTesting() {
        hasPopped = false
        pendingTrigger = false
        lastNetworkType = null
        networkObserved.set(false)
    }

    @VisibleForTesting
    fun setPoppedForTesting(popped: Boolean) {
        hasPopped = popped
    }

    @VisibleForTesting
    fun isPopped(): Boolean = hasPopped

    @VisibleForTesting
    fun isPendingTrigger(): Boolean = pendingTrigger

    @VisibleForTesting
    fun notifyNetworkChangedForTesting(type: NetworkStateManager.NetworkType) {
        val prev = lastNetworkType
        lastNetworkType = type
        if (prev != null && prev != type) {
            hasPopped = false
            pendingTrigger = false
        }
    }
}
