package ceui.loxia

import android.content.Context
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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

fun String.isJson(): Boolean {
    return try {
        JSONObject(this)
        true
    } catch (e1: JSONException) {
        try {
            JSONArray(this)
            true
        } catch (e2: JSONException) {
            false
        }
    }
}

fun Fragment.hideKeyboard() {
    val dialogFragment = findAncestor<DialogFragment>()
    if (dialogFragment != null) {
        context?.hideKeyboard(dialogFragment.dialog?.window)
    } else {
        context?.hideKeyboard(activity?.window)
    }
}

inline fun <reified T : Fragment> Fragment.findAncestor(): T? {
    var itr = this.parentFragment
    while (itr != null) {
        if (itr is T) {
            return itr
        }
        itr = itr.parentFragment
    }
    return null
}