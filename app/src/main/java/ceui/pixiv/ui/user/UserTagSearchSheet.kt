package ceui.pixiv.ui.user

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellUserTagSearchRowBinding
import ceui.lisa.databinding.DialogUserTagSearchBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.UserWorkTag
import ceui.pixiv.ui.search.v3.V3BottomSheetBase
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 画师主页「高级搜索」—— 该画师**全量**作品标签的可搜索列表，对齐网页版画师页的同名面板。
 * issue #996 起插画/漫画/小说三个 tab 共用：按 [category]（网页 ajax 端点段
 * illusts/manga/novels）选 tags 数据源与点击后的筛选路由。
 *
 * 和筛选条那几个 chip 的区别在数据源：chip 是 [ceui.lisa.activities.UserV3WorkTabFragment]
 * 从 app-api 首屏作品本地聚合出来的高频 tag（最多 20 个，还要按 2 行裁），这里走网页
 * `/ajax/user/{id}/{category}/tags`，一次拿到画师建过的所有 tag（实测可达近 2000 条）。
 *
 * 顺带修掉筛选条那个结构性错位：chip 的 tag 来自 app-api（已登录视角），筛选结果却走网页
 * ajax（未同步 cookie 时是匿名视角），于是「chip 在、点进去 0 件」。本 sheet 的 tag 和筛选
 * 是同一个数据源、同一套身份，列出来的就一定筛得到 —— 详见
 * [UserIllustByTagFeedFragment] 的 emptyStateText 注释。
 *
 * **排序必须自己做**：服务端返回的顺序既不是频次也不是五十音，直接渲染第一条会是个只用过
 * 一次的冷门 tag。这里按 cnt 降序、同频次按 tag 名字典序稳定兜底。
 */
class UserTagSearchSheet : V3BottomSheetBase() {

    // 全量列表，占满高度才不至于翻两下就到底。
    override val maxHeightFraction: Float = 0.92F

    private var _binding: DialogUserTagSearchBinding? = null
    private val binding get() = _binding!!

