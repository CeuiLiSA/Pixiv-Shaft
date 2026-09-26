package ceui.pixiv.ui.search

import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.SearchEntity
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.pinned.findPinnedSearch
import ceui.pixiv.ui.pinned.searchTermsDisplayName
import ceui.pixiv.ui.pinned.splitSearchTerms
import ceui.pixiv.utils.buildPinnedTagPreviewJson
import ceui.pixiv.witstudio.theme.motionEnabled
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索结果页顶栏的图钉：一键置顶当前 chip 行里的全部标签（pixez#1364「收藏标签组合」）。
 *
 * 为什么放在结果页顶栏：组合本来就是在这里拼出来的——点进一个标签、再敲一个、看着结果满意了，
 * 下一步就是「把它留住」。放进长按菜单或设置里，用户得先知道有这个功能才找得到。
 *
 * - 图钉描边 = 未置顶，实心 = 已置顶；判定按词集合（[findPinnedSearch]），「胡桃 原神」和
 *   「原神 胡桃」是同一个组合。chip 增删后由宿主调 [refresh] 重算。
 * - 置顶与取消都是一次点击直接生效、不弹确认：两者都能撤回，确认框只会拖慢高频操作。
 *   取消置顶的 Snackbar 带「撤销」，原样写回旧行（保留原排序时间和预览图）；置顶的 Snackbar
 *   带「查看」，去「我置顶的内容」。
 * - 预览图取插画 tab 当前结果的头 3 张，置顶卡片直接有图；还没加载插画 tab 时置顶照常，只是没图。
 * - 命中搜索风控（[SearchRiskPolicy]）的查询不持久化，图钉不出现——与搜索历史同一口径。
 */
class SearchPinController(
    private val activity: AppCompatActivity,
    private val toolbar: Toolbar,
    private val snackbarAnchor: View,
    private val termsProvider: () -> List<String>,
    private val previewProvider: () -> List<Illust>,
) {

    /** 当前词集合对应的已置顶行；null = 未置顶。 */
    private var pinnedEntity: SearchEntity? = null
    private var lookupJob: Job? = null
    /** 置顶 / 取消正在落库。期间忽略连点，避免两次操作交错写库、图钉与库对不上。 */
    private var toggling = false

    private val menuItem: MenuItem? get() = toolbar.menu.findItem(R.id.action_pin)

    init {
        render(visible = false, pinned = false, animate = false)
    }

    /** chip 变化或页面回到前台（可能在别处取消了置顶）时重算图钉状态。 */
    fun refresh() {
        val terms = termsProvider()
        lookupJob?.cancel()
        if (terms.isEmpty()) {
            pinnedEntity = null
            render(visible = false, pinned = false, animate = false)
            return
        }
        lookupJob = activity.lifecycleScope.launch {
            val (withheld, match) = withContext(Dispatchers.IO) {
                SearchRiskPolicy.shouldWithhold(terms.joinToString(" ")) to findPinnedSearch(dao(), terms)
            }
            pinnedEntity = match
            render(visible = !withheld, pinned = match != null, animate = false)
        }
    }

    fun toggle() {
        val terms = termsProvider()
        if (terms.isEmpty() || toggling) return
        // 进行中的查询可能拿着旧结果回来覆盖本次操作的状态，先掐掉。
        lookupJob?.cancel()
        toggling = true
        val current = pinnedEntity
        // 预览读的是 feeds 的 uiState 快照，在主线程取好再切 IO。
        val preview = if (current == null) previewProvider() else emptyList()
        activity.lifecycleScope.launch {
            try {
                if (current == null) pin(terms, preview) else unpin(current)
            } finally {
                toggling = false
            }
        }
    }

    private suspend fun pin(terms: List<String>, preview: List<Illust>) {
        val keyword = terms.joinToString(" ")
        pinnedEntity = withContext(Dispatchers.IO) {
            val previewJson = buildPinnedTagPreviewJson(TagsBean().apply { name = keyword }, preview)
            PixivOperate.insertPinnedSearchHistory(
                keyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD, true, previewJson,
            )
            findPinnedSearch(dao(), terms)
        }
        render(visible = true, pinned = true, animate = true)
        Snackbar.make(
            snackbarAnchor,
            activity.getString(R.string.search_pinned_snack, searchTermsDisplayName(terms)),
            Snackbar.LENGTH_LONG,
        ).setAction(R.string.search_pin_snack_view) {
            activity.startActivity(
                Intent(activity, TemplateActivity::class.java)
                    .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PINNED_CONTENT.key),
            )
        }.show()
    }

    private suspend fun unpin(previous: SearchEntity) {
        withContext(Dispatchers.IO) {
            PixivOperate.insertPinnedSearchHistory(
                previous.keyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD, false,
            )
        }
        pinnedEntity = null
        render(visible = true, pinned = false, animate = true)
        Snackbar.make(
            snackbarAnchor,
            activity.getString(
                R.string.search_unpinned_snack,
                searchTermsDisplayName(splitSearchTerms(previous.keyword)),
            ),
            Snackbar.LENGTH_LONG,
        ).setAction(R.string.search_pin_snack_undo) {
            activity.lifecycleScope.launch {
                // 原样写回旧行：排序时间与预览图都还是取消前的样子，而不是当作一次新置顶。
                withContext(Dispatchers.IO) { dao().insert(previous) }
                refresh()
            }
        }.show()
    }

    private fun render(visible: Boolean, pinned: Boolean, animate: Boolean) {
        val item = menuItem ?: return
        item.isVisible = visible
        if (!visible) return
        item.setIcon(if (pinned) R.drawable.ic_pin_filled_24 else R.drawable.ic_pin_outline_24)
        item.title = activity.getString(if (pinned) R.string.search_unpin_action else R.string.search_pin_action)
        if (!animate) return
        val view = toolbar.findViewById<View>(R.id.action_pin) ?: return
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (pinned) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        )
        if (!motionEnabled()) return
        // 置顶：从 0.6 弹回并过冲一次；取消：只轻轻一缩。强弱对应「钉上 / 拔下」，不循环。
        view.animate().cancel()
        val from = if (pinned) 0.6f else 0.85f
        view.scaleX = from
        view.scaleY = from
        view.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(if (pinned) 420L else 200L)
            .setInterpolator(OvershootInterpolator(if (pinned) 3f else 0f))
            .start()
    }
}

private fun dao() = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao()
