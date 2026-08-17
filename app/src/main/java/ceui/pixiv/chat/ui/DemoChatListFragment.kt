package ceui.pixiv.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.ChatFragmentDemoListBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.pixiv.chat.api.ChatConversationsRepository
import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.api.ChatThreadId
import ceui.pixiv.chat.api.HttpChatHistorySource
import ceui.pixiv.chat.api.ShaftChatGateway
import ceui.pixiv.chat.base.PagingFooterAdapter
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.chat.base.panel.PanelHost
import ceui.pixiv.chat.base.panel.attachBottomPanel
import ceui.pixiv.chat.base.setupToolbar
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.chat.data.ChatDatabase
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.RoomChatMessageStore
import ceui.pixiv.chat.vm.ChatListViewModel
import ceui.pixiv.session.SessionManager
import ceui.pixiv.websocket.WebSocketState
import com.hjq.toast.Toaster
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Chat screen wired to shaft-api-v2's uid-routing chat WebSocket.
 *
 * The WS itself is app-scoped — owned by [ShaftChatGateway] on top of
 * [ceui.pixiv.websocket.WebSocketManager]. This fragment opens a per-room
 * view onto the existing connection:
 *
 *  - **Global room** (no [ARG_PEER_UID] argument)
 *  - **1v1 room** with peer uid passed in via [ARG_PEER_UID] — room id
 *    derived locally via `ChatThreadId.oneOnOneThreadId(selfUid, peerUid)`
 *
 *  - history: [HttpChatHistorySource] → `GET /api/v1/chat/history?room=...`
 *  - live:    [ShaftChatGateway.chatStream] (filtered by computed room)
 *  - send:    optimistic local write → [ShaftChatGateway.send] → WS echo
 *             flips local row Sending → Delivered (doc §4.3)
 *
 * `reverseLayout = true` puts position 0 at the bottom of the screen
 * (newest message). [PagingFooterAdapter] sits at the END of the
 * [ConcatAdapter], which maps to the TOP of the screen with reverse layout
 * — where "loading older…" belongs.
 */
class DemoChatListFragment : Fragment(R.layout.chat_fragment_demo_list) {

    /** `null` → global room. Long > 0 → peer uid for 1v1. */
    private val peerUidArg: Long? by lazy {
        val v = arguments?.getLong(ARG_PEER_UID, 0L) ?: 0L
        if (v > 0L) v else null
    }

    private val viewModel: ChatListViewModel by viewModels {
        val appCtx = requireContext().applicationContext
        ChatListViewModel(
            selfUid = SessionManager.loggedInUid,
            toUid = peerUidArg,
            store = RoomChatMessageStore(
                ChatDatabase.getInstance(appCtx).chatMessageDao()
            ),
            historySource = HttpChatHistorySource(),
            stream = ShaftChatGateway.chatStream,
            sender = ShaftChatGateway::send,
            typingSender = ShaftChatGateway::sendTyping,
            typingFrames = ShaftChatGateway.typingFrames,
        )
    }

    private val binding by viewBinding(ChatFragmentDemoListBinding::bind)

    private var chatAdapter: ChatMessageAdapter? = null
    private var scrollToBottomOnNextUpdate = false

    /** "N 条新消息" pill state — only shown when a new message lands while the
     *  user is scrolled up reading history. */
    private var newMsgCount = 0
    private var lastNewestKey: String? = null

    /**
     * 当前 toolbar 标题的"非 typing"基线 —— typing 期间 title 被替换成
     * "对方正在输入…"(微信式),停止打字时恢复到这个值。
     * 初值 = initialTitle("uid=..." 或 "聊天室");fetchPeerProfile 成功后
     * 升级到真实 peer name。
     */
    private var baseTitle: String = ""

    /** Toolbar 主标题 view 引用,避开每次 typing emit 都 findViewById。 */
    private val titleView: TextView?
        get() = view?.findViewById(R.id.tv_title)

