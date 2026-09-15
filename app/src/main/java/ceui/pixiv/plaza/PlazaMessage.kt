package ceui.pixiv.plaza

import android.content.Context
import androidx.annotation.StringRes

/** Resolve at the UI boundary so retained ViewModels follow the current app language. */
internal class PlazaMessage(@StringRes val resourceId: Int, val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String = context.getString(resourceId, *args.toTypedArray())
}

internal class PlazaFailure(val userMessage: PlazaMessage) : Exception()
