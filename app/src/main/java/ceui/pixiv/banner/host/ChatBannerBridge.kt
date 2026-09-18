package ceui.pixiv.banner.host

import android.content.Context
import android.util.LruCache
import ceui.lisa.R
import ceui.pixiv.api.Client
import ceui.pixiv.banner.BannerCategory
import ceui.pixiv.banner.BannerDisplayPolicy
import ceui.pixiv.banner.BannerIcon
import ceui.pixiv.banner.BannerManager
import ceui.pixiv.banner.BannerPriority
import ceui.pixiv.banner.BannerRequest
import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.api.ChatFrameDecoder
import ceui.pixiv.chat.api.ChatThreadId
import ceui.pixiv.chat.api.ShaftChatGateway
import ceui.pixiv.session.SessionManager
import ceui.pixiv.websocket.IncomingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

/**
 * Bridges every inbound chat [ChatFrame.Msg] from [ShaftChatGateway.incoming]
 * into a [BannerRequest.Text] on the [BannerManager].
 *
 * Suppression rules:
 *  - User's own echo (`uid == SessionManager.loggedInUid`) — pointless to
 *    banner a message you just sent.
 *  - Foreground activity is already showing the same chat room — the user
 *    is reading the conversation, an overlay would be redundant and obscure
 *    the very content they want to see.
 *  - No STARTED banner host (app backgrounded, or the foreground Activity is
 *    not a host) — the manager would hold the request for the next host, and
 *    a chat banner that resurfaces later is wrong: by then the message is
 *    already in the conversation, and the user may be sitting in that very
 *    room (the room-suppression above is evaluated at enqueue time, so a
 *    held banner would sail right past it).
 *
 * Newer messages in the same room use `Replace` (dedupKey="chat-<room>") so
 * they supersede the previous banner instead of stacking.
 *
 * What the card shows (see [toBannerRequest]):
 *  - caption: `私信` / `公屏闲聊`, plus `回复了你` when the message quotes one of ours
 *  - title: sender display name
 *  - message: the text, or "分享了一件作品" for illust-only messages
 *  - icon: the peer's pixiv avatar for 1v1 (resolved once per uid, bounded by
 *    [AVATAR_FETCH_TIMEOUT_MS]); the global room is anonymous so it only ever
 *    gets the Shaft logo — never fetch a real profile for it.
 */
