package ceui.pixiv.ui.detail

import android.content.Intent
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.SheetComicThumbsBinding
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.widgets.PageThumbsSheet
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.pillButton
import ceui.pixiv.witstudio.theme.v3Font
import com.google.android.flexbox.FlexboxLayout
import java.io.File

/**
 * 详情页阅读胶囊**长按**弹出的多图预览(#1085)。web 端那枚页码按钮是点击预览的,这里改成长按:
 * 胶囊上「收起」那一段本来就吃单击,再叠一层点击必然互抢(见 issue 里报告人的建议)。
 *
 * 模型走 activity 作用域的 [ArtworkThumbsProvider] 交接,不塞 arguments —— 200 页的 URL 走
 * Bundle 会 TransactionTooLargeException(与 #820 同类问题,漫画阅读器的
 * [ceui.pixiv.ui.comic.reader.ComicReaderPagesProvider] 也是这个理由)。
 *
 * 选页结果回宿主走 fragment result 而不是 lambda:sheet 跨横屏会重建,回调必然失效(#1023)。
 */
class ArtworkThumbsProvider : ViewModel() {
    var models: List<Any?> = emptyList()
    var currentIndex: Int = 0
}

class ArtworkThumbsSheet : PageThumbsSheet() {

    private val provider by activityViewModels<ArtworkThumbsProvider>()

    override fun thumbModels(): List<Any?> = provider.models

    override fun currentIndex(): Int = provider.currentIndex

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val illustId = arguments?.getLong(KEY_READER_ILLUST_ID, 0L) ?: 0L
        if (illustId <= 0L || provider.models.isEmpty()) return
        // 作品 ID 放 arguments，旋屏重建后仍可进入阅读器；快照没有此参数，保留本地预览。
        val button = requireContext().pillButton(
            getString(R.string.comic_reader_enter_illust),
            primary = false,
            icon = R.drawable.ic_baseline_menu_book_24,
        ) {
            startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMIC_READER.key)
                putExtra(Params.ILLUST_ID, illustId)
            })
            dismiss()
        }.apply {
            textSize = 13f
            typeface = context.v3Font(500)
            lineHeightRatio(1.4f)
            // 可见胶囊约 32dp，透明上下沿保留 48dp 热区，避免次级入口抢过标题。
            background = InsetDrawable(background, 0, context.dp(8), 0, context.dp(8))
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            compoundDrawablePadding = context.dp(6)
            compoundDrawablesRelative[0]?.let { icon ->
                icon.setBounds(0, 0, context.dp(16), context.dp(16))
                setCompoundDrawablesRelative(icon, null, null, null)
            }
        }
        SheetComicThumbsBinding.bind(view).comicThumbsHeader.addView(
            button,
            FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { flexShrink = 1f },
        )
    }

    override fun onPagePicked(index: Int) {
        setFragmentResult(REQUEST_KEY, bundleOf(KEY_PAGE_INDEX to index))
    }

    companion object {
        const val TAG = "ArtworkThumbsSheet"
        const val REQUEST_KEY = "artwork_thumbs_pick"
        const val KEY_PAGE_INDEX = "page_index"
        private const val KEY_READER_ILLUST_ID = "reader_illust_id"

        /**
         * 宿主详情页调这个:先把模型交给 provider,再拉起 sheet。单图 / 拿不到页面时不弹。
         * readerIllustId 仅用于普通作品；快照传 null，不能跳到只认联网作品的阅读器。
         * 已经弹着一张就不再叠(长按可能连发)。
         */
        fun show(
            host: Fragment,
            models: List<Any?>,
            currentIndex: Int,
            readerIllustId: Long? = null,
        ): Boolean {
            if (models.size <= 1) return false
            val fm = host.childFragmentManager
            if (fm.findFragmentByTag(TAG) != null) return false
            ViewModelProvider(host.requireActivity())[ArtworkThumbsProvider::class.java].also {
                it.models = models
                it.currentIndex = currentIndex
            }
            ArtworkThumbsSheet().apply {
                arguments = bundleOf(KEY_READER_ILLUST_ID to (readerIllustId ?: 0L))
            }.show(fm, TAG)
            return true
        }

        /**
         * 每页一个缩略图模型,顺序即页序,取不到的那页给 null(Glide 吃 null 只是画不出图,
         * 保住索引对齐 —— 少一个元素会让后面所有页都点错)。
         *
         * 取 medium 而不是详情页正在用的 large:这是一张三/五列的网格,large 在折叠态(3P+ 未展开)
         * 下等于把整篇作品按原尺寸拉一遍。
         */
        fun networkModels(illust: Illust): List<Any?> {
            val total = illust.page_count.coerceAtLeast(1)
            return (0 until total).map { index ->
                IllustDownload.getUrl(illust, index, Params.IMAGE_RESOLUTION_MEDIUM)
                    ?.let { GlideUrlChild(it) }
            }
        }

        /** 快照(离线归档)页:缩略图直读本地文件,不要回网上取。 */
        fun localModels(pageCount: Int, pageFile: (Int) -> File?): List<Any?> =
            (0 until pageCount.coerceAtLeast(1)).map { index -> pageFile(index)?.let(Uri::fromFile) }
    }
}
