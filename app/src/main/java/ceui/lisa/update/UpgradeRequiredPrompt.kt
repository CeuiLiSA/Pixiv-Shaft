package ceui.lisa.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.banner.BannerAction
import ceui.pixiv.banner.BannerCategory
import ceui.pixiv.banner.BannerDisplayPolicy
import ceui.pixiv.banner.BannerPriority
import ceui.pixiv.banner.BannerRequest
import ceui.pixiv.banner.host.InAppBanners
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Process-wide 426 entry point. All mutable state is confined to the main thread. */
object UpgradeRequiredPrompt {
    const val DEEP_LINK = "shaft://upgrade-required"
    private const val BANNER_ID = "client-upgrade-required"
    private const val COOLDOWN_MS = 30_000L
    private const val PLAY_PACKAGE = "ceui.pixiv.pshaft"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending = false
    private var minimumVersion: String? = null
    private var lastShownAt: Long? = null
    private var updateJob: Job? = null
    private var updateOwner: WeakReference<FragmentActivity>? = null

    fun notifyRequired(version: String?) {
        scope.launch {
            val last = lastShownAt
            if (last != null && SystemClock.elapsedRealtime() - last < COOLDOWN_MS) return@launch
            minimumVersion = version
            pending = true
            onForeground()
        }
    }

    /** Called on resume and after banner bootstrap, including a 426 during cold startup. */
    fun onForeground() {
        if (!pending || !InAppBanners.isReady()) return
        val activity = InAppBanners.currentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        InAppBanners.manager.enqueue(
            BannerRequest.Text(
                id = BANNER_ID,
                title = activity.getString(R.string.upgrade_required_title),
                message = minimumVersion?.let {
                    activity.getString(R.string.upgrade_required_version_message, it)
                } ?: activity.getString(R.string.upgrade_required_message),
                action = BannerAction(activity.getString(R.string.upgrade_required_action), DEEP_LINK),
                dedupKey = BANNER_ID,
                // A busy/sticky banner can hold this in the queue longer than the cooldown.
                policy = BannerDisplayPolicy.Replace,
                priority = BannerPriority.HIGH,
                category = BannerCategory.System,
                autoDismissMillis = 10_000L,
                deepLink = DEEP_LINK,
            )
        )
        pending = false
        lastShownAt = SystemClock.elapsedRealtime()
    }

    /** Only user taps launch the store or start the manual GitHub update check. */
    fun open(activity: FragmentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (BuildConfig.IS_LITE) {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PLAY_PACKAGE"))
                .setPackage("com.android.vending")
            try {
                activity.startActivity(market)
            } catch (_: ActivityNotFoundException) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$PLAY_PACKAGE")))
                } catch (_: ActivityNotFoundException) {
                    Common.showToast(R.string.upgrade_required_store_unavailable)
                }
            }
            return
        }
        if (activity.supportFragmentManager.findFragmentByTag("update_dialog") != null) return
        if (updateJob?.isActive == true && updateOwner?.get() === activity) return
        // A different screen can take over a check whose original Activity is stopped.
        // Cancel its pending delivery so returning to that screen cannot show a stale sheet.
        updateJob?.cancel()
        updateOwner = WeakReference(activity)
        Common.showToast(R.string.update_checking)
        updateJob = activity.lifecycleScope.launch {
            try {
                // Manual check deliberately ignores the daily interval and skipped versions.
                val result = AppUpdateChecker.checkForUpdate()
                // Keep a completed check while the user briefly backgrounds this Activity.
                // Destruction cancels the wait through lifecycleScope.
                activity.lifecycle.withResumed {
                    when (result) {
                        is AppUpdateChecker.UpdateResult.UpdateAvailable -> {
                            val fm = activity.supportFragmentManager
                            if (!fm.isStateSaved && fm.findFragmentByTag("update_dialog") == null) {
                                UpdateBottomSheet.newInstance(result.release).show(fm, "update_dialog")
                            }
                        }
                        is AppUpdateChecker.UpdateResult.NoUpdate ->
                            Common.showToast(R.string.upgrade_required_no_update)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.lifecycle.withResumed {
                    Common.showToast(if (AppUpdateChecker.isRateLimit(e)) {
                        AppUpdateChecker.getRateLimitMessage(activity, e)
                    } else {
                        activity.getString(R.string.update_check_failed)
                    })
                }
            }
        }
    }
}