    /** Send button gating (doc §12): WS Connected + has text + not rate-limited. */
    private var wsConnected = false
    private var rateLimitCoolDown = false
    // Public room closed by admin (global_send_enabled=false). Only gates the
    // global room; 1v1 always sends. Tracked from ShaftChatGateway.globalSendEnabled.
    private var globalSendClosed = false

    /** null peer arg ⇒ the public broadcast room. */
    private val isGlobalRoom: Boolean get() = peerUidArg == null

    /**
     * TemplateActivity declares `windowSoftInputMode="adjustPan"` in the
     * manifest — fine for most fragments but breaks the chat screen:
     * `adjustPan` translates the **entire window** upward to keep the
     * focused EditText above the keyboard, which scrolls the toolbar off
     * the top and leaves a black band at the bottom where the window
     * used to be.
     *
     * We need `adjustResize` so that:
     *  - parent ConstraintLayout's height shrinks by the IME inset
     *  - bottom-anchored `emoji_panel` / `input_bar` lift up
     *  - top-anchored `app_bar_layout` (toolbar) **stays put**
     *
     * Set on fragment resume, restore on pause — local to the chat screen
     * so we don't change behavior for other fragments TemplateActivity hosts.
     */
    private var previousSoftInputMode: Int = INVALID_SOFT_INPUT_MODE

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Title: 1v1 starts with a placeholder uid label and is overwritten
        // once getUserProfile() resolves the peer's display name. Global
        // room shows the canonical drawer entry — there is no single peer.
        val initialTitle = if (peerUidArg != null) "uid=$peerUidArg"
                           else getString(R.string.chat_drawer_entry)
        baseTitle = initialTitle
        setupToolbar(title = initialTitle, showBack = true)

        // ── Bottom panel (emoji ↔ keyboard) ──────────────────────────
        attachBottomPanel(
            host = object : PanelHost {
                override val panelRoot get() = binding.root
                override val panelView get() = binding.emojiPanel
                override val panelInputView get() = binding.etInput
                override val panelContentView get() = binding.recyclerView
                override val panelToggleButton get() = binding.btnEmoji
                override val panelToggleIconRes get() = R.drawable.chat_ic_emoji
                override val keyboardToggleIconRes get() = R.drawable.chat_ic_keyboard
                override fun onAnchorContent() {
                    binding.recyclerView.scrollToPosition(0)
                }
            },
        )
        binding.emojiPanel.onEmojiClick = { emoji ->
            binding.etInput.text?.insert(binding.etInput.selectionStart, emoji)
        }

        // ── Input ────────────────────────────────────────────────────
        setupInput()

        // ── RecyclerView ─────────────────────────────────────────────
        // Adapter compares msg.uid against selfUid for right-vs-left bubble.
        // selfUid = pixiv SessionManager.loggedInUid — same identity the WS
        // handshake authenticates with, so echoes line up.
        val chatAdapter = ChatMessageAdapter(
            selfUid = SessionManager.loggedInUid,
            isGlobal = isGlobalRoom,
            onLongClick = { msg -> showMessageActions(msg) },
            onAvatarClick = { uid -> openUserProfile(uid) },
        ).also { this.chatAdapter = it }
        val footerAdapter = PagingFooterAdapter().apply {
            onRetry = { viewModel.retryPaging() }
        }

