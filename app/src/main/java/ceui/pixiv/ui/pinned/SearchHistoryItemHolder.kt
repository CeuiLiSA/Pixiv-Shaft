package ceui.pixiv.ui.pinned

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import ceui.lisa.R
import ceui.lisa.activities.OutWakeActivity
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.SearchEntity
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.utils.pinnedTagTranslation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 「我置顶的内容 → 搜索历史」列表条目（issue #1399）。
 *
 * 对应 `search_table` 中 `pinned = 0` 的全量本地记录（不设 50 条截断），
 * 涵盖标签关键词、插画 ID、画师 ID、小说 ID、URL 等全部历史搜索类型。
 */
class SearchHistoryItemHolder(val entity: SearchEntity) : FeedItem {

    override val feedKey: Any get() = entity.id

    val keyword: String = entity.keyword.orEmpty()

    val searchType: Int = entity.searchType

    val searchTime: Long = entity.searchTime

    /** 旧记录若曾置顶过且残留官方译名，仍参与检索与展示兜底。 */
    val translatedName: String? = pinnedTagTranslation(entity.previewIllustsJson)

    /** 只有关键字类型适合作为「置顶标签」放进左页标签栏。 */
    val canPinAsTag: Boolean = searchType == SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD
}

@DrawableRes
internal fun searchTypeIconRes(searchType: Int): Int = when (searchType) {
    SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID -> R.drawable.ic_collections_black_24dp
    SearchTypeUtil.SEARCH_TYPE_DB_USERID -> R.drawable.ic_setcat_person
    SearchTypeUtil.SEARCH_TYPE_DB_NOVELID -> R.drawable.ic_baseline_menu_book_24
    SearchTypeUtil.SEARCH_TYPE_DB_URL -> R.drawable.ic_v3_social_link
    else -> R.drawable.ic_search_black_24dp
}

@StringRes
internal fun searchTypeLabelRes(searchType: Int): Int = when (searchType) {
    SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD -> R.string.string_430
    SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID -> R.string.string_150
    SearchTypeUtil.SEARCH_TYPE_DB_USERKEYWORD -> R.string.string_432
    SearchTypeUtil.SEARCH_TYPE_DB_USERID -> R.string.string_152
    SearchTypeUtil.SEARCH_TYPE_DB_NOVELID -> R.string.string_153
    SearchTypeUtil.SEARCH_TYPE_DB_URL -> R.string.string_341
    else -> R.string.string_431
}

/** 列表副标题时间：今天显示相对/时分，今年显示月日时分，跨年补年份。 */
internal fun formatSearchHistoryTime(context: Context, timeMillis: Long): String {
    if (timeMillis <= 0L) return ""
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timeMillis }
    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> DateUtils.formatDateTime(context, timeMillis, DateUtils.FORMAT_SHOW_TIME)
        sameYear -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
    }
}

/**
 * 与 `FragmentSearch.handleHistoryClick` 保持一致的跳转语义，
 * 并将 `searchTime` 写回移到 IO 线程，不阻塞点击响应。
 */
internal fun openSearchEntity(context: Context, entity: SearchEntity, ioScope: CoroutineScope) {
    val keyword = entity.keyword?.trim().orEmpty()
    if (keyword.isEmpty()) return
    when (entity.searchType) {
        SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD,
        SearchTypeUtil.SEARCH_TYPE_DB_USERKEYWORD -> {
            context.startActivity(Intent(context, SearchActivity::class.java).apply {
                putExtra(Params.KEY_WORD, keyword)
                putExtra(Params.INDEX, 0)
            })
        }

        SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID -> {
            touchSearchEntityAsync(entity, ioScope)
            val id = keyword.toLongOrNull() ?: return
            PixivOperate.getIllustByID(id, context)
        }

        SearchTypeUtil.SEARCH_TYPE_DB_USERID -> {
            touchSearchEntityAsync(entity, ioScope)
            context.startActivity(Intent(context, UActivity::class.java).apply {
                putExtra(Params.USER_ID, Common.safeUserId(keyword).toLong())
            })
        }

        SearchTypeUtil.SEARCH_TYPE_DB_NOVELID -> {
            touchSearchEntityAsync(entity, ioScope)
            val id = keyword.toLongOrNull() ?: return
            PixivOperate.getNovelByID(id, context, null)
        }

        SearchTypeUtil.SEARCH_TYPE_DB_URL -> {
            touchSearchEntityAsync(entity, ioScope)
            context.startActivity(Intent(context, OutWakeActivity::class.java).apply {
                data = keyword.toUri()
            })
        }

        else -> {
            context.startActivity(Intent(context, SearchActivity::class.java).apply {
                putExtra(Params.KEY_WORD, keyword)
                putExtra(Params.INDEX, 0)
            })
        }
    }
}

private fun touchSearchEntityAsync(entity: SearchEntity, ioScope: CoroutineScope) {
    val copy = SearchEntity().apply {
        id = entity.id
        keyword = entity.keyword
        searchType = entity.searchType
        isPinned = entity.isPinned
        previewIllustsJson = entity.previewIllustsJson
        searchTime = System.currentTimeMillis()
    }
    ioScope.launch(Dispatchers.IO) {
        AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().insert(copy)
    }
}