class ChatBannerBridge(
    private val context: Context,
    private val bannerManager: BannerManager,
    private val scope: CoroutineScope,
    private val gateway: ShaftChatGateway,
) {

    /** uid → avatar url. Only successful lookups are cached; failures retry on the next message. */
    private val avatarCache = LruCache<Long, String>(64)

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            gateway.incoming
                .filterIsInstance<IncomingMessage.Text>()
                .map { ChatFrameDecoder.decode(it.text) }
                .filterIsInstance<ChatFrame.Msg>()
                // ⚠️ 下游 toBannerRequest 会做网络（拉头像，最多 AVATAR_FETCH_TIMEOUT_MS）。
                // gateway.incoming 底层是 onBufferOverflow=SUSPEND 的 SharedFlow，任何一个慢订阅者
                // 把缓冲填满都会挂住 WS 读循环、殃及聊天 UI / 持久化等所有订阅方。这里切出独立缓冲，
                // 突发时宁可丢掉最旧的几条 banner，也绝不让上游等我们。
                .buffer(BANNER_BUFFER, BufferOverflow.DROP_OLDEST)
                .mapNotNull { toBannerRequest(it) }
                .collect { bannerManager.enqueue(it) }
        }
        Timber.tag(TAG).i("ChatBannerBridge started")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun toBannerRequest(msg: ChatFrame.Msg): BannerRequest.Text? {
        val selfUid = SessionManager.loggedInUid
        if (selfUid != 0L && msg.uid == selfUid) return null
        val isGlobal = msg.room == ChatThreadId.ROOM_GLOBAL
        // 横幅开关只控制公开/全局房(默认关)，不影响用户主动发起的 1v1 私信通知。
        if (isGlobal && !publicChatBannerEnabled()) {
            return null
        }
        if (isViewingRoom(msg.room)) {
            Timber.tag(TAG).d("suppress banner: foreground is room=%s", msg.room)
            return null
        }
        if (!hasBannerHost()) return null
        val body = msg.text?.takeIf { it.isNotBlank() }
            ?: msg.illustId?.let { context.getString(R.string.chat_banner_shared_illust) }
            ?: return null
        val sender = msg.displayName?.takeIf { it.isNotBlank() } ?: "uid ${msg.uid}"
        val caption = buildList {
            add(context.getString(if (isGlobal) R.string.chat_room_global_title else R.string.chat_banner_caption_dm))
            if (selfUid != 0L && msg.replyTo?.uid == selfUid) add(context.getString(R.string.chat_banner_caption_reply_to_you))
        }.joinToString(" · ")
        // 公屏是匿名房（display_name 是「匿名_xxx」），拉真实头像等于把人去匿名化 —— 只给 Shaft logo。
        val icon: BannerIcon = if (isGlobal) {
            PLACEHOLDER_ICON
        } else {
            avatarFor(msg.uid)?.let { BannerIcon.Url(it) } ?: PLACEHOLDER_ICON
        }
        // 拉头像期间用户可能已经点进了这个房间（首屏正好在会话列表时最常见），再确认一次；
        // 同样地，这最多 AVATAR_FETCH_TIMEOUT_MS 的等待里 app 也可能已经退到后台。
        if (!hasBannerHost()) return null
        if (isViewingRoom(msg.room)) {
            Timber.tag(TAG).d("suppress banner after avatar fetch: foreground is room=%s", msg.room)
            return null
        }
        // 1v1 room id is a hashed pair → cannot reverse to peer uid. But the
        // sender (msg.uid, already filtered against self) IS the peer for 1v1,
        // so encode that directly. Global rooms drop the peer param.
        val deepLink = if (msg.room == ChatThreadId.ROOM_GLOBAL) {
            "shaft://chat?room=global"
        } else {
            "shaft://chat?peer=${msg.uid}"
        }
        return BannerRequest.Text(
            id = UUID.randomUUID().toString(),
            title = sender,
            message = body,
            caption = caption,
            icon = icon,
            dedupKey = "chat-${msg.room}",
            priority = BannerPriority.NORMAL,
            category = BannerCategory.Chat,
            policy = BannerDisplayPolicy.Replace,
            autoDismissMillis = 4000L,
            deepLink = deepLink,
            metadata = mapOf(
                "room" to msg.room,
                "uid" to msg.uid.toString(),
            ),
        )
    }

    /**
     * 私信对方的 pixiv 头像。首次按 uid 拉一次 `user/detail`，之后命中缓存零延迟；
     * 网络慢时最多等 [AVATAR_FETCH_TIMEOUT_MS]，超时 / 失败就退回占位图，绝不让 banner 干等。
     */
    private suspend fun avatarFor(uid: Long): String? {
        avatarCache.get(uid)?.let { return it }
        val url = withTimeoutOrNull(AVATAR_FETCH_TIMEOUT_MS) {
            try {
                Client.appApi.getUserProfile(uid).user?.profile_image_urls?.findMaxSizeUrl()
            } catch (e: CancellationException) {
                throw e // 超时 / bridge.stop() 的取消必须冒泡，不能当成「拉失败」吞掉
            } catch (e: Exception) {
                Timber.tag(TAG).v(e, "avatar fetch failed for uid=%d", uid)
                null
            }
        }
        if (!url.isNullOrBlank()) avatarCache.put(uid, url)
        return url
    }

    /**
     * Is the user already looking at the chat room that produced [msgRoom]?
     *
     * Asks the authoritative foreground-room registry that the chat fragment
     * itself maintains (`ShaftChatGateway.enterChatRoom` / `exitChatRoom` on its
     * own resume/pause, keyed on `ChatListViewModel.room`). This replaced the
     * earlier approach of reverse-engineering the room from the foreground
     * Activity's intent extras — that was fragile (depended on currentActivity
     * tracking + intent introspection) and is exactly what let global-room
     * banners slip through while the user was sitting in the global room.
     */
    private fun isViewingRoom(msgRoom: String): Boolean =
        gateway.foregroundChatRoom == msgRoom

    /**
     * 现在有没有宿主能把 banner 真的画出来。
     *
     * manager 在没有 STARTED 宿主时会把请求**保留**到下一个宿主（一次性引导必须这样，
     * 否则后台完成回填那条引导就永久丢了）。但聊天 banner 的价值只在「消息刚到」那一刻：
     * 补显时那条消息早就在会话列表里了，更糟的是用户很可能就停在那个房间——
     * 「正在看这个房间就不弹」是**入队那一刻**算的，被保留下来的请求会绕过它。
     * 所以这里自己掐掉，而不是让 manager 为聊天破坏保留语义（WS 后台仍在收，
     * 消息一条不丢，用户回来在会话里照常看到）。
     */
    private fun hasBannerHost(): Boolean {
        if (bannerManager.hasStartedHost.value) return true
        Timber.tag(TAG).d("suppress banner: no started banner host")
        return false
    }

    // 入口默认展示；公开聊天室横幅仍只在用户主动开启横幅开关时显示。
    // lite(google/Play)渠道直接判死:那边设置页没有横幅开关(见 FragmentSettingsExperimental),
    // 而开关值会随「设置备份还原」/ 云同步从 github 包带过来 —— 只认设置的话,Play 用户会收到
    // 一个自己关不掉的全局房 banner。这里只压全局房,1v1 私信 banner 仍照常。
    private fun publicChatBannerEnabled(): Boolean {
        if (ceui.lisa.BuildConfig.IS_LITE) return false
        val settings = ceui.lisa.activities.Shaft.sSettings ?: return false
        return settings.isShowChatRoomPushBanner
    }

    companion object {
        private const val TAG = "Chat-Banner-Bridge"
        private const val AVATAR_FETCH_TIMEOUT_MS = 1500L
        private const val BANNER_BUFFER = 64
        /** 匿名发言 / 头像拉不到时的头像位：Shaft 自己的 logo，而不是任何 pixiv 品牌图。 */
        private val PLACEHOLDER_ICON = BannerIcon.Resource(R.drawable.icon_shaft_with_bg)
    }
}
