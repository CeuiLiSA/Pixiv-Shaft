package ceui.pixiv.applock

import androidx.annotation.StringRes

/** 离开 App 多久后，再回来需要重新验证。 */
public enum class RelockTimeout(
    internal val millis: Long,
    @StringRes public val labelRes: Int,
) {
    IMMEDIATELY(0L, R.string.applock_timeout_immediately),
    AFTER_1_MINUTE(60_000L, R.string.applock_timeout_1_minute),
    AFTER_5_MINUTES(5 * 60_000L, R.string.applock_timeout_5_minutes),
    AFTER_15_MINUTES(15 * 60_000L, R.string.applock_timeout_15_minutes),
    ;

    internal companion object {
        val DEFAULT: RelockTimeout = IMMEDIATELY

        fun fromName(name: String?): RelockTimeout =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
