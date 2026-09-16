package ceui.pixiv.plaza.ui

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import ceui.lisa.R
import ceui.pixiv.plaza.PlazaApi
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.shaftapi.MediaUploadException
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class PlazaLocalizationTest {
    private fun context(locale: String): ContextThemeWrapper {
        val app = RuntimeEnvironment.getApplication()
        val config =
            Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            }
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme)
    }

    @Test
    fun `retained messages resolve against the current language and plural rules`() {
        val message = PlazaMessage(R.string.plaza_photo_limit, listOf(9))
        val locales = listOf("zh-CN", "en", "ja", "ko", "ru", "tr", "zh-TW")
        val close = listOf("关闭", "Close", "閉じる", "닫기", "Закрыть", "Kapat", "關閉")
        for ((i, locale) in locales.withIndex()) {
            val context = context(locale)
            assertEquals(close[i], context.getString(R.string.plaza_close))
            assertTrue(message.resolve(context).contains("9"))
            val error =
                plazaError(MediaUploadException(MediaUploadException.Reason.HTTP, 413))
                    .resolve(context)
            assertTrue(error.contains("413"))
            assertFalse(error.contains("%1"))
        }
        assertNotEquals(message.resolve(context("en")), message.resolve(context("zh-CN")))
        assertEquals(
            "1 reply ›",
            context("en").resources.getQuantityString(R.plurals.plaza_reply_count, 1, 1),
        )
        assertEquals(
            "2 replies ›",
            context("en").resources.getQuantityString(R.plurals.plaza_reply_count, 2, 2),
        )
        assertEquals(
            "2 ответа ›",
            context("ru").resources.getQuantityString(R.plurals.plaza_reply_count, 2, 2),
        )
        assertEquals(
            "5 ответов ›",
            context("ru").resources.getQuantityString(R.plurals.plaza_reply_count, 5, 5),
        )
        assertEquals(
            context("en").getString(R.string.plaza_generic_error),
            plazaError(IllegalArgumentException("internal raw error")).resolve(context("en")),
        )
    }

    @Test
    fun `an error alerts once while preserving retry state and a new failure alerts again`() {
        val api =
            java.lang.reflect.Proxy.newProxyInstance(
                PlazaApi::class.java.classLoader,
                arrayOf(PlazaApi::class.java),
            ) { _, _, _ ->
                error("Unexpected API call")
            } as PlazaApi
        val model = PlazaComposeViewModel(SavedStateHandle(), api, { 42L }, { "Author" })
        val uris = (1..10).map { android.net.Uri.parse("content://test/$it") }
        model.attach(uris)
        val first = model.takeErrorForAlert()
        assertNotNull(first)
        assertNull(model.takeErrorForAlert())
        assertSame(first, model.state.value.error)
        model.attach(uris)
        assertNotNull(model.takeErrorForAlert())
    }

    @Test
    fun `upload failure opens an actual WitStudio dialog with localized message`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val activity = controller.get()
        val error = plazaError(MediaUploadException(MediaUploadException.Reason.SIZE_UNKNOWN))
        activity.showPlazaError(error)
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog is ceui.pixiv.witstudio.dialog.WitDialog)
        assertTrue(dialog.isShowing)
        fun text(view: View): List<String> =
            (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                (if (view is ViewGroup)
                    (0 until view.childCount).flatMap { text(view.getChildAt(it)) }
                else emptyList())
        val labels = text(dialog.window!!.decorView)
        assertTrue(labels.contains(error.resolve(activity)))
        assertTrue(labels.contains(activity.getString(R.string.plaza_understood)))
        dialog.dismiss()
        controller.pause().stop().destroy()
    }
}
