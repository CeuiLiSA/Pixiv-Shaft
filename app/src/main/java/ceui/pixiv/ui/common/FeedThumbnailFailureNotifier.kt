package ceui.pixiv.ui.common

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.banner.BannerAction
import ceui.pixiv.banner.BannerCategory
import ceui.pixiv.banner.BannerPriority
import ceui.pixiv.banner.BannerRequest
import ceui.pixiv.banner.host.InAppBanners
import ceui.pixiv.services.appServices
import ceui.pixiv.utils.INetworkState
import ceui.pixiv.utils.NetworkStateManager
import timber.log.Timber
import java.lang.ref.WeakReference
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
    private var pendingList: WeakReference<RecyclerView>? = null

    private val networkObserved = AtomicBoolean(false)
    private var lastNetworkType: NetworkStateManager.NetworkType? = null
    private var networkSource: INetworkState? = null
    private val networkObserver = Observer<NetworkStateManager.NetworkType>(::onNetworkChanged)

    /**
     * Registers network state listener to reset [hasPopped] on network change.
     */
    fun init(networkStateManager: INetworkState) {
        if (!networkObserved.compareAndSet(false, true)) return
        networkSource = networkStateManager
        networkStateManager.networkState.observeForever(networkObserver)
    }

    private fun onNetworkChanged(type: NetworkStateManager.NetworkType) {
        val prev = lastNetworkType
        lastNetworkType = type
        // Skip initial sticky emission (prev == null).
        if (prev != null && prev != type) {
            Timber.tag(TAG).i("Network changed (%s -> %s), resetting thumbnail failure banner state", prev, type)
            hasPopped = false
            pendingList = null
        }
    }

    /**
     * Called when [InAppBanners] becomes ready. If a trigger was pending, fires it now.
     */
    fun onBannerReady() {
        val list = pendingList?.get()
        pendingList = null
        // The user may have left, or the images may have recovered during bootstrap.
        list?.let(::checkAndNotify)
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

    /** Recheck after layout, including failures delivered synchronously before holder attachment. */
    fun checkAfterLayout(card: View) {
        if (hasPopped) return
        card.post {
            if (card.isAttachedToWindow) {
                findParentRecyclerView(card)?.let(::checkAndNotify)
            }
        }
    }

    fun onThumbnailFailed(card: View) {
        card.setTag(R.id.tag_feed_thumbnail_load_failed, true)
        checkAfterLayout(card)
    }

    private fun outermostList(list: RecyclerView): RecyclerView {
        var outer = list
        while (true) {
            outer = findParentRecyclerView(outer) ?: return outer
        }
    }

    private fun isActive(view: View): Boolean = view.isAttachedToWindow && view.isShown &&
        view.findViewTreeLifecycleOwner()?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.STARTED) != false

    private fun watchVisibility(list: RecyclerView) {
        if (list.getTag(R.id.tag_feed_thumbnail_visibility_observer) != null) return
        val observer = VisibilityObserver(list)
        list.setTag(R.id.tag_feed_thumbnail_visibility_observer, observer)
        list.addOnAttachStateChangeListener(observer)
        if (list.isAttachedToWindow) observer.onViewAttachedToWindow(list)
    }

    /** Page switches and enclosing scroll containers can reveal cards without scrolling this list. */
    private class VisibilityObserver(private val list: RecyclerView) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener,
        ViewTreeObserver.OnGlobalLayoutListener, LifecycleEventObserver {
        private val lastBounds = Rect()
        private val bounds = Rect()
        private var lifecycle: Lifecycle? = null

        override fun onViewAttachedToWindow(v: View) {
            lastBounds.setEmpty()
            v.viewTreeObserver.addOnPreDrawListener(this)
            v.viewTreeObserver.addOnGlobalLayoutListener(this)
            lifecycle = v.findViewTreeLifecycleOwner()?.lifecycle
            lifecycle?.addObserver(this)
        }

        override fun onViewDetachedFromWindow(v: View) {
            // During detach this is still the window's observer; remove before mAttachInfo clears.
            v.viewTreeObserver.removeOnPreDrawListener(this)
            v.viewTreeObserver.removeOnGlobalLayoutListener(this)
            lifecycle?.removeObserver(this)
            lifecycle = null
            lastBounds.setEmpty()
        }

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
                lastBounds.setEmpty()
            }
        }

        override fun onGlobalLayout() {
            // Filtering/removing cards can change the ratio while this list's bounds stay fixed.
            lastBounds.setEmpty()
        }

        override fun onPreDraw(): Boolean {
            if (hasPopped) return true
            // Local bounds also change when an enclosing scroller moves this list while its
            // clipped rectangle on the screen remains identical.
            if (!isActive(list) || !list.getLocalVisibleRect(bounds)) {
                lastBounds.setEmpty()
            } else if (bounds != lastBounds) {
                lastBounds.set(bounds)
                checkAndNotify(list)
            }
            return true
        }
    }

    /** Count artwork roots once; descend into header rails, but never into a timeline card's grid. */
    @VisibleForTesting
    internal fun visibleFailureCounts(recyclerView: RecyclerView): Pair<Int, Int> {
        val bounds = Rect()
        var visible = 0
        var failed = 0
        fun visit(view: View) {
            if (!isActive(view) || !view.getGlobalVisibleRect(bounds)) return
            val failure = view.getTag(R.id.tag_feed_thumbnail_load_failed) as? Boolean
            if (failure != null) {
                visible++
                if (failure) failed++
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(outermostList(recyclerView))
        return visible to failed
    }

    /**
     * Inspects visible artwork cards in [recyclerView] and pops banner if failure threshold is reached.
     */
    fun checkAndNotify(recyclerView: RecyclerView) {
        if (hasPopped) return

        val list = outermostList(recyclerView)
        watchVisibility(list)
        val context = list.context
        if (!networkObserved.get()) {
            runCatching {
                context.appServices().networkStateManager.let(::init)
            }
        }

        val (visibleCards, failedCards) = visibleFailureCounts(list)

        Timber.tag(TAG).d("checkAndNotify: visibleCards=%d, failedCards=%d, hasPopped=%s", visibleCards, failedCards, hasPopped)

        if (shouldTrigger(visibleCards, failedCards)) {
            if (InAppBanners.isReady()) triggerBanner(context)
            else pendingList = WeakReference(list)
        } else if (pendingList?.get() === list) {
            pendingList = null
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

        hasPopped = true
        pendingList = null
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
        networkSource?.networkState?.removeObserver(networkObserver)
        networkSource = null
        hasPopped = false
        pendingList = null
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
    fun isPendingTrigger(): Boolean = pendingList?.get() != null

    @VisibleForTesting
    fun notifyNetworkChangedForTesting(type: NetworkStateManager.NetworkType) {
        onNetworkChanged(type)
    }
}
