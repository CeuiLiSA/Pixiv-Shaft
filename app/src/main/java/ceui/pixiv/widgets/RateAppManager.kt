package ceui.pixiv.widgets

import ceui.lisa.activities.Shaft
import com.tencent.mmkv.MMKV

/**
 * Manages the timing and display logic for the Play Store rating dialog.
 *
 * Strategy:
 * - Wait at least [MIN_DAYS_BEFORE_PROMPT] days after first launch
 * - Wait at least [MIN_LAUNCHES_BEFORE_PROMPT] app launches
 * - After "Maybe Later": wait [DAYS_AFTER_LATER] more days
 * - After "Don't Ask Again" or successful rating: never show again
 */
object RateAppManager {

    private const val KEY_FIRST_LAUNCH_TIME = "rate_first_launch_time"
    private const val KEY_LAUNCH_COUNT = "rate_launch_count"
    private const val KEY_NEVER_ASK = "rate_never_ask"
    private const val KEY_RATED = "rate_already_rated"
    private const val KEY_LATER_TIMESTAMP = "rate_later_timestamp"

    private const val MIN_DAYS_BEFORE_PROMPT = 3
    private const val MIN_LAUNCHES_BEFORE_PROMPT = 5
    private const val DAYS_AFTER_LATER = 7

    private val mmkv: MMKV get() = Shaft.getMMKV()

    /**
     * Call this on every app launch to track engagement.
     */
    fun onAppLaunched() {
        if (mmkv.decodeLong(KEY_FIRST_LAUNCH_TIME, 0L) == 0L) {
            mmkv.encode(KEY_FIRST_LAUNCH_TIME, System.currentTimeMillis())
        }
        val count = mmkv.decodeInt(KEY_LAUNCH_COUNT, 0)
        mmkv.encode(KEY_LAUNCH_COUNT, count + 1)
    }

    /**
     * Returns true if we should show the rating dialog.
     */
    fun shouldShowRateDialog(): Boolean {
        if (mmkv.decodeBool(KEY_NEVER_ASK, false)) return false
        if (mmkv.decodeBool(KEY_RATED, false)) return false

        val firstLaunch = mmkv.decodeLong(KEY_FIRST_LAUNCH_TIME, 0L)
        if (firstLaunch == 0L) return false

        val daysSinceFirst = (System.currentTimeMillis() - firstLaunch) / (1000 * 60 * 60 * 24)
        val launchCount = mmkv.decodeInt(KEY_LAUNCH_COUNT, 0)

        if (daysSinceFirst < MIN_DAYS_BEFORE_PROMPT) return false
        if (launchCount < MIN_LAUNCHES_BEFORE_PROMPT) return false

        val laterTimestamp = mmkv.decodeLong(KEY_LATER_TIMESTAMP, 0L)
        if (laterTimestamp > 0L) {
            val daysSinceLater = (System.currentTimeMillis() - laterTimestamp) / (1000 * 60 * 60 * 24)
            if (daysSinceLater < DAYS_AFTER_LATER) return false
        }

        return true
    }

    fun onUserChoseLater() {
        mmkv.encode(KEY_LATER_TIMESTAMP, System.currentTimeMillis())
    }

    fun onUserChoseNever() {
        mmkv.encode(KEY_NEVER_ASK, true)
    }

    fun onUserRated() {
        mmkv.encode(KEY_RATED, true)
    }
}
