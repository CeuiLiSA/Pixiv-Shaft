package ceui.pixiv.ui.recommend

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.CellEventHistoryBinding
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.User
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.events.EventReporter
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.utils.clearGlideOnRecycle
import com.bumptech.glide.Glide
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 操作记录列表（feeds 框架版）。原 EventHistoryHolder + CommonAdapter 迁到标准 FeedSource/Renderer。
 * item.meta 里塞了原始 Illust / Novel / User，[EventHistoryFeedItem.parsed] lazy 反序列化一次。
 */
data class EventHistoryFeedItem(val item: ShaftApiV2.EventHistoryItem) : FeedItem {
    override val feedKey: Any get() = item.id

    // 解析一次缓存在 item 上，避免每次 onBind 都 parse
    val parsed: ParsedTarget? by lazy { parseTarget(item.target_type, item.meta) }
}

/**
 * FeedSource：调 shaft-api-v2 /events/history 拉当前 client_id 的事件流（id DESC，游标 = next_before）。
 * client_id 还没生成（EventReporter.init 未跑完）时返回空页 → 框架显示 empty 占位，不打服务端。
 */
class EventHistoryFeedSource : FeedSource<Long> {
    override suspend fun load(cursor: Long?): FeedPage<Long> {
        val cid = EventReporter.currentClientId()
        if (cid.isEmpty()) return FeedPage(emptyList(), null)
        val resp = ShaftApiV2Client.service.eventsHistory(
            clientId = cid, limit = 50, eventType = null, before = cursor,
        )
        // 预解析挪 Default：parsed 是整个 meta 的 gson 反序列化，lazy 首次求值原本落在首次
        // onBind（主线程滚动路径，每页 50 条逐条 parse）。这里在后台先把 lazy 焐热，bind 只读值
        // （对齐「bean 预解析别 per-bind」的既有裁决）。
        val items = withContext(Dispatchers.Default) {
            resp.items.map { EventHistoryFeedItem(it).also { feedItem -> feedItem.parsed } }
        }
        return FeedPage(items, resp.next_before)
    }
}

data class ParsedTarget(
    val title: String,
    val thumbUrl: String?,
    val openIntent: (Context) -> Unit,
)

private fun parseTarget(targetType: String, meta: JsonElement?): ParsedTarget? {
    // 无 payload 的事件 meta 是 null 或 JsonNull(见 EventHistoryItem.meta 注释),行降级为纯文字。
    val obj = meta as? JsonObject ?: return null
    val gson = Shaft.sGson
    return try {
        when (targetType) {
            "illust", "manga" -> {
                val bean = gson.fromJson(obj, Illust::class.java) ?: return null
                ParsedTarget(
                    title = bean.title ?: "",
                    thumbUrl = bean.image_urls?.medium ?: bean.image_urls?.square_medium,
                    openIntent = { ctx ->
                        val page = PageData(arrayListOf(bean))
                        Container.get().addPageToMap(page)
                        ctx.startActivity(Intent(ctx, VActivity::class.java).apply {
                            putExtra(Params.POSITION, 0)
                            putExtra(Params.PAGE_UUID, page.getUUID())
                        })
                    }
                )
            }
            "novel" -> {
                val bean = gson.fromJson(obj, Novel::class.java) ?: return null
                ParsedTarget(
                    title = bean.title ?: "",
                    thumbUrl = bean.image_urls?.medium ?: bean.image_urls?.square_medium,
                    openIntent = { ctx ->
                        ctx.startActivity(Intent(ctx, TemplateActivity::class.java).apply {
                            putExtra(Params.CONTENT, bean)
                            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
                            putExtra("hideStatusBar", true)
                        })
                    }
                )
            }
            "user" -> {
                val bean = gson.fromJson(obj, User::class.java) ?: return null
                ParsedTarget(
                    title = bean.name ?: "",
                    thumbUrl = bean.profile_image_urls?.medium,
                    openIntent = { ctx ->
                        ctx.startActivity(Intent(ctx, UActivity::class.java).apply {
                            putExtra(Params.USER_ID, bean.id.toInt())
                        })
                    }
                )
            }
            else -> null
        }
    } catch (e: Throwable) {
        Timber.tag("EventHistory").w(e, "parseTarget failed target_type=$targetType")
        null
    }
}

// ── Renderer（原 EventHistoryViewHolder 的绑定逻辑）。无多选/删除，纯只读行。──

fun eventHistoryRenderer(): FeedRenderer<EventHistoryFeedItem, CellEventHistoryBinding> =
    feedRenderer(
        inflate = CellEventHistoryBinding::inflate,
        create = { cell ->
            // 监听只挂一次，点击那一刻经 cell.item 取当下条目（绑定零 lambda 分配）
            cell.binding.root.setOnClickListener { v ->
                cell.itemOrNull?.parsed?.openIntent?.invoke(v.context)
            }
        },
        recycle = { it.binding.thumb.clearGlideOnRecycle() },
    ) { cell ->
        val binding = cell.binding
        val ev = cell.item.item
        val parsed = cell.item.parsed
        val context = binding.root.context

        val displayTitle = parsed?.title.orEmpty()
        val verb = verbLabel(context, ev.event_type)
        // "收藏了《XXX》" / "关注了 XXX" — 用户类型不加书名号，作品类型加
        binding.title.text = if (displayTitle.isEmpty()) verb
            else if (ev.target_type == "user") "$verb $displayTitle"
            else "$verb 《$displayTitle》"

        binding.subtitle.text = buildString {
            append(typeLabel(context, ev.target_type))
            append(" · ")
            append(formatRelative(ev.ts))
        }

        Glide.with(context)
            .load(GlideUtil.getUrl(parsed?.thumbUrl))
            .placeholder(R.color.v3_surface_2)
            .into(binding.thumb)

        binding.root.isClickable = parsed != null
    }

private fun verbLabel(ctx: Context, type: String): String = when (type) {
    "bookmark" -> ctx.getString(R.string.event_verb_bookmark)
    "unbookmark" -> ctx.getString(R.string.event_verb_unbookmark)
    "download" -> ctx.getString(R.string.event_verb_download)
    "follow" -> ctx.getString(R.string.event_verb_follow)
    "unfollow" -> ctx.getString(R.string.event_verb_unfollow)
    else -> type
}

private fun typeLabel(ctx: Context, type: String): String = when (type) {
    "illust" -> ctx.getString(R.string.type_illust)
    "manga" -> ctx.getString(R.string.type_manga)
    "novel" -> ctx.getString(R.string.type_novel)
    "user" -> ctx.getString(R.string.tab_user)
    else -> type
}

private fun formatRelative(ts: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(
        ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    )
