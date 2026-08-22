package ceui.pixiv.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.CellThemeColorBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx

/**
 * 「主题色彩」列表页（feeds 框架版，替代 legacy FragmentColors + ColorAdapter + ColorItem）。
 *
 * 入口在 [ceui.lisa.activities.TemplateActivity]（`EXTRA_FRAGMENT = "主题颜色"`），来源是
 * 设置 → 外观里的「主题色彩」行。
 *
 * 数据是 [ThemeColorCatalog] 那份静态目录，不碰网络也不碰 DB：单页、无翻页、无缓存
 * （本地优先没有意义，数据本来就编译在包里）。点一行即写 Settings 并重启进程。
 */
class ThemeColorFeedFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    /** 是否为「标签译文颜色」选择模式（#1047-5）：选中颜色只写标签译文设置，不改主题。 */
    private val selectTagTranslationColor by lazy {
        arguments?.getBoolean(ARG_SELECT_TAG_TRANSLATION_COLOR, false) ?: false
    }

    override val feedViewModel by feedViewModels<Int> {
        // 零捕获：先把 arguments 读成局部值，source 不碰 Fragment（约定见 feedViewModels 文档）。
        // 游标恒 null —— 十行就是全部，没有下一页。
        val selectTagTranslationColor =
            arguments?.getBoolean(ARG_SELECT_TAG_TRANSLATION_COLOR, false) ?: false
        FeedSource {
            FeedPage(
                if (selectTagTranslationColor) tagTranslationColorItems() else themeColorItems(),
                null
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(
            if (selectTagTranslationColor) R.string.tag_translation_color else R.string.string_324
        )
        childFragmentManager.setFragmentResultListener(
            CustomThemeColorSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString(CustomThemeColorSheet.KEY_HEX)?.let { hex ->
                if (selectTagTranslationColor) {
                    onPickTagTranslationCustomColor(hex)
                } else {
                    onPickCustomColor(hex)
                }
            }
        }
    }

    /**
     * 卡片间距归列表管，不写在 cell 的 layout_margin 上（旧版 recy_color 为了挂 margin 还多套了
     * 一层 RelativeLayout）。18dp 是本仓竖排列表的通行值（[ceui.pixiv.ui.common.setUpLayoutManager]
     * 的 ListMode.VERTICAL、置顶标签页都是它），比旧版那 6dp 松，卡片不再顶着屏幕边。
     */
    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(18.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(themeColorRenderer())
    }

    private fun themeColorRenderer() = feedRenderer<ThemeColorFeedItem, CellThemeColorBinding>(
        inflate = CellThemeColorBinding::inflate,
        create = { cell -> cell.binding.root.setOnClickListener {
            if (selectTagTranslationColor) {
                onPickTagTranslationColor(cell.item)
            } else {
                onPickColor(cell.item)
            }
        } },
    ) { cell ->
        val item = cell.item
        // 卡片本身就是色块；hex 全部来自目录里的字面量，parseColor 不会抛。
        cell.binding.root.setCardBackgroundColor(Color.parseColor(item.hex))
        cell.binding.name.text = if (item.selected) {
            getString(item.nameRes) + getString(R.string.theme_nowUsing)
        } else {
            getString(item.nameRes)
        }
        cell.binding.value.text = item.hex
    }

    /**
     * 选中即写盘 + 重启（对齐 legacy ColorAdapter.handleClick）：主题是 Activity 级的
     * `setTheme(AppTheme_IndexN)`，只能靠重进程整体换掉，没法就地重绘。
     *
     * 点当前这一行直接吞掉 —— 否则用户点一下自己正在用的颜色，App 会白重启一次。
     * 「自定义」那一行例外：它每次都要开 picker，用户就是来改色值的。
     */
    private fun onPickColor(item: ThemeColorFeedItem) {
        if (item.index == CustomThemeColor.INDEX) {
            CustomThemeColorSheet().show(childFragmentManager, "custom_theme_color")
            return
        }
        if (item.index == Shaft.sSettings.themeIndex) return
        Shaft.sSettings.themeIndex = item.index
        Local.setSettings(Shaft.sSettings)
        Common.restart()
        Common.showToast(getString(R.string.string_428), 2)
    }

    /** picker 回来的色值：写盘 + 切到自定义档 + 重启，规则与 [onPickColor] 一致。 */
    private fun onPickCustomColor(hex: String) {
        val alreadyUsing = Shaft.sSettings.themeIndex == CustomThemeColor.INDEX &&
                CustomThemeColor.normalize(Shaft.sSettings.customThemeColor) == hex
        if (alreadyUsing) return
        Shaft.sSettings.customThemeColor = hex
        Shaft.sSettings.themeIndex = CustomThemeColor.INDEX
        Local.setSettings(Shaft.sSettings)
        Common.restart()
        Common.showToast(getString(R.string.string_428), 2)
    }

    /**
     * 标签译文颜色模式：选预设只写标签译文设置，不改主题、不重启，选完直接返回设置页。
     */
    private fun onPickTagTranslationColor(item: ThemeColorFeedItem) {
        if (item.index == CustomThemeColor.INDEX) {
            CustomThemeColorSheet().show(childFragmentManager, "custom_theme_color")
            return
        }
        if (item.index == Shaft.sSettings.getTagTranslationColorIndex()) return
        Shaft.sSettings.setTagTranslationColorIndex(item.index)
        Local.setSettings(Shaft.sSettings)
        Common.showToast(getString(R.string.string_428), 2)
        requireActivity().finish()
    }

    /** 标签译文颜色自定义档：写盘后直接返回设置页，不需要重启。 */
    private fun onPickTagTranslationCustomColor(hex: String) {
        val alreadyUsing = Shaft.sSettings.getTagTranslationColorIndex() == CustomThemeColor.INDEX &&
                CustomThemeColor.normalize(Shaft.sSettings.getTagTranslationColorCustomHex()) == hex
        if (alreadyUsing) return
        Shaft.sSettings.setTagTranslationColorIndex(CustomThemeColor.INDEX)
        Shaft.sSettings.setTagTranslationColorCustomHex(hex)
        Local.setSettings(Shaft.sSettings)
        Common.showToast(getString(R.string.string_428), 2)
        requireActivity().finish()
    }

    companion object {
        /** 设置页从「从主题色彩页中选择」进入时置 true，本页变成标签译文颜色选择器。 */
        const val ARG_SELECT_TAG_TRANSLATION_COLOR = "select_tag_translation_color"

        @JvmStatic
        fun newInstance(selectTagTranslationColor: Boolean): ThemeColorFeedFragment =
            ThemeColorFeedFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_SELECT_TAG_TRANSLATION_COLOR, selectTagTranslationColor)
                }
            }
    }
}

