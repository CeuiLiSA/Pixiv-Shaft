package ceui.pixiv.banner.host

import ceui.pixiv.banner.BannerCategory
import ceui.pixiv.banner.BannerDisplayPolicy
import ceui.pixiv.banner.BannerManager
import ceui.pixiv.banner.BannerPriority
import ceui.pixiv.banner.BannerRequest
import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.api.ChatFrameDecoder
import ceui.pixiv.chat.api.ChatThreadId
import ceui.pixiv.chat.api.ShaftChatGateway
import ceui.pixiv.session.SessionManager
import ceui.pixiv.websocket.IncomingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
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
 *
 * Newer messages in the same room use `Replace` (dedupKey="chat-<room>") so
 * they supersede the previous banner instead of stacking.
 */
class ChatBannerBridge(
    private val bannerManager: BannerManager,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            ShaftChatGateway.incoming
                .filterIsInstance<IncomingMessage.Text>()
                .map { ChatFrameDecoder.decode(it.text) }
                .filterIsInstance<ChatFrame.Msg>()
                .mapNotNull { toBannerRequest(it) }
                .collect { bannerManager.enqueue(it) }
        }
        Timber.tag(TAG).i("ChatBannerBridge started")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun toBannerRequest(msg: ChatFrame.Msg): BannerRequest.Text? {
        val selfUid = SessionManager.loggedInUid
        if (selfUid != 0L && msg.uid == selfUid) return null
        // 试验性开关只 gate 公开/全局房 banner(默认关)。1v1 私信 banner **故意不 gate**:
        // 私信入口是用户主页的「发消息」按钮(UserActivityV3,独立于侧边栏「聊天室入口」开关),
        // 是用户主动发起的会话;若按聊天室入口开关把回复通知静默掉,反而是更严重的 bug。
        // ⚠️ 别为了「一致性」把这条收紧成 !showChatRoomEntry 就 return —— 会吞掉私信通知。
        if (msg.room == ChatThreadId.ROOM_GLOBAL && !publicChatBannerEnabled()) {
            return null
        }
        if (isViewingRoom(msg.room)) {
            Timber.tag(TAG).d("suppress banner: foreground is room=%s", msg.room)
            return null
        }
        val body = msg.text?.takeIf { it.isNotBlank() }
            ?: msg.illustId?.let { "[illust:$it]" }
            ?: return null
        val sender = msg.displayName?.takeIf { it.isNotBlank() } ?: "uid ${msg.uid}"
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
        ShaftChatGateway.foregroundChatRoom == msgRoom

    // 公开聊天室 push banner 同时受两个「试验性」开关约束:聊天室入口本身开启,且 banner 开关开启。
    // 任一关闭都不弹,因此设置页隐藏 push 行时即使其值残留为 true 也不会误弹。
    //
    // lite(google/Play)渠道直接判死:那边设置页没有这两个开关(见 FragmentSettingsExperimental),
    // 而开关值会随「设置备份还原」/ 云同步从 github 包带过来 —— 只认设置的话,Play 用户会收到
    // 一个自己关不掉的全局房 banner。这里只压全局房,1v1 私信 banner 仍照常(理由见上面的 ⚠️)。
    private fun publicChatBannerEnabled(): Boolean {
        if (ceui.lisa.BuildConfig.IS_LITE) return false
        val settings = ceui.lisa.activities.Shaft.sSettings ?: return false
        return settings.isShowChatRoomEntry && settings.isShowChatRoomPushBanner
    }

    companion object {
        private const val TAG = "Chat-Banner-Bridge"
    }
}
