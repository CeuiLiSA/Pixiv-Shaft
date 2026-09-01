package ceui.pixiv.utils

import android.content.Context
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment

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
