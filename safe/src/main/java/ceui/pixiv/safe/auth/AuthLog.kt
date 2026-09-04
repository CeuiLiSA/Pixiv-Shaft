package ceui.pixiv.safe.auth

import android.util.Log

/** One searchable, token-safe Logcat stream for the complete first-party auth flow. */
public object AuthLog {
    public const val TAG: String = "SafeAuth"

    public fun debug(message: String) {
        Log.d(TAG, message)
    }

    public fun warning(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    public fun error(message: String, error: Throwable? = null) {
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
    }
}
