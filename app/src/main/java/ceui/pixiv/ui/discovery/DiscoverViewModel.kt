package ceui.pixiv.ui.discovery

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.core.RemoteRepo
import ceui.lisa.model.ListIllust
import ceui.loxia.Illust
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.repo.LatestIllustRepo
import ceui.pixiv.ui.prime.PrimeTagIndexItem
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.loxia.appServices
import android.app.Application

/**
 * 发现页(FragmentCenter)各内容货架的数据持有者。把侧边栏「发现」分组的内容直接铺进 tab:
 *   · [primeTags]   热度标签 —— 本地策展 asset(prime_index.json),离线秒开
 *   · [latest]      最新     —— [LatestIllustRepo] getNewWorks(全站新投稿)
 *   · [siteRecommend] 本月收藏 —— shaft-api-v2 周收藏榜(非 Lite)
 *   · [recentHot]   当前最热 —— shaft-api-v2 实时收藏流(非 Lite)
 *
 * 上面两条 shaft-api-v2 货架合并成一个 /discover 聚合请求([loadDiscover]),不再各打一枪两个来回。
 * 每条货架只拉一页、截断到 [RAIL_LIMIT] 张;点「查看全部」跳各自整页(仍走各自分页接口)。
 * 数据存这里而非 Fragment 字段:tab 来回切不重拉,配置变更后货架还在。
 */
class DiscoverViewModel(application: Application) : AndroidViewModel(application) {

    private val _primeTags = MutableLiveData<List<PrimeTagIndexItem>>()
    val primeTags: LiveData<List<PrimeTagIndexItem>> get() = _primeTags

    private val _latest = MutableLiveData<List<Illust>>()
    val latest: LiveData<List<Illust>> get() = _latest

    private val _siteRecommend = MutableLiveData<List<Illust>>()
    val siteRecommend: LiveData<List<Illust>> get() = _siteRecommend

    private val _recentHot = MutableLiveData<List<Illust>>()
    val recentHot: LiveData<List<Illust>> get() = _recentHot

    private var started = false

    /** 首次可见时懒加载;加载过就跳过(tab 来回切不重拉)。 */
    fun start(includeServerShelves: Boolean) {
        if (started) return
        started = true
        reload(includeServerShelves)
    }

    /** 下拉刷新 / 双击 tab forceRefresh:重拉全部货架。 */
    fun reload(includeServerShelves: Boolean) {
        loadTags()
        loadIllustRail(LatestIllustRepo("illust", getApplication<Application>().appServices().discoveryPool), _latest)
        // 本月收藏 / 当前最热 / 隐藏神作 三条 shaft-api-v2 货架合并成一个 /discover 请求;
        // Lite 渠道不展示这三条。
        if (includeServerShelves) {
            loadDiscover()
        }
    }

    /**
     * 一个 /discover 聚合请求,填两条 shaft-api-v2 货架(替掉之前 2 个独立请求)。
     * 失败整体静默塌陷:两条都发空 → 收起货架,不弹 toast(后台货架不打扰前台)。
     * 每条 bean 的收藏态清成 false(payload 里是上报者的态,跟当前用户无关),user==null
     * 的脏数据剔掉(RAdapter 直取 user.name / 头像,null 会 NPE)。
     */
    private fun loadDiscover() {
        viewModelScope.launch {
            val resp = withContext(Dispatchers.IO) {
                try {
                    ShaftApiV2Client.service.discover()
                } catch (e: Throwable) {
                    Timber.tag("Discover").w(e, "discover aggregate failed")
                    null
                }
            }
            _siteRecommend.value = parseShelf(resp?.site?.items) { it.score.toFloat() }
            _recentHot.value = parseShelf(resp?.recent?.items) { it.bookmark_count.toFloat() }
        }
    }

    /**
     * 把一条 shelf 的 item 列表解析成可渲染的 [Illust]:bean JSON → Gson,热度值由
     * [scoreOf] 决定(本月收藏取加权 score、当前最热/隐藏神作取 bookmark_count),露左上角 pill。
     */
    private fun parseShelf(
        items: List<ShaftApiV2.TrendingWorkItem>?,
        scoreOf: (ShaftApiV2.TrendingWorkItem) -> Float,
    ): List<Illust> {
        if (items.isNullOrEmpty()) return emptyList()
        val gson = Shaft.sGson
        return items.mapNotNull { item ->
            item.bean?.let { json ->
                try {
                    gson.fromJson(json, Illust::class.java)
                        .withTrendingScore(scoreOf(item))
                        .withBookmarked(false)
                } catch (e: Throwable) {
                    null
                }
            }
        }.filter { it.user != null }.take(RAIL_LIMIT)
    }

    private fun loadIllustRail(
        repo: RemoteRepo<ListIllust>,
        live: MutableLiveData<List<Illust>>
    ) {
        viewModelScope.launch {
            val list = try {
                // 过滤 user==null 的脏数据:RAdapter 直接取 user.name / 头像,null 会 NPE 崩。
                repo.loadFirst()?.illusts?.filter { it.user != null }?.take(RAIL_LIMIT) ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失败静默塌陷(不走 ErrorCtrl,避免对后台货架弹 toast);发空→收起该货架。
                Timber.w(e, "Discovery/Repo latest rail failed")
                emptyList()
            }
            live.value = list
        }
    }

    private fun loadTags() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    val json = Utils.getApp().assets.open(INDEX_FILE)
                        .bufferedReader().use { it.readText() }
                    val type = object : TypeToken<List<PrimeTagIndexItem>>() {}.type
                    Gson().fromJson<List<PrimeTagIndexItem>>(json, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            // 随机展示:每次真正构建 fragment(新 VM)重新洗牌,不再永远头三个「超电磁炮/对魔忍…」。
            // tab 来回切 VM 不重建、started 门控住,所以不会一切换就换一批(符合用户要的口径)。
            _primeTags.value = items.shuffled().take(TAG_LIMIT)
        }
    }

    companion object {
        private const val INDEX_FILE = "pixiv_prime/prime_index.json"
        private const val RAIL_LIMIT = 12
        private const val TAG_LIMIT = 15
    }
}
