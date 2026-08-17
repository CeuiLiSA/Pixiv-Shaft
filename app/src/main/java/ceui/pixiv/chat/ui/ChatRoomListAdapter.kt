package ceui.pixiv.chat.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.ChatItemRoomBinding
import ceui.lisa.databinding.ChatItemRoomHeroBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.chat.base.BaseListAdapter
import com.bumptech.glide.Glide

/**
 * Renders the chat conversation list in the V3 language.
 *
 * Two view types share one [ChatRoomEntry] model:
 *   - [TYPE_HERO] — the pinned public room (公屏闲聊). A theme-gradient flagship
 *     card so the one public space reads as special, not as "just another row".
 *   - [TYPE_ROW]  — 1v1 DMs. Clean divider-less rows; unread state is expressed
 *     with a theme-accent avatar ring + a theme-accent count pill.
 *
 * All accent colors come from [V3Palette] built off [Shaft.getThemeColor] — NOT
 * `?attr/colorPrimary`: this fragment used to overlay a full Material3 theme
 * (see NavExt) and even now we pin to the brand color so the accent matches the
 * user's AppTheme_IndexN exactly, in both day and night. The palette is cached
 * per adapter instance (recreated on config change, so night-mode flips refresh
 * it for free).
 */
class ChatRoomListAdapter(
    private val onClick: (ChatRoomEntry) -> Unit,
) : BaseListAdapter<ChatRoomEntry, RecyclerView.ViewHolder>(
    diffCallback(keySelector = { it.room }),
) {

    private var cachedPalette: V3Palette? = null

    private fun palette(ctx: Context): V3Palette = cachedPalette ?: run {
        val brand = runCatching { Color.parseColor(Shaft.getThemeColor()) }
            .getOrDefault(ContextCompat.getColor(ctx, R.color.v3_purple))
        val isDark = (ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        V3Palette(brand, isDark).also { cachedPalette = it }
    }

    override fun getDataItemViewType(item: ChatRoomEntry, position: Int): Int =
        if (item.kind == ChatRoomEntry.Kind.GLOBAL) TYPE_HERO else TYPE_ROW

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO -> HeroVH(ChatItemRoomHeroBinding.inflate(inflater, parent, false))
            else -> RowVH(ChatItemRoomBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindDataViewHolder(holder: RecyclerView.ViewHolder, item: ChatRoomEntry) {
        when (holder) {
            is HeroVH -> holder.bind(item, palette(holder.itemView.context), onClick)
            is RowVH -> holder.bind(item, palette(holder.itemView.context), onClick)
        }
    }

    // ── Hero: the public room ───────────────────────────────────────────────

    class HeroVH(private val binding: ChatItemRoomHeroBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatRoomEntry, palette: V3Palette, onClick: (ChatRoomEntry) -> Unit) {
            val density = binding.root.resources.displayMetrics.density
            // Theme-gradient card (primary → +40° hue). Elevation shadow follows
            // the drawable's rounded outline for a soft floating card.
            binding.heroCard.background = palette.seriesIconBg(24f * density)
            binding.tvName.text = item.title
            binding.tvPreview.text = buildPreview(binding.root.context, item).ifBlank { "—" }
            binding.tvTime.text = if (item.lastTs > 0L) formatRelative(item.lastTs) else ""
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    // ── Row: 1v1 DMs ─────────────────────────────────────────────────────────

    class RowVH(private val binding: ChatItemRoomBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatRoomEntry, palette: V3Palette, onClick: (ChatRoomEntry) -> Unit) {
            val ctx = binding.root.context
            val density = ctx.resources.displayMetrics.density

            // Avatar: peer's pixiv image once resolved, else the chat placeholder.
            // Loading goes through GlideUrlChild for the .pximg.net referer.
            val url = item.avatarUrl
            if (url.isNullOrBlank()) {
                Glide.with(binding.ivAvatar).clear(binding.ivAvatar)
                binding.ivAvatar.setImageResource(R.drawable.chat_avatar_placeholder)
            } else {
                Glide.with(binding.ivAvatar)
                    .load(GlideUrlChild(url))
                    .placeholder(R.drawable.chat_avatar_placeholder)
                    .into(binding.ivAvatar)
            }

            val unread = item.unreadCount > 0
            // Unread rows get a theme-accent avatar ring; read rows a faint hairline.
            binding.ivAvatar.borderColor =
                if (unread) palette.primary else ContextCompat.getColor(ctx, R.color.v3_border_2)

            binding.tvName.text = item.title
            binding.tvPreview.text = buildPreview(ctx, item).ifBlank { "—" }
            // Unread preview reads at full strength; read preview stays muted.
            binding.tvPreview.setTextColor(
                ContextCompat.getColor(ctx, if (unread) R.color.v3_text_1 else R.color.v3_text_2),
            )
            binding.tvTime.text = if (item.lastTs > 0L) formatRelative(item.lastTs) else ""

            if (unread) {
                binding.tvUnread.visibility = View.VISIBLE
                binding.tvUnread.background = palette.pillPrimary(10f * density)
                binding.tvUnread.text =
                    if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
            } else {
                binding.tvUnread.visibility = View.GONE
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private const val TYPE_HERO = 1
        private const val TYPE_ROW = 0

        /**
         * "<sender>: <text>" for global rows where the sender isn't the current
         * user; plain text for DMs (the row title already identifies the peer).
         * "你: " prefix for own messages on either kind so the last line's
         * direction reads at a glance.
         */
        private fun buildPreview(ctx: Context, item: ChatRoomEntry): String {
            val body = item.previewText
            if (body.isBlank()) return ""
            val selfUid = ceui.pixiv.session.SessionManager.loggedInUid
            return when {
                item.previewSenderUid != null && item.previewSenderUid == selfUid ->
                    "${ctx.getString(R.string.chat_preview_you_prefix)} $body"
                item.kind == ChatRoomEntry.Kind.GLOBAL &&
                    !item.previewSenderDisplayName.isNullOrBlank() ->
                    "${item.previewSenderDisplayName}: $body"
                else -> body
            }
        }

        private fun formatRelative(ts: Long): CharSequence =
            DateUtils.getRelativeTimeSpanString(
                ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            )
    }
}

/**
 * Display-ready row. Source-agnostic — populated either from the API
 * `ConversationItem` projection (production) or from local-DB previews
 * (offline fallback / pre-API smoke).
 */
data class ChatRoomEntry(
    val room: String,
    val kind: Kind,
    val title: String,
    val previewText: String,
    /** Last message's sender uid — null when the room has no message yet. */
    val previewSenderUid: Long? = null,
    /** Last message's display_name (server-resolved). Used for "X: 内容" prefix. */
    val previewSenderDisplayName: String? = null,
    /** Server autoincrement id of the last message. Needed to POST /read. */
    val lastMessageId: Long? = null,
    val lastTs: Long,
    /** Only present for 1v1 rooms — null for Global. Used to launch the
     *  per-conversation chat fragment with the correct peer. */
    val peerUid: Long? = null,
    /** Server-authoritative unread count for DM; 0 for global (server returns null,
     *  we coerce). */
    val unreadCount: Int = 0,
    /** Peer's pixiv profile-image URL for 1v1 rows; filled in lazily by the
     *  fragment after a `getUserProfile` lookup, null until then. Always null
     *  for the Global row (no single peer). */
    val avatarUrl: String? = null,
) {
    enum class Kind { GLOBAL, ONE_ON_ONE }
}
