package ceui.pixiv.ui.slideshow

import android.app.Activity
import android.content.Context
import android.content.Intent
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.Common
import timber.log.Timber

object SlideshowLauncher {

    /**
     * Launch the slideshow from a list of [Illust]. Single-page illusts contribute their one image;
     * multi-page illusts contribute every page in order (ORIGINAL, falling back to LARGE).
     */
    @JvmStatic
    @JvmOverloads
    fun launchFromIllusts(
        context: Context,
        list: List<Illust>,
        startListIndex: Int,
        random: Boolean = true,
    ) {
        val slides = ArrayList<SlideshowStore.Slide>(list.size)
        var startUrlIndex = 0
        var seenStart = false
        list.forEachIndexed { i, illust ->
            val baseTitle = illust.title.orEmpty()
            val pages = pagesOf(illust)
            for ((page, url) in pages) {
                if (i == startListIndex && !seenStart) {
                    startUrlIndex = slides.size
                    seenStart = true
                }
                slides.add(
                    SlideshowStore.Slide(
                        url = url,
                        title = if (pages.size > 1) "$baseTitle (${page + 1})" else baseTitle,
                        illust = illust,
                        page = page,
                    ),
                )
            }
        }
        if (slides.isEmpty()) {
            Common.showToast(context.getString(ceui.lisa.R.string.slideshow_empty))
            return
        }
        startSession(context, slides, startUrlIndex, random)
    }

    /**
     * Prefer ORIGINAL; fall back to LARGE only if the original variant is missing.
     *
     * 返回 **(真实页码, url)**。不能先 `mapNotNull` 丢掉空页、再拿列表下标当页码 —— 中间任何一页
     * 缺 url 都会让后面的页码整体前移，放映时就会去查、去读**另一页**的本地文件。
     */
    private fun pagesOf(illust: Illust): List<Pair<Int, String>> {
        if (illust.page_count <= 0) return emptyList()
        return if (illust.page_count == 1) {
            val url = illust.meta_single_page?.original_image_url
                ?: illust.image_urls?.original
                ?: illust.image_urls?.large
            listOfNotNull(url?.takeIf { it.isNotEmpty() }?.let { 0 to it })
        } else {
            illust.meta_pages.orEmpty().mapIndexedNotNull { page, mp ->
                (mp.image_urls?.original ?: mp.image_urls?.large)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { page to it }
            }
        }
    }

    private fun startSession(
        context: Context,
        slides: List<SlideshowStore.Slide>,
        startIndex: Int,
        random: Boolean,
    ) {
        val sessionId = SlideshowStore.put(
            SlideshowStore.Session(
                slides = slides,
                startIndex = startIndex,
                random = random,
            )
        )
        val intent = Intent(context, SlideshowActivity::class.java).apply {
            putExtra(SlideshowActivity.EXTRA_SESSION_ID, sessionId)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Activity callers stay in the current task. A non-Activity Context
        // needs NEW_TASK or Context.startActivity throws AndroidRuntimeException.
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            SlideshowStore.remove(sessionId)
            throw e
        }
    }
}
