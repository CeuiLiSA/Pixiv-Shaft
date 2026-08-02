package ceui.lisa.fragments

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 在 [FragmentHistoryTabs] 容器和它的子 tab ([FragmentHistoryList] /
 * [FragmentHistoryUserList]) 之间共享浏览历史搜索的当前 query。activityViewModels
 * 范围 — host fragment 跟所有 ViewPager 子 fragment 都 attach 到同一个 Activity。
 *
 * null = 未进入搜索；""=展开 SearchView 但未输入；非空 = 真实关键词。子 tab 内部
 * 用 flatMapLatest / collect 切换数据源。
 */
class HistorySearchSharedViewModel : ViewModel() {

    private val _query = MutableStateFlow<String?>(null)
    val query: StateFlow<String?> get() = _query

    fun setQuery(q: String?) {
        _query.value = q
    }

    /**
     * 各 tab 最近一次首屏加载实际用的搜索词（historyType → 归一化后的 query，null = 未过滤）。
     *
     * 存在这里而不是各 tab 的 Fragment 字段上：搜索词归 activity 作用域，某个 tab 的视图销毁
     * 期间（三 tab pager，滑远了会销毁）它照样会变；tab 重建时得知道「我列表里这批内容对应的
     * 是哪个词」才能判断要不要补刷一次。Fragment 字段会随旋转 / 视图重建丢失，这个 map 不会。
     */
    private val appliedQueries = HashMap<Int, String?>()

    /** 数据源真正按 [query] 拉过首屏后调用（只在首页，翻页沿用同一个词）。 */
    fun markQueryApplied(historyType: Int, query: String?) {
        appliedQueries[historyType] = query
    }

    /** 该 tab 当前列表内容是否就是 [query] 拉出来的（false = 需要补一次刷新）。 */
    fun isQueryApplied(historyType: Int, query: String?): Boolean =
        appliedQueries.containsKey(historyType) && appliedQueries[historyType] == query
}
