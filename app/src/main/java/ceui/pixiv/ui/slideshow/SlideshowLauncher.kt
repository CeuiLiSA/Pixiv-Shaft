package ceui.pixiv.ui.slideshow

import android.app.Activity
import android.content.Context
import android.content.Intent
import ceui.loxia.Illust
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
        val urls = ArrayList<String>(list.size)
        val titles = ArrayList<String>(list.size)
        var startUrlIndex = 0
        var seenStart = false
        list.forEachIndexed { i, illust ->
            val baseTitle = illust.title.orEmpty()
            val pages = pagesOf(illust)
            for ((p, url) in pages.withIndex()) {
                if (url.isEmpty()) continue
                if (i == startListIndex && !seenStart) {
                    startUrlIndex = urls.size
                    seenStart = true
                }
                urls.add(url)
                titles.add(if (pages.size > 1) "$baseTitle (${p + 1})" else baseTitle)
            }
        }
        if (urls.isEmpty()) {
            Common.showToast(context.getString(ceui.lisa.R.string.slideshow_empty))
            return
        }
        startSession(context, urls, titles, startUrlIndex, random)
    }

    /** Prefer ORIGINAL; fall back to LARGE only if the original variant is missing. */
    private fun pagesOf(illust: Illust): List<String> {
        if (illust.page_count <= 0) return emptyList()
        return if (illust.page_count == 1) {
            listOfNotNull(
                illust.meta_single_page?.original_image_url
                    ?: illust.image_urls?.original
                    ?: illust.image_urls?.large
            )
        } else {
            illust.meta_pages.orEmpty().mapNotNull { mp ->
                mp.image_urls?.original ?: mp.image_urls?.large
            }
        }
    }

    private fun startSession(
        context: Context,
        urls: List<String>,
        titles: List<String>,
        startIndex: Int,
        random: Boolean,
    ) {
        val sessionId = SlideshowStore.put(
            SlideshowStore.Session(
                urls = urls,
                titles = titles,
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
