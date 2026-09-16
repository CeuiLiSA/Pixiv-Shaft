package ceui.pixiv.sticker

import android.util.Log
import java.util.Locale

/** Operational state logs are available in release builds as well as debug. */
internal object StickerLog {
    const val TAG = "Sticker-System"
    fun i(message: String, vararg args: Any?) { Log.i(TAG, format(message, args)) }
    fun d(message: String, vararg args: Any?) { Log.d(TAG, format(message, args)) }
    fun w(error: Throwable, message: String, vararg args: Any?) { Log.w(TAG, format(message, args), error) }
    fun e(error: Throwable, message: String, vararg args: Any?) { Log.e(TAG, format(message, args), error) }
    private fun format(message: String, args: Array<out Any?>) = if (args.isEmpty()) message else String.format(Locale.ROOT, message, *args)
}
