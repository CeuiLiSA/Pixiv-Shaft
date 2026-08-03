package ceui.lisa.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.loxia.Client
import ceui.loxia.CsrfTokenProvider
import ceui.loxia.StreetContent
import ceui.loxia.StreetNextParams
import ceui.loxia.StreetRequest
import ceui.loxia.StreetResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** 广告：`pure_ad` 是 pixiv 自家投放脚本，`pixiv_ads_ad` 是外部广告位，两者都不进列表。 */
private val AD_KINDS = setOf("pure_ad", "pixiv_ads_ad")

/** 单卡内容（进瀑布流的一格）。 */
internal val WORK_KINDS = setOf("illust", "manga", "novel", "collection")

/** 通栏货架（横向条，占满两列）。 */
internal const val KIND_CAROUSEL = "carousel"
internal const val KIND_TAGS_CAROUSEL = "tags_carousel"

class StreetMainViewModel : ViewModel() {

    private val _items = MutableLiveData<List<StreetContent>>(emptyList())
    val items: LiveData<List<StreetContent>> = _items

    private val _loadState = MutableLiveData<LoadState>(LoadState.Idle)
    val loadState: LiveData<LoadState> = _loadState

    private var nextParams: StreetNextParams? = null
    val hasMore: Boolean get() = nextParams != null

    // 累积已加载的各类 ID，下次请求带上
    private val loadedIllustIds = mutableListOf<String>()
    private val loadedMangaIds = mutableListOf<String>()
    private val loadedNovelIds = mutableListOf<String>()
    private val loadedCollectionIds = mutableListOf<String>()

    /** 已经出过的货架 key，见 [renderable]。刷新时清空。 */
    private val seenRails = mutableSetOf<String>()

    fun refresh() = load(refresh = true)
    fun loadMore() = load(refresh = false)

    private fun load(refresh: Boolean) {
        if (_loadState.value == LoadState.Loading) return
        _loadState.value = LoadState.Loading

        viewModelScope.launch {
            try {
                val request = buildRequest(refresh)
                val response = callApi(request, retried = false)

                // 清空必须在 renderable 之前：货架去重靠 seenRails，刷新时不先清就会把
                // 新一轮首屏的「精选新作」当成上一轮的重复条给滤掉。
                if (refresh) {
                    loadedIllustIds.clear()
                    loadedMangaIds.clear()
                    loadedNovelIds.clear()
                    loadedCollectionIds.clear()
                    seenRails.clear()
                }

                val current = if (refresh) emptyList() else (_items.value ?: emptyList())
                val contents = renderable(response.body?.contents)
                nextParams = response.body?.nextParams

                for (c in response.body?.contents.orEmpty()) {
                    val tid = c.thumbnails?.firstOrNull()?.id ?: continue
                    when (c.kind) {
                        "illust" -> loadedIllustIds.add(tid)
                        "manga" -> loadedMangaIds.add(tid)
                        "novel" -> loadedNovelIds.add(tid)
                        "collection" -> loadedCollectionIds.add(tid)
                    }
                }

                _items.value = current + contents
                _loadState.value = if (refresh) {
                    LoadState.Refreshed
                } else {
                    LoadState.LoadedMore(current.size, contents.size)
                }
            } catch (e: Exception) {
                _loadState.value = LoadState.Error(e.message ?: "加载失败")
            }
        }
    }

    /**
     * 服务端 contents → 真正进列表的条目。丢广告，丢分隔线，其余能画的全留下。
     *
     * 分隔线（`separator`）不进列表：它在瀑布流里只能做通栏条，一条就把两列强行对齐一次，
     * 单卡区被切碎后左右两列各留一大片空白。网页端是单列流才吃得下这个语义。
     *
     * 货架（carousel / tags_carousel）要跨页去重：翻页请求只带「看过哪些作品」（vhi/vhn/…），
     * 没有任何字段能告诉服务端货架已经给过了，于是**每一页都会原样再下发一次**「精选新作」
     * 和热门标签 —— 不拦就是同一条货架在列表里刷屏。按 listType 认，一次会话里只留第一条。
     */
    private fun renderable(contents: List<StreetContent>?): List<StreetContent> =
        contents.orEmpty().filter { c ->
            when (c.kind) {
                in AD_KINDS -> false
                in WORK_KINDS -> c.thumbnails?.firstOrNull() != null
                // 货架空了就没什么可展开的，通栏一条空标题反而突兀
                KIND_CAROUSEL ->
                    !c.thumbnails.isNullOrEmpty() &&
                        seenRails.add("$KIND_CAROUSEL:${c.listType ?: c.title.orEmpty()}")
                KIND_TAGS_CAROUSEL ->
                    !c.trendTags.isNullOrEmpty() && seenRails.add(KIND_TAGS_CAROUSEL)
                else -> false
            }
        }

    private fun buildRequest(refresh: Boolean): StreetRequest {
        return if (refresh) {
            StreetRequest()
        } else {
            StreetRequest(
                vhi = loadedIllustIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                vhm = loadedMangaIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                vhn = loadedNovelIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                vhc = loadedCollectionIds.takeIf { it.isNotEmpty() }?.joinToString(","),
            )
        }
    }

    private suspend fun callApi(request: StreetRequest, retried: Boolean): StreetResponse {
        // 缓存里没有就现抓一次,别直接判死 —— 「CSRF token 未就绪」对用户来说没有任何可操作性。
        val csrf = withContext(Dispatchers.IO) { CsrfTokenProvider.getOrFetch() }
            ?: throw RuntimeException("CSRF token 未就绪，请重试")

        val response = try {
            withContext(Dispatchers.IO) {
                Client.webApi.getStreetMain(csrf, request)
            }
        } catch (ex: HttpException) {
            // token 会在 cookie 没变的情况下自行轮换,过期后服务端直接回 400/403。Retrofit
            // 把它抛成 HttpException,压根走不到下面 error==true 那条重试分支 —— 于是坏 token
            // 一直缓存着,用户每次刷新都吃同一句「HTTP 400」,登录得再对也好不了。
            if (!retried && (ex.code() == 400 || ex.code() == 403)) {
                CsrfTokenProvider.clear()
                return callApi(request, retried = true)
            }
            throw ex
        }

        if (response.error == true && !retried) {
            // token 可能过期，清除缓存后重试一次
            CsrfTokenProvider.clear()
            return callApi(request, retried = true)
        }
        if (response.error == true) {
            throw RuntimeException(response.message ?: "请求失败")
        }
        return response
    }

    sealed class LoadState {
        object Idle : LoadState()
        object Loading : LoadState()
        object Refreshed : LoadState()
        data class LoadedMore(val insertStart: Int, val insertCount: Int) : LoadState()
        data class Error(val message: String) : LoadState()
    }
}