        val layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
            stackFromEnd = true
        }

        binding.recyclerView.apply {
            this.layoutManager = layoutManager
            adapter = ConcatAdapter(chatAdapter, footerAdapter)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) paginateIfNeeded()
                    if (newMsgCount > 0 && layoutManager.findFirstVisibleItemPosition() <= 0) {
                        resetNewMsgPill()
                    }
                }
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                    if (layoutManager.findFirstVisibleItemPosition() <= 1) {
                        viewModel.trimWindow()
                    } else {
                        paginateIfNeeded()
                    }
                }
            })
        }

        binding.newMsgPill.setOnClickListener {
            binding.recyclerView.scrollToPosition(0)
            resetNewMsgPill()
        }

        binding.stateLayout.setOnRetryClickListener { viewModel.retry() }
        setupMessageActionResult()

        // Self avatar — available synchronously from the cached profile.
        // Peer avatar — fetched lazily (1v1 only); no-op in the global room.
        bindSelfAvatar(chatAdapter)
        peerUidArg?.let { peerUid ->
            // Title tap → peer profile. The classic Shaft toolbar doesn't
            // host a peer avatar (unlike the previous Material3 design);
            // the per-bubble avatar tap (wired by the adapter) is still
            // the primary affordance for opening the peer profile.
            view.findViewById<View>(R.id.tv_title)?.setOnClickListener {
                openUserProfile(peerUid)
            }
            fetchPeerProfile(peerUid, chatAdapter)
        }

        launchSuspend {
            launch { observePageState() }
            launch { observePagingFooter(footerAdapter) }
            launch { observeMessages(chatAdapter, footerAdapter, layoutManager) }
            launch { observeConnection() }
            launch { observeServerErrors() }
            launch { observeGlobalSendState() }
            launch { observeReplacedByOtherDevice() }
            launch { observeFatalAuth() }
            launch { observeMarkRead() }
            launch { observePeerTyping() }
        }
    }

    /**
     * DM-only: every time the visible messages list bumps the max server id,
     * POST /api/v1/chat/conversations/<room>/read so the conversation list's
     * unread badge can decay authoritatively.
     *
     * Skipped for the global room (server returns 400 read_not_supported_for_global)
     * and for WS-only pushes that don't carry an `id` field — those messages
     * are read once /history backfills them with a real id, at which point
     * this effect picks up the new max and fires.
     *
     * Strict-monotonic dedup (`maxServerId > lastSentReadId`) keeps us from
     * spamming /read on every list re-emit when nothing has actually
     * advanced (e.g. pagination loading older rows, optimistic-send rows
     * flipping state).
     */
    private suspend fun observeMarkRead() {
        val peerUid = peerUidArg ?: return
        val selfUid = SessionManager.loggedInUid
        if (selfUid <= 0L) return
        val room = runCatching { ChatThreadId.oneOnOneThreadId(selfUid, peerUid) }
            .getOrElse {
                Timber.tag("Chat-Read").w(it, "skip markRead: cannot derive room for self=%d peer=%d", selfUid, peerUid)
                return
            }
        val repo = ChatConversationsRepository()
        var lastSentReadId = 0L
        viewModel.messages.collect { messages ->
            val maxServerId = messages.maxOfOrNull { it.serverId ?: 0L } ?: 0L
            if (maxServerId > lastSentReadId) {
                lastSentReadId = maxServerId
                try {
                    repo.markRead(selfUid, room, maxServerId)
                } catch (t: Throwable) {
                    // Network errors / 404 not_a_member shouldn't break the
                    // chat UI; user can keep reading either way.
                    Timber.tag("Chat-Read").w(
                        t, "markRead failed room=%s id=%d", room, maxServerId,
                    )
                }
            }
        }
    }

    // ── Avatar / peer-profile wiring ────────────────────────────────────

    private fun bindSelfAvatar(adapter: ChatMessageAdapter) {
        val url = SessionManager.loggedInUser?.profile_image_urls?.findMaxSizeUrl()
        adapter.selfAvatarUrl = url
    }

    /**
     * Routes every avatar tap (toolbar + bubbles) into [UActivity], which
     * is the canonical entry for profile pages — it forwards to
     * [ceui.lisa.activities.UserActivityV3] when the user has v3 settings
     * enabled, so we don't have to branch here.
     *
     * UActivity reads the uid as Int; the cast is safe since pixiv uids
     * fit well within Int.MAX_VALUE. Uid 0 means "unknown" — bail out.
     */
    private fun openUserProfile(uid: Long) {
        if (uid <= 0L) return
        val ctx = context ?: return
        val intent = Intent(ctx, UActivity::class.java).apply {
            putExtra(Params.USER_ID, uid.toInt())
        }
        ctx.startActivity(intent)
    }

    /**
     * Resolve the peer's display name + avatar, then push both into the
     * toolbar (title / `iv_peer_avatar`) and the bubble adapter
     * ([ChatMessageAdapter.peerAvatarUrl]). All updates happen on the
     * main thread inside the view-lifecycle scope, so a quick back-press
     * before the call returns cancels cleanly.
     *
     * Errors are swallowed — the screen stays usable with the fallback
     * "uid=…" title and placeholder avatar.
     */
    private fun fetchPeerProfile(peerUid: Long, adapter: ChatMessageAdapter) {
        viewLifecycleOwner.lifecycleScope.launch {
            val user = runCatching { Client.appApi.getUserProfile(peerUid).user }
                .getOrNull() ?: return@launch
            user.name?.takeIf { it.isNotBlank() }?.let { name ->
                baseTitle = name
                // 只有当前不在 typing 状态时才直接覆盖 title;typing 中
                // peerTyping observer 会用更新后的 baseTitle 在 stop 时恢复。
                if (viewModel.peerTyping.value.isTyping.not()) {
                    titleView?.text = name
                }
            }
            val avatarUrl = user.profile_image_urls?.findMaxSizeUrl()
            adapter.peerAvatarUrl = avatarUrl
            // Toolbar no longer hosts a peer-avatar slot (classic Shaft
            // toolbar = title + nav back only). Peer avatar still lives
            // on each inbound bubble via the adapter — same Glide load
            // path, just at the message level.
        }
    }

    // ── Message actions ─────────────────────────────────────────────────

    private fun showMessageActions(msg: ChatMessageEntity) {
        MessageActionsSheet.newInstance(msg.localKey, msg.text)
            .show(childFragmentManager, MessageActionsSheet.TAG)
    }

    private fun setupMessageActionResult() {
        childFragmentManager.setFragmentResultListener(
            MessageActionsSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val action = bundle.getString(MessageActionsSheet.RESULT_ACTION) ?: return@setFragmentResultListener
            val localKey = bundle.getString(MessageActionsSheet.RESULT_LOCAL_KEY) ?: return@setFragmentResultListener
            handleMessageAction(action, localKey)
        }
    }

    private fun handleMessageAction(action: String, localKey: String) {
        when (action) {
            MessageActionsSheet.ACTION_COPY -> copyMessage(localKey)
            MessageActionsSheet.ACTION_REPLY -> {
                Toaster.showShort("回复（TODO）")
            }
            MessageActionsSheet.ACTION_FORWARD -> {
                Toaster.showShort("转发（TODO）")
            }
            MessageActionsSheet.ACTION_DELETE -> confirmDeleteMessage(localKey)
        }
    }

    private fun copyMessage(localKey: String) {
        val text = viewModel.messages.value
            .find { it.localKey == localKey }
            ?.text ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toaster.showShort("已复制")
    }

    private fun confirmDeleteMessage(localKey: String) {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle("删除消息")
            .setMessage("确定要删除这条消息吗？")
            .addAction("取消") { d, _ -> d.dismiss() }
            .addAction("删除") { d, _ ->
                d.dismiss()
                deleteMessage(localKey)
            }
            .create()
            .show()
    }

    private fun deleteMessage(localKey: String) {
        val dao = ChatDatabase.getInstance(requireContext()).chatMessageDao()
        viewLifecycleOwner.lifecycleScope.launch {
            dao.deleteByLocalKey(localKey)
            Toaster.showShort("已删除")
        }
    }

    // ── Input bar ───────────────────────────────────────────────────────

    private fun setupInput() {
        // Brand-tint the send button. This screen overlays a full Material3
        // theme (for its StateLayout), which shadows colorPrimary with the M3
        // baseline tone — so the default Filled button would render M3 purple,
        // clashing with the brand-green toolbar + sent bubbles. Pull the real
        // brand colour (same source as the bubbles) and build a state list that
        // still dims when the button is disabled.
        runCatching { Color.parseColor(Shaft.getThemeColor()) }.getOrNull()?.let { brand ->
            binding.btnSend.backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(brand, ColorUtils.setAlphaComponent(brand, 0x40)),
            )
            binding.btnSend.iconTint = ColorStateList.valueOf(Color.WHITE)
        }
        binding.etInput.doAfterTextChanged { text ->
            refreshSendEnabled()
            // Outbound typing signal — DM-only, VM short-circuits global.
            // VM debounces internally (~4s between start frames), so it's
            // safe to call on every keystroke. Empty input → explicit stop
            // so peer's indicator clears the instant the user deletes
            // everything, not after the 5s timeout on their end.
            if (text.isNullOrEmpty()) {
                viewModel.notifyTypingStop()
            } else {
                viewModel.notifyTyping()
            }
        }
        binding.btnSend.setOnClickListener { sendMessage() }
        refreshSendEnabled()
    }

    private fun refreshSendEnabled() {
        // Public room closed by admin → lock the input entirely so a rejected
        // message is never optimistically appended in the first place (vs the
        // reactive removal fallback in observeServerErrors). 1v1 is never gated.
        val closed = isGlobalRoom && globalSendClosed
        val hasText = !binding.etInput.text.isNullOrBlank()
        binding.btnSend.isEnabled = hasText && wsConnected && !rateLimitCoolDown && !closed
        binding.etInput.isEnabled = !closed
        binding.etInput.hint = getString(
            if (closed) R.string.chat_global_closed_hint else R.string.chat_input_hint
        )
    }

    /**
     * Track the public-room send switch (gateway merges hello + live
     * `global_send_state` pushes). When it closes while the user sits in the
     * global room, lock input immediately — no flash, nothing appended. Reopens
     * re-enable live. Tracked for all rooms but only *gates* the global room.
     */
    private suspend fun observeGlobalSendState() {
        ShaftChatGateway.globalSendEnabled.collect { enabled ->
            globalSendClosed = !enabled
            refreshSendEnabled()
        }
    }

    /**
     * VM owns the full optimistic-send lifecycle (doc §4.3):
     * generates `client_msg_id`, writes the local row `state=Sending`,
     * dispatches via gateway, and listens for the WS echo to flip
     * `state=Delivered`. UI here just collects the boolean accept signal
     * for the immediate Toast + input clear.
     */
    private fun sendMessage() {
        val text = binding.etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val accepted = viewModel.sendText(text)
            if (!accepted) {
                Toaster.showShort("发送失败,请稍后重试")
                return@launch
            }
            binding.btnSend.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            // `.clear()` triggers TextWatcher synchronously → the
            // doAfterTextChanged listener calls notifyTypingStop() in the
            // same call stack. No explicit follow-up call needed here.
            binding.etInput.text?.clear()
            scrollToBottomOnNextUpdate = true
        }
    }

    // ── Observers ───────────────────────────────────────────────────────

    private suspend fun observePageState() {
        viewModel.pageState.collect { state ->
            binding.stateLayout.setState(state)
        }
    }

    private suspend fun observePagingFooter(footerAdapter: PagingFooterAdapter) {
        viewModel.pagingState.collect { state ->
            footerAdapter.setPagingState(state)
        }
    }

    private suspend fun observeConnection() {
        ShaftChatGateway.state.collect { state ->
            wsConnected = state is WebSocketState.Connected
            refreshSendEnabled()
        }
    }

    /**
     * Per doc §3.2 / §12: server `err` frames with `client_msg_id` anchor
     * to a specific local row (mark Failed); without cmid, the VM
     * falls back to "most recent Sending". Additionally, `rate_limited`
     * triggers the 1-second input cool-down.
     *
     * `collectLatest` cancels the prior body on each new err so a back-to-
     * back rate_limited resets the cool-down timer rather than stacking.
     */
    private suspend fun observeServerErrors() {
        ShaftChatGateway.errorFrames.collectLatest { err ->
            // global_send_disabled = 管理员关闭了公共聊天室发言:重试无意义,这条
            // 消息从未被接受,直接移除该乐观行(否则会留一条看着像已发的气泡)。
            // 其余错误维持"标记 Failed"语义(用户可重试),cmid==null 时 VM 兜底
            // 锚定到最近一条 Sending 行。
            if (err.code == "global_send_disabled") {
                viewModel.removeByClientMsgId(err.clientMsgId)
            } else {
                viewModel.markFailedByClientMsgId(err.clientMsgId)
            }

            if (err.code == "rate_limited") {
                rateLimitCoolDown = true
                refreshSendEnabled()
                Toaster.showShort("发送太频繁,请稍候")
                delay(1_000)
                rateLimitCoolDown = false
                refreshSendEnabled()
            } else {
                Toaster.showShort(friendlyChatErrorMessage(err))
            }
        }
    }

    /**
     * 把服务端 err 帧转成用户能看懂的中文提示,绝不把机器码(如
     * `global_send_disabled`)直接甩给用户。优先级:
     *   1. 服务端下发的 [ChatFrame.Err.message](策略类错误会带,服务端可改词
     *      而不用发版);
     *   2. 客户端按 code 的兜底映射;
     *   3. 通用文案。
     */
    private fun friendlyChatErrorMessage(err: ChatFrame.Err): String {
        err.message?.takeIf { it.isNotBlank() }?.let { return it }
        return when (err.code) {
            "global_send_disabled"  -> "聊天室已关闭发言"
            "room_forbidden"        -> "无法发送到该聊天"
            "self_chat_not_allowed" -> "不能给自己发消息"
            "bad_text_length"       -> "消息为空或过长"
            "bad_text"              -> "消息内容无效"
            "bad_illust_id"         -> "插画 ID 无效"
            "bad_client_msg_id"     -> "消息标识无效"
            "frame_too_large"       -> "消息过大"
            else                    -> "发送失败,请稍后重试"
        }
    }

    /**
     * Doc §1.1 "同 uid 顶号": server sent close(1008, "replaced") because
     * the same uid logged in on another device and pushed the max-5
     * per-uid connection cap over. Show a dedicated toast — do NOT
     * trigger reconnect (`DEFAULT_SHOULD_RECONNECT` already excludes 1008
     * from FATAL_CLOSE_CODES; user must explicitly come back).
     */
    private suspend fun observeReplacedByOtherDevice() {
        ShaftChatGateway.replacedByOtherDevice.collect {
            Toaster.showLong("账号在其它设备登录,聊天已断开")
        }
    }

    /**
     * Doc §2.2 / §7.3: 401 on the handshake (`bad_sig` / `bad_ts` /
     * `ts_skew` / `bad_uid`) is **fatal** — retrying with the same
     * credentials won't help. Surface a concrete user-actionable message
     * so they aren't left wondering why the input is greyed out forever.
     */
    private suspend fun observeFatalAuth() {
        ShaftChatGateway.fatalAuth.collect {
            Toaster.showLong("聊天认证失败 — 请检查系统时间是否正确,或重新登录")
        }
    }

    /**
     * 微信式 typing 指示:对方打字期间 toolbar title 整个替换成
     * 「xxx 正在输入…」/「对方正在输入…」,停止打字立即恢复到 [baseTitle]
     * (=peer 真实昵称 / 全员公屏标题)。
     *
     * Server 只在 DM 房间转发 typing 帧,VM 已经在 global 短路 —— 这个
     * collector 在 global 房里 peerTyping 恒为 Idle,无副作用。
     *
     * Display name 优先取 typing 帧自带的 display_name (canonical,跟
     * msg-bubble 命名一致);缺省时回退到 anon 文案。
     */
    private suspend fun observePeerTyping() {
        viewModel.peerTyping.collect { state ->
            val tv = titleView ?: return@collect
            tv.text = if (state.isTyping) {
                val name = state.displayName?.takeIf { it.isNotBlank() }
                if (name != null) getString(R.string.chat_peer_typing, name)
                else getString(R.string.chat_peer_typing_anon)
            } else {
                baseTitle
            }
        }
    }

    private suspend fun observeMessages(
        chatAdapter: ChatMessageAdapter,
        footerAdapter: PagingFooterAdapter,
        layoutManager: LinearLayoutManager,
    ) {
        viewModel.messages.collect { messages ->
            val wasAtBottom = layoutManager.findFirstVisibleItemPosition() <= 1
            val ownSend = scrollToBottomOnNextUpdate
            // A brand-new newest row (position 0 changed) means an inbound/own
            // message — NOT a pagination load, which only grows the older tail.
            val newestKey = messages.firstOrNull()?.localKey
            val isNewIncoming = lastNewestKey != null &&
                newestKey != null && newestKey != lastNewestKey
            lastNewestKey = newestKey

            chatAdapter.submitList(messages) {
                footerAdapter.setPagingState(viewModel.pagingState.value)

                if ((wasAtBottom || ownSend) && messages.isNotEmpty()) {
                    binding.recyclerView.scrollToPosition(0)
                    scrollToBottomOnNextUpdate = false
                    resetNewMsgPill()
                    if (isNewIncoming || ownSend) animateNewestBubbleIn()
                } else if (isNewIncoming) {
                    // New message while the user is reading older history →
                    // surface the "N 条新消息" pill instead of yanking them down.
                    newMsgCount++
                    showNewMsgPill()
                }

                binding.recyclerView.post { paginateIfNeeded() }
            }
        }
    }

    private fun showNewMsgPill() {
        binding.newMsgPill.visibility = View.VISIBLE
        binding.newMsgPill.text = getString(R.string.chat_new_messages, newMsgCount)
    }

    private fun resetNewMsgPill() {
        newMsgCount = 0
        binding.newMsgPill.visibility = View.GONE
    }

    /** Subtle fade + rise on the newest bubble when it lands at the bottom. */
    private fun animateNewestBubbleIn() {
        binding.recyclerView.post {
            val vh = binding.recyclerView.findViewHolderForLayoutPosition(0) ?: return@post
            val v = vh.itemView
            v.translationY = 14f * resources.displayMetrics.density
            v.alpha = 0f
            v.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun paginateIfNeeded() {
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val total = chatAdapter?.itemCount ?: return
        if (total == 0) return
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible >= 0 && total - 1 - lastVisible <= PREFETCH_THRESHOLD) {
            viewModel.loadOlder()
        }
    }

    override fun onResume() {
        super.onResume()
        // Authoritatively tell ChatBannerBridge which room is foreground so it
        // suppresses banners for it (vs the bridge guessing from Activity state).
        ShaftChatGateway.enterChatRoom(viewModel.room)
        val window = requireActivity().window
        previousSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onPause() {
        super.onPause()
        // Guarded exit tolerates resume/pause reordering across a room switch.
        ShaftChatGateway.exitChatRoom(viewModel.room)
        if (previousSoftInputMode != INVALID_SOFT_INPUT_MODE) {
            requireActivity().window.setSoftInputMode(previousSoftInputMode)
            previousSoftInputMode = INVALID_SOFT_INPUT_MODE
        }
    }

    override fun onDestroyView() {
        view?.findViewById<RecyclerView>(R.id.recycler_view)?.adapter = null
        chatAdapter = null
        super.onDestroyView()
    }

    companion object {
        private const val PREFETCH_THRESHOLD = 5
        /** Sentinel — softInputMode is a packed int; -1 is never a valid combo. */
        private const val INVALID_SOFT_INPUT_MODE = -1

        /** Fragment-arg key: peer pixiv uid for 1v1; absent / 0 → global room. */
        const val ARG_PEER_UID = "peerUid"

        /** Builder for the global chat fragment (default). */
        fun newInstanceGlobal(): DemoChatListFragment = DemoChatListFragment()

        /** Builder for a 1v1 chat fragment with the given peer pixiv uid. */
        fun newInstanceForPeer(peerUid: Long): DemoChatListFragment =
            DemoChatListFragment().apply { arguments = bundleOf(ARG_PEER_UID to peerUid) }
    }
}
