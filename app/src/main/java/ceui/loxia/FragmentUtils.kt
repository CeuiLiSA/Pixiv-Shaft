package ceui.loxia

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import ceui.lisa.activities.Shaft
import com.blankj.utilcode.util.Utils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

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

fun Context.openChromeTab(url: String) {
    try {
        val uri = url.toUri()
        val builder = CustomTabsIntent.Builder()
        val customTabsIntent = builder.build()
        customTabsIntent.launchUrl(this, uri)
    } catch (e: Exception) {
        // fallback 使用默认浏览器打开
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    }
}


fun findLanguageBySystem(): String {
    val LANGUAGE_MAP = mapOf(
        "简体中文" to "zh-CN",
        "日本語" to "ja",
        "English" to "en",
        "繁體中文" to "zh-TW",
        "русский" to "ru",
        "한국어" to "ko"
    )

    val inSettings = Shaft.sSettings.appLanguage
    if (inSettings?.isNotEmpty() == true && inSettings != "undefined") return inSettings

    val locale = Locale.getDefault()
    val languageTag = locale.toLanguageTag() // 例如 zh-CN, ja-JP, en-US

    // 先尝试全匹配
    LANGUAGE_MAP.entries.firstOrNull {
        it.value.equals(languageTag, ignoreCase = true)
    }?.let {
        return it.key
    }

    // 再尝试只匹配语言部分 (zh, ja, en...)
    LANGUAGE_MAP.entries.firstOrNull {
        it.value.substringBefore('-').equals(locale.language, ignoreCase = true)
    }?.let {
        return it.key
    }

    // 如果都匹配不上，默认返回 English
    return "English"
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
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            context.startActivity(launchIntent)
        } else {
            // 未安装，跳转 VPN 设置
            openVpnSettings(context)
        }
    } catch (e: Exception) {
        // 启动失败，跳转 VPN 设置
        openVpnSettings(context)
    }
}

private fun openVpnSettings(context: Context) {
    try {
        val intent = Intent("android.net.vpn.SETTINGS")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        // 如果部分 ROM 不支持 android.net.vpn.SETTINGS，就退回到通用设置
        val intent = Intent(Settings.ACTION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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