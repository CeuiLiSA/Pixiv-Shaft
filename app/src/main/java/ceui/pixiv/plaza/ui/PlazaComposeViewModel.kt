package ceui.pixiv.plaza.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Illust
import ceui.lisa.network.PlazaResult
import ceui.lisa.network.ShaftApiV2Client
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.pixiv.db.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 发帖编辑器 ViewModel。
 *
 * 状态:已附 illust id 列表 (max 9) + 提交进行中 flag。文本字段不放
 * VM 里 (EditText 自管),只在 submit 时从 Fragment 拿。
 *
 * 提交时按 spec 校验 trim 后非空 + ≤500 code points + illust ≤ 9。
 * server 端会做权威校验,本地这层只是早失败避开 round trip。
 */
class PlazaComposeViewModel : ViewModel() {

    data class UiState(
        val attachedIllusts: List<Long> = emptyList(),
        // illust id → 缩略 URL,prefetchMeta 完成后填进来。UI 用这个直接 Glide,
        // 没的项 fallback 显示 illust id 占位(server 端拿到帖子后会补全 meta)。
        val thumbUrls: Map<Long, String> = emptyMap(),
        val isSending: Boolean = false,
    )

    sealed class Event {
        data object Sent : Event()
        data class Toast(val message: String) : Event()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events get() = _events.receiveAsFlow()

    // attached illust id → 完整 Illust(异步 fetch 到的)。POST 时塞进
    // ref_metas 让 server 直接 seed illust_meta。fetch 失败的 id 不在这个 map
    // 里,POST 仍照发,只是 server 那侧的 meta 留空(后续 events 兜底)。
    private val metaCache = mutableMapOf<Long, Illust>()

    fun attachIllust(id: Long) {
        if (id <= 0L) return
        if (_state.value.attachedIllusts.contains(id)) return
        if (_state.value.attachedIllusts.size >= 9) return
        // metaCache 上次会话已有(用户 remove 再 add 同 id),直接把 thumb 推回 state,
        // 免得 prefetchMeta 早 return 后 UI 一直显示 id 占位。
        val cachedThumb = metaCache[id]?.let { it.image_urls?.square_medium ?: it.image_urls?.medium }
        val nextThumbs = if (!cachedThumb.isNullOrEmpty()) {
            _state.value.thumbUrls + (id to cachedThumb)
        } else {
            _state.value.thumbUrls
        }
        _state.value = _state.value.copy(
            attachedIllusts = _state.value.attachedIllusts + id,
            thumbUrls = nextThumbs,
        )
        prefetchMeta(id)
    }

    fun removeIllust(id: Long) {
        _state.value = _state.value.copy(
            attachedIllusts = _state.value.attachedIllusts.filter { it != id },
            thumbUrls = _state.value.thumbUrls - id,
        )
    }

    /**
     * 后台拉一份 Illust 进 [metaCache]。优先用 ObjectPool,
     * 拿不到走 DB history,最后才 API。复用 ArtworkV3ViewModel 同款 fallback
     * 顺序(ObjectPool → DB → API)。
     * 任何环节失败都安静 swallow —— meta 是 best-effort hint,不是发帖前置。
     */
    private fun prefetchMeta(id: Long) {
        if (metaCache.containsKey(id)) return
        viewModelScope.launch {
            try {
                val bean = ObjectPool.getIllust(id).value
                    ?: fetchFromDbOrApi(id)
                if (bean != null) {
                    metaCache[id] = bean
                    // 把 thumb URL 推到 state,Adapter rebind 就能 Glide.load 出预览。
                    // square_medium 优先(列表 tile 是 1:1),没的退回 medium。
                    val thumb = bean.image_urls?.square_medium
                        ?: bean.image_urls?.medium
                    if (!thumb.isNullOrEmpty() && _state.value.attachedIllusts.contains(id)) {
                        _state.value = _state.value.copy(
                            thumbUrls = _state.value.thumbUrls + (id to thumb)
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "[PlazaCompose] prefetchMeta failed id=$id")
            }
        }
    }

    private suspend fun fetchFromDbOrApi(id: Long): Illust? {
        val ctx = Shaft.getContext()
        val fromDb = withContext(Dispatchers.IO) {
            AppDatabase.getAppDatabase(ctx).generalDao()
                .getByRecordTypeAndId(RecordType.VIEW_ILLUST_HISTORY, id)
                ?.typedObject<Illust>()
        }
        val illust = fromDb ?: Client.appApi.getIllust(id).illust ?: return null
        ObjectPool.update(illust)
        return illust
    }

    fun submit(context: Context, text: String, selfUid: Long) {
        if (_state.value.isSending) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _events.trySend(Event.Toast(context.getString(R.string.plaza_post_empty)))
            return
        }
        // server 用 code points 计长(spec §3),Kotlin String.length 是 UTF-16 units —
        // 大多数情况一致,补充字符 (emoji 等) 1 个 code point 但 String.length=2,
        // 这里也按 code point 算,跟 server 对齐避开 false positive。
        val codePoints = trimmed.codePointCount(0, trimmed.length)
        if (codePoints > 500) {
            _events.trySend(Event.Toast(context.getString(R.string.plaza_post_too_long)))
            return
        }

        _state.value = _state.value.copy(isSending = true)
        viewModelScope.launch {
            val attached = _state.value.attachedIllusts
            // 只把 fetch 成功的 Illust 塞进 ref_metas;没的 id 仍然在 refs.illust
            // 里被签,server 那边 meta 留空,后续靠 events/batch 兜底。
            val metas: Map<Long, Any> = attached
                .mapNotNull { id -> metaCache[id]?.let { id to it } }
                .toMap()
            val r = ShaftApiV2Client.createPlazaPost(
                uid = selfUid,
                text = trimmed,
                illust = attached,
                illustMetas = metas,
            )
            _state.value = _state.value.copy(isSending = false)
            when (r) {
                is PlazaResult.Ok -> {
                    _events.trySend(Event.Toast(context.getString(R.string.plaza_post_sent)))
                    _events.trySend(Event.Sent)
                }
                is PlazaResult.Err -> {
                    val msg = mapErrorMessage(context, r)
                    _events.trySend(
                        Event.Toast(context.getString(R.string.plaza_post_send_failed, msg))
                    )
                }
            }
        }
    }

    private fun mapErrorMessage(context: Context, err: PlazaResult.Err): String {
        return when (err.code) {
            "rate_limited" -> {
                val s = err.body?.retryAfterSeconds ?: 60
                context.getString(R.string.plaza_rate_limited, s.toInt())
            }
            "ts_skew" -> context.getString(R.string.plaza_ts_skew)
            "text_too_long" -> context.getString(R.string.plaza_post_too_long)
            "empty_text" -> context.getString(R.string.plaza_post_empty)
            "network" -> context.getString(R.string.chat_error_network_unavailable)
            else -> err.code
        }
    }
}
