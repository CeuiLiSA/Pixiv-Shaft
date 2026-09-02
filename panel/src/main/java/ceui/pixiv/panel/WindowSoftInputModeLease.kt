package ceui.pixiv.panel

import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import java.util.WeakHashMap

/**
 * Reference-counted Window policy lease for panel hosts sharing the same Activity.
 *
 * The first holder snapshots the complete soft-input mode, all holders share `adjustResize`, and
 * the final release restores the snapshot. This prevents overlapping resumed Fragments from
 * restoring each other's Window policy out of order.
 */
object WindowSoftInputModeLease {

    private data class Entry(
        val originalMode: Int,
        var holders: Int,
    )

    private val entries = WeakHashMap<Window, Entry>()

    @MainThread
    fun acquireAdjustResize(window: Window): Handle {
        val entry = entries[window]
        if (entry != null) {
            entry.holders++
        } else {
            val originalMode = window.attributes.softInputMode
            entries[window] = Entry(originalMode, holders = 1)
            val resizeMode =
                (originalMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            window.setSoftInputMode(resizeMode)
        }
        return Handle(window)
    }

    class Handle(private var window: Window?) {
        @MainThread
        fun release() {
            val acquiredWindow = window ?: return
            window = null
            val entry = entries[acquiredWindow] ?: return
            entry.holders--
            if (entry.holders == 0) {
                entries.remove(acquiredWindow)
                acquiredWindow.setSoftInputMode(entry.originalMode)
            }
        }
    }
}
