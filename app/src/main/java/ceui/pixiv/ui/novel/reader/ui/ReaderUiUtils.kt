package ceui.pixiv.ui.novel.reader.ui

import android.util.TypedValue
import android.view.View

/**
 * Apply the current theme's `selectableItemBackground` (ripple) to the view.
 *
 * `android.R.attr.selectableItemBackground` is an attribute reference, not a
 * drawable ID — passing it to [View.setBackgroundResource] directly crashes
 * with `Resources$NotFoundException`. Resolve via the theme first.
 */
internal fun View.applySelectableBackground() {
    val tv = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    if (tv.resourceId != 0) {
        setBackgroundResource(tv.resourceId)
    }
}
