package ceui.pixiv.utils

import android.content.Context
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle

fun Context.showKeyboard(editText: EditText?) {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    editText?.requestFocus()
    imm?.showSoftInput(editText, InputMethodManager.HIDE_IMPLICIT_ONLY)
//    imm?.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, InputMethodManager.HIDE_IMPLICIT_ONLY)
}

fun Context.hideKeyboard(window: Window?) {
    if (window != null) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }
}

fun Fragment.showKeyboard(editText: EditText?) {
    context?.showKeyboard(editText)
}

fun Fragment.hideKeyboard() {
    var itr = parentFragment
    while (itr != null) {
        if (itr is DialogFragment) {
            context?.hideKeyboard(itr.dialog?.window)
            return
        }
        itr = itr.parentFragment
    }
    context?.hideKeyboard(activity?.window)
}

/**
 * 宿主 Activity 是否还在 RESUMED。
 *
 * 详情页用它分辨「本页被降级」和「宿主被盖住」：横滑到相邻作品时宿主一动不动，只有本页被
 * 降到 STARTED（见 ViewPager 的 setMaxLifecycle）；进二级大图页（半透明）或切后台时宿主自己
 * 先 paused —— FragmentActivity 的 ReportFragment 排在内容 fragment 前面，它的 onPause 把
 * Activity 的 lifecycle 落到 STARTED 之后，本页的 onPause 才跑，所以这里必然是 false。
 */
fun Fragment.isHostStillResumed(): Boolean =
    activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