/**
 * 一行主题色。[selected] 是「装配这一页那一刻的当前主题」的快照 —— 选中即重启进程，
 * 页面不存在「选完还留在原地看它换勾」的状态，不需要就地更新。
 */
data class ThemeColorFeedItem(
    val index: Int,
    @StringRes val nameRes: Int,
    val hex: String,
    val selected: Boolean,
) : FeedItem {

    override val feedKey: Any get() = index
}

/**
 * 顶层函数（非成员方法）：给 [ThemeColorFeedFragment.feedViewModel] 的 [FeedSource] lambda 用。
 * 写成成员方法会隐式捕获 Fragment 实例，而 FeedSource 被 VM 持有到页面最终销毁
 * （零捕获约定见 [ceui.pixiv.feeds.feedViewModels] 文档）。
 */
private fun themeColorItems(): List<FeedItem> {
    val current = Shaft.sSettings.themeIndex
    val presets = ThemeColorCatalog.entries.mapIndexed { index, entry ->
        ThemeColorFeedItem(index, entry.nameRes, entry.hex, index == current)
    }
    // 自定义档（issue #1014）排在十个预设之后。系统不支持时整行不出现 —— 理由见
    // [CustomThemeColor] 的类注释（API 30 以下没法把任意色值送进 ?attr/colorPrimary）。
    if (!CustomThemeColor.isSupported) return presets
    val customHex = CustomThemeColor.savedColor()?.let(CustomThemeColor::toHex)
        ?: ThemeColorCatalog.hexOf(current)
    return presets + ThemeColorFeedItem(
        index = CustomThemeColor.INDEX,
        nameRes = R.string.custom_theme_color_entry,
        hex = customHex,
        selected = current == CustomThemeColor.INDEX,
    )
}

/**
 * 标签译文颜色模式的列表数据：同样复用主题色目录和自定义档，但 selected 以标签译文设置为准。
 * 跟随主题（-2）时没有任何一行高亮 —— 该选项在设置页的弹窗里。
 */
private fun tagTranslationColorItems(): List<FeedItem> {
    val current = Shaft.sSettings.getTagTranslationColorIndex()
    val presets = ThemeColorCatalog.entries.mapIndexed { index, entry ->
        ThemeColorFeedItem(index, entry.nameRes, entry.hex, index == current)
    }
    if (!CustomThemeColor.isSupported) return presets
    val customHex = CustomThemeColor.normalize(Shaft.sSettings.getTagTranslationColorCustomHex())
        ?: ThemeColorCatalog.hexOf(current.takeIf { it in ThemeColorCatalog.entries.indices } ?: 0)
    return presets + ThemeColorFeedItem(
        index = CustomThemeColor.INDEX,
        nameRes = R.string.custom_theme_color_entry,
        hex = customHex,
        selected = current == CustomThemeColor.INDEX &&
                CustomThemeColor.normalize(Shaft.sSettings.getTagTranslationColorCustomHex()) == customHex,
    )
}
