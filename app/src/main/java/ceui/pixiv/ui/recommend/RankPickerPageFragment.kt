package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRankPickerPageBinding
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 「选择条 + bottom sheet + feed」的榜单子页基类:标签专区([TagRankPageFragment])与年代榜
 * ([YearRankPageFragment])按类型各有一页,挂在 [TypeTabsRankFragment] 的 ViewPager 里。
 * 选择条显示当前选项(翻译优先 + 作品数),点它弹 [RankPickerSheet](列表来自服务端按
 * [type] 拉的 /discover/tags 或 /discover/years),选完 child feed 整页 replace。
 *
 * 为什么选项不是横向 tab:标签是 21 万个里挑出来的 top-50,横滑找不到心智锚点,列表 + 计数
 * 一屏看全才挑得动(用户反馈,年代榜同期一起改了)。类型(插画 / 漫画 / 小说)只有三个,
 * 才配得上 tab。
 *
 * 懒加载:选项列表在 [onResume] 才拉(宿主 ViewPager 是 RESUME_ONLY_CURRENT,离屏 tab 的
 * onViewCreated 会先跑但不 RESUMED),避免三个 tab 齐射 /discover/tags;feed 子页
 * `autoLoad=false`,replace 进来即 RESUMED 即拉首屏,一次只有一个 feed 在场。
 *
 * 选项 sheet 挂 childFragmentManager,回调走 parentFragment 强转(见 [RankPickerSheet]),
 * 重建后链路天然恢复。
 */
abstract class RankPickerPageFragment : Fragment(R.layout.fragment_rank_picker_page), RankPickerSheet.Host {

    /** 一条可选项。[key] 是服务端 enum 语义(tag 原文 / 年份),[label]/[sublabel] 是展示文案。 */
    protected class Option(
        val key: String,
        val label: String,
        val sublabel: String,
        val count: Int,
    )

    private val binding by viewBinding(FragmentRankPickerPageBinding::bind)

    /** 服务端 enum([RankType]),决定拉哪一类的选项列表与 feed。 */
    protected val type: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_TYPE) ?: RankType.ILLUST
    }

    /** 选项列表(本页每次建 view 拉一次,sheet 每次打开复用)。 */
    private var options: List<Option> = emptyList()
    /** 当前展示的选项 key。重建后靠它恢复选择条文案与 sheet 选中态;feed 由 FM 自己带回来。 */
    private var currentKey: String? = null
    /** 本次 view 生命周期内是否已经发起过选项拉取(onResume 可能跑多次,只拉一次)。 */
    private var optionsRequested = false

    @get:StringRes protected abstract val pickTitleRes: Int
    @get:StringRes protected abstract val loadFailedRes: Int
    protected abstract val logTag: String

    /** 拉 [type] 类型的选项列表(主线程调用,挂起到网络回来)。 */
    protected abstract suspend fun fetchOptions(type: String): List<Option>

    /** [type] 类型下、选项 [key] 的 feed 子页(autoLoad=false,无 toolbar)。 */
    protected abstract fun createFeed(type: String, key: String): Fragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentKey = savedInstanceState?.getString(KEY_CURRENT)
        optionsRequested = false
        binding.rankSelector.setOnClickListener { openPicker() }

        // 重建路径:先用恢复的 key 裸显原文,不等网络往返(options 回来后 loadOptions 会再 bind
        // 一次补上翻译 + 计数)。
        currentKey?.let { bindSelector(it) }
        binding.rankLoading.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (!optionsRequested) {
            optionsRequested = true
            loadOptions()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT, currentKey)
    }

    /** 拉选项列表。失败 toast + 选择条显失败文案(点它重试);CancellationException 原样 rethrow(约定见本仓 FeedViewModel)。 */
    private fun loadOptions() {
        // 重建路径:feed 子页已被 FM 带回来,列表只是给 sheet 用 —— 不挡内容,loading 只在首装转。
        val firstBuild = childFragmentManager.findFragmentById(R.id.feed_container) == null
        binding.rankLoading.visibility = if (firstBuild) View.VISIBLE else View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val type = type
            val fetched = try {
                fetchOptions(type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(logTag).w(e, "load options failed type=$type")
                emptyList()
            }
            binding.rankLoading.visibility = View.GONE
            if (fetched.isEmpty()) {
                // 拉挂了 / 列表为空:toast 一声,选择条改显失败文案,再点即重试(optionsRequested
                // 归零);首装时页面本来就是空的,不关整个宿主 —— 别的类型 tab 还好好的。
                Common.showToast(getString(loadFailedRes))
                if (currentKey == null) {
                    binding.rankSelector.text = getString(loadFailedRes)
                }
                optionsRequested = false
                return@launch
            }
            options = fetched
            // 首装默认第一个(最热 / 最新一年);重建保持原选择。currentKey 不在新列表里(榜单漂移)
            // 也没关系 —— feed 与选择条仍按它展示,sheet 里只是没有高亮行。
            val key = currentKey ?: fetched.first().key
            if (firstBuild) {
                showOption(key)
            } else {
                currentKey = key
                bindSelector(key)
            }
        }
    }

    /** 切到 [key]:换 feed 子页 + 刷选择条。选同一个由 [onRankOptionPicked] 挡掉,不白重载。 */
    private fun showOption(key: String) {
        currentKey = key
        bindSelector(key)
        // allowingStateLoss:首装的 showOption 由网络回调驱动,响应落在「切后台之后、视图
        // 销毁之前」的窗口时普通 commit 会抛 IllegalStateException。丢状态无害 —— 重建路径
        // 本来就靠 FM 恢复 child + savedInstanceState 恢复选择,少这一笔照样对。
        childFragmentManager.commit(allowStateLoss = true) {
            replace(R.id.feed_container, createFeed(type, key))
        }
    }

    /** 选择条文案:「碧蓝档案 · 20.0k ▾」;列表还没回来时先裸显 key。 */
    private fun bindSelector(key: String) {
        val option = options.find { it.key == key }
        binding.rankSelector.text = if (option != null) {
            "${option.label} · ${formatRankCount(option.count)}"
        } else {
            key
        }
    }

    private fun openPicker() {
        if (options.isEmpty()) {
            // 列表还没回来 / 拉挂了:没在拉就当作重试。
            if (!optionsRequested) {
                optionsRequested = true
                loadOptions()
            }
            return
        }
        RankPickerSheet.show(
            childFragmentManager,
            title = getString(pickTitleRes),
            keys = options.map { it.key }.toTypedArray(),
            labels = options.map { it.label }.toTypedArray(),
            sublabels = options.map { it.sublabel }.toTypedArray(),
            counts = options.map { it.count }.toIntArray(),
            // -1 = 当前项已掉出最新列表(榜单漂移):sheet 不高亮任何行,而不是错误地亮第一行。
            selected = options.indexOfFirst { it.key == currentKey },
        )
    }

    override fun onRankOptionPicked(key: String) {
        if (key == currentKey) return
        showOption(key)
    }

    companion object {
        private const val KEY_CURRENT = "rank_picker_page_current"
        internal const val ARG_TYPE = "rank_picker_page_type"

        /** 给子类 newInstance 用:只放 type 一个参数。 */
        internal fun typeArgs(type: String): Bundle = Bundle().apply { putString(ARG_TYPE, type) }
    }
}

/**
 * 标签专区的某个类型页:选项来自 /discover/tags?type=(top-N,翻译优先 + 作品数),feed 是
 * discover/most-bookmarked?type=&tag=。
 */
class TagRankPageFragment : RankPickerPageFragment() {

    override val pickTitleRes: Int get() = R.string.tag_rank_pick
    override val loadFailedRes: Int get() = R.string.tag_rank_load_failed
    override val logTag: String get() = "TagRank"

    override suspend fun fetchOptions(type: String): List<Option> {
        val tags = ShaftApiV2Client.service.discoverTags(type = type, limit = TAG_LIST_COUNT).tags.orEmpty()
        return tags.map {
            Option(
                key = it.tag,
                label = it.translated ?: it.tag,
                // 有翻译时副行显原文(在 pixiv 站内搜索还得用原文);没翻译主行就是原文,副行藏。
                sublabel = if (it.translated != null) it.tag else "",
                count = it.count,
            )
        }
    }

    override fun createFeed(type: String, key: String): Fragment = if (type == RankType.NOVEL) {
        BookmarkRankNovelFeedFragment.newInstance(tag = key)
    } else {
        BookmarkRankIllustFeedFragment.newInstance(type = type, tag = key)
    }

    companion object {
        /** sheet 列表条数。列表比 tab 装得下更多长尾;再多靠服务端 ?q= 搜索,以后要做再加。 */
        private const val TAG_LIST_COUNT = 50

        @JvmStatic
        fun newInstance(type: String): TagRankPageFragment =
            TagRankPageFragment().apply { arguments = typeArgs(type) }
    }
}

/**
 * 年代榜的某个类型页:选项来自 /discover/years?type=(年份降序 + 每年作品数),feed 是
 * discover/most-bookmarked?type=&year=。
 *
 * ⚠️ 分布极度倾斜:2026 年占 56%,2007 年整年只有几十个作品。sheet 行里带上作品数,
 * 否则用户点进 2007 看到一屏就到底,会以为是 bug。
 */
class YearRankPageFragment : RankPickerPageFragment() {

    override val pickTitleRes: Int get() = R.string.year_rank_pick
    override val loadFailedRes: Int get() = R.string.year_rank_load_failed
    override val logTag: String get() = "YearRank"

    override suspend fun fetchOptions(type: String): List<Option> {
        val years = ShaftApiV2Client.service.discoverYears(type = type).years.orEmpty()
        return years.map { Option(key = it.year, label = it.year, sublabel = "", count = it.count) }
    }

    override fun createFeed(type: String, key: String): Fragment = if (type == RankType.NOVEL) {
        BookmarkRankNovelFeedFragment.newInstance(year = key)
    } else {
        BookmarkRankIllustFeedFragment.newInstance(type = type, year = key)
    }

    companion object {
        @JvmStatic
        fun newInstance(type: String): YearRankPageFragment =
            YearRankPageFragment().apply { arguments = typeArgs(type) }
    }
}