    private val userId: Long by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getLong(Params.USER_ID)
    }

    // 网页 ajax 端点段:illusts / manga / novels。缺省 illusts。
    private val category: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.CONTENT_TYPE) ?: CATEGORY_ILLUSTS
    }

    /** 已按 cnt 降序排好的全量 tag；过滤只在它之上做，不重排。 */
    private var allRows: List<TagRow> = emptyList()

    /**
     * 请求是否已成功返回。空态文案要靠它区分「还没加载完 / 加载失败」和「这位画师真的没有
     * 标签」—— 三种情况下 [allRows] 都是空的，只看 size 会在加载途中打字时闪出
     * 「这位画师还没有作品标签」，失败后打字还会把失败提示顶掉。
     */
    private var loaded = false

    private val adapter = TagAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogUserTagSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // adapter 置空，断开 RecyclerView ←→ adapter 的互引；binding 置空前做。
        _binding?.tagList?.adapter = null
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClick { dismissAllowingStateLoss() }
        binding.tagList.layoutManager = LinearLayoutManager(requireContext())
        // RecyclerView 自身尺寸恒为 match_parent，不随内容变 —— 省掉每次 adapter 变更后的
        // requestLayout（2000 条列表上按键过滤时每次都会触发）。
        binding.tagList.setHasFixedSize(true)
        binding.tagList.adapter = adapter

        // 基类给 root 垫了 systemBars 的底 inset —— 那套是给静态内容(设置卡)用的，压在滚动
        // 列表上会把 RecyclerView 直接压短：内容在导航栏上方硬生生截断，滚不进 safe area。
        // 换成仓库里列表型 sheet 的通用做法(见 utils/BottomSheetInsets.letDrawBehindNavBar)：
        // root 不垫底 —— 自绘的 bg_v3_sheet_top 一路铺到屏幕底；inset 移到 RecyclerView 的
        // paddingBottom，配合 clipToPadding=false，列表内容从导航栏底下穿过去，滚到底时
        // 最后一行又正好抬离手势条。覆盖 super 的 listener，故必须在 super.onViewCreated 之后。
        // 键盘弹起时（ime > 0）改由 root 垫 ime：edgeToEdge 下 window 不随软键盘 resize/pan，
        // 不垫的话键盘会把这张 sheet 连同顶部那个搜索框一起盖住（issue #1024）。垫上之后整块
        // 内容被抬到键盘之上、列表随之压缩；键盘收起 ime 归零，又回到上面那套穿透式布局。
        // 列表的底 inset 相应扣掉 ime —— 键盘已经盖住导航栏，再留一份就是双份留白。
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.updatePadding(bottom = ime)
            _binding?.tagList?.updatePadding(bottom = (bottom - ime).coerceAtLeast(0))
            insets
        }
        ViewCompat.requestApplyInsets(view)

        binding.searchClear.setOnClick { binding.searchInput.setText("") }
        binding.searchInput.doAfterTextChanged { text ->
            val q = text?.toString().orEmpty()
            binding.searchClear.isVisible = q.isNotEmpty()
            applyFilter(q)
        }

        loadTags()
    }

    private fun loadTags() {
        binding.loading.isVisible = true
        binding.emptyText.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            val uid = userId
            val tags = try {
                Client.webApi.getUserWorkTags(uid, category).body
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 不吞取消：view 销毁时 scope 被取消，必须让它继续向上传播。
                throw e
            } catch (e: Throwable) {
                null
            }
            val b = _binding ?: return@launch
            b.loading.isVisible = false
            if (tags == null) {
                // 只有网络/解析失败才走这里；空列表是合法结果，交给下面的空态文案。
                // 保持 loaded=false，这样之后打字不会把失败提示顶掉。
                showEmpty(getString(R.string.user_tag_sheet_failed))
                return@launch
            }
            // 服务端不排序，这里定序：热度降序，同频次按 tag 名稳定兜底（否则每次请求顺序可能抖）。
            // 同时把小写形式预算好 —— 过滤要在每次按键时扫全量，现算 lowercase 等于每敲一个
            // 字符做 2×N 次字符串分配（N 可达 2000）。
            allRows = tags
                .asSequence()
                .filter { !it.tag.isNullOrBlank() }
                .sortedWith(compareByDescending<UserWorkTag> { it.cnt }.thenBy { it.tag.orEmpty() })
                .map { TagRow(it) }
                .toList()
            loaded = true
            applyFilter(b.searchInput.text?.toString().orEmpty())
        }
    }

    private fun applyFilter(query: String) {
        val b = _binding ?: return
        // 还没加载完（或加载失败）时不碰空态：此刻 allRows 为空只说明数据还没到，
        // 不代表画师没有标签。loading / 失败文案由 loadTags 负责。
        if (!loaded) return
        // 大小写折叠固定用 ROOT：跟着 default locale 走的话，土耳其语环境下 'I' 会折成 'ı'，
        // 拉丁字母的匹配会莫名其妙失效。这里是内部比较，不是给人看的文本。
        val q = query.trim().lowercase(Locale.ROOT)
        val shown = if (q.isEmpty()) {
            allRows
        } else {
            // 原文和译名都参与匹配 —— 用户可能记得「碧蓝档案」也可能记得「ブルーアーカイブ」。
            allRows.filter { it.rawLower.contains(q) || it.translationLower.contains(q) }
        }
        adapter.submit(shown)
        b.tagList.scrollToPosition(0)
        if (shown.isEmpty()) {
            showEmpty(
                if (allRows.isEmpty()) getString(R.string.user_tag_sheet_no_tag)
                else getString(R.string.user_tag_sheet_no_match)
            )
        } else {
            b.emptyText.isVisible = false
            b.tagList.isVisible = true
        }
    }

    private fun showEmpty(text: CharSequence) {
        val b = _binding ?: return
        b.emptyText.text = text
        b.emptyText.isVisible = true
        b.tagList.isVisible = false
    }

    /** 点某个 tag = 进该画师按此 tag 筛选的对应类型作品页（issue #569/#996 的现成路由）。 */
    private fun openTag(tag: UserWorkTag) {
        // 筛选接口吃的是原文 tag，不是译名。
        val name = tag.tag.orEmpty()
        if (name.isEmpty()) return
        startActivity(
            Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(Params.USER_ID, userId)
                putExtra(TemplateActivity.EXTRA_FRAGMENT, routeOf(category))
                putExtra(Params.KEY_WORD, name)
            }
        )
        dismissAllowingStateLoss()
    }

    /**
     * MD3-E 分段行背景的四种形态（单行 / 段首 / 段中 / 段尾），和
     * [V3BottomSheetBase.applySegmentedCardStyle] 同一套视觉（cardFill 底 + 12% hairline +
     * 段首段尾 20dp、段中 5dp）。那个方法只走 LinearLayout 的直接子 view，对 RecyclerView
     * 无效，所以这里自己建。
     *
     * 存 ConstantState 而不是每次新建 Drawable：onBindViewHolder 在长列表滚动时调用极频繁，
     * 每次 new 一对 GradientDrawable/RippleDrawable 外加 resolveAttribute 是持续的分配抖动。
     * newDrawable() 出来的实例共享 constant state，但 ripple 的 hotspot 是实例级的，不会串。
     */
    private val segmentStates: Array<Drawable.ConstantState?> by lazy {
        val d = resources.displayMetrics.density
        val big = 20f * d
        val small = 5f * d
        val strokePx = (0.5f * d).toInt().coerceAtLeast(1)
        val highlight = TypedValue().let { tv ->
            requireContext().theme.resolveAttribute(android.R.attr.colorControlHighlight, tv, true)
            tv.data
        }
        Array(4) { kind ->
            val top = if (kind and SEG_FIRST != 0) big else small
            val bottom = if (kind and SEG_LAST != 0) big else small
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
                setColor(palette.cardFill)
                setStroke(strokePx, palette.cardHairline)
            }
            RippleDrawable(ColorStateList.valueOf(highlight), shape, null).constantState
        }
    }

    /** 计数胶囊同理：常量态复用，避免每次 bind 新建。 */
    private val countBgState: Drawable.ConstantState? by lazy {
        palette.tagCountBg().constantState
    }

    private fun segmentBg(position: Int, total: Int): Drawable? {
        var kind = 0
        if (position == 0) kind = kind or SEG_FIRST
        if (position == total - 1) kind = kind or SEG_LAST
        return segmentStates[kind]?.newDrawable()
    }

    private inner class TagAdapter : RecyclerView.Adapter<TagHolder>() {

        private var items: List<TagRow> = emptyList()

        /**
         * 过滤是「整表换一批」，没有稳定的 item 身份可言（同一个 tag 在不同查询下位置全变），
         * DiffUtil 在这里只会白算一遍。notifyDataSetChanged 后 RecyclerView 也只重绑可见的
         * 十来个 holder，代价可控。
         */
        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<TagRow>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder {
            val b = CellUserTagSearchRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return TagHolder(b)
        }

        override fun onBindViewHolder(holder: TagHolder, position: Int) {
            val item = items[position].tag
            val raw = item.tag.orEmpty()
            val translation = item.tag_translation.orEmpty()
            if (translation.isNotEmpty() && translation != raw) {
                holder.binding.tagTitle.text = translation
                holder.binding.tagSub.isVisible = true
                holder.binding.tagSub.text = "#$raw"
            } else {
                // 没译名：原文升到主标题，副标题收起（留空行会让列表节奏散掉）。
                holder.binding.tagTitle.text = "#$raw"
                holder.binding.tagSub.isVisible = false
            }
            holder.binding.tagCount.text = item.cnt.toString()
            holder.binding.tagCount.background = countBgState?.newDrawable()
            holder.binding.tagCount.setTextColor(palette.textAccent)
            // MD3-E 分段行：整个列表当一个段，首尾 20dp、中间 5dp。
            holder.binding.root.background = segmentBg(position, items.size)
            holder.binding.root.setOnClick { openTag(item) }
        }
    }

    private inner class TagHolder(val binding: CellUserTagSearchRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    /** 一行的展示数据 + 预算好的小写形式（过滤时按键扫全量，不能现算）。 */
    private class TagRow(val tag: UserWorkTag) {
        val rawLower: String = tag.tag.orEmpty().lowercase(Locale.ROOT)
        val translationLower: String = tag.tag_translation.orEmpty().lowercase(Locale.ROOT)
    }

    companion object {
        private const val TAG = "UserTagSearchSheet"
        private const val SEG_FIRST = 1
        private const val SEG_LAST = 2

        // 网页 ajax 端点段,同时是本 feature 全链路(包装 tab / 本 sheet / 筛选列表页)的类别标识。
        const val CATEGORY_ILLUSTS = "illusts"
        const val CATEGORY_MANGA = "manga"
        const val CATEGORY_NOVELS = "novels"

        /** 类别 → TemplateActivity 的筛选列表路由(chip 与 sheet 两个入口共用这份映射)。 */
        fun routeOf(category: String): String = when (category) {
            CATEGORY_MANGA -> "漫画标签作品"
            CATEGORY_NOVELS -> "小说标签作品"
            else -> "插画标签作品"
        }

        fun show(fm: FragmentManager, userId: Long, category: String = CATEGORY_ILLUSTS) {
            // 双保险：isStateSaved 挡住 onSaveInstanceState 之后 commit 抛 IllegalStateException；
            // findFragmentByTag 挡住快速双击弹出两张 sheet。
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            UserTagSearchSheet().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userId)
                    putString(Params.CONTENT_TYPE, category)
                }
            }.show(fm, TAG)
        }
    }
}
