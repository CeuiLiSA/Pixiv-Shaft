package ceui.loxia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.Utils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

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


fun stableHash(input: String): Int {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    // 使用前两个字节生成 0 ~ 65535 的正整数，然后取模 10000
    val hashInt = ((hashBytes[0].toInt() and 0xFF) shl 8) or (hashBytes[1].toInt() and 0xFF)
    val ret = hashInt % 10000
    Timber.d("sadasdsw2 ${ret}")
    return ret
}


fun openClashApp(context: Context) {
    val packageName = "com.github.kr328.clash"
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
        context.startActivity(launchIntent)
    } else {
        Toast.makeText(context, "未安装 Clash", Toast.LENGTH_SHORT).show()
    }
}

fun Fragment.showKeyboard(editText: EditText?) {
    context?.showKeyboard(editText)
}

fun Window.applyGrayMode(enabled: Boolean) {
    val matrix = ColorMatrix().apply { setSaturation(if (enabled) 0f else 1f) }
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(matrix)
    }
    decorView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
}

fun copyBitmapToImageCacheFolder(bitmap: Bitmap, fileName: String): Uri? {
    return try {
        // 创建缓存目录：<cache>/images
        val cachePath = File(Utils.getApp().externalCacheDir, "images")
        cachePath.mkdirs()

        // 创建文件并写入 Bitmap
        val file = File(cachePath, fileName)
        FileOutputStream(file).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        }

        // 返回 FileProvider Uri
        FileProvider.getUriForFile(
            Utils.getApp(),
            "ceui.lisa.pixiv.provider",
            file
        )
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}

fun copyImageFileToCacheFolder(originalFile: File, fileName: String): Uri? {
    return try {
        // 创建缓存目录：<cache>/images
        val cachePath = File(Utils.getApp().externalCacheDir, "images").apply { mkdirs() }

        // 创建目标文件
        val targetFile = File(cachePath, fileName)

        // 拷贝文件内容
        originalFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // 返回 FileProvider Uri
        FileProvider.getUriForFile(
            Utils.getApp(),
            "ceui.lisa.pixiv.provider",
            targetFile
        )
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
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