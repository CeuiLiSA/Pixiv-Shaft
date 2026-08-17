package ceui.pixiv.chat.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.util.LinkifyCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.chat.base.BaseListAdapter
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.SendState
import java.text.BreakIterator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for chat messages — V3 redesign.
 *
 * Two view types dispatched on sender uid:
 *  - **Sent**     (msg.uid == selfUid) → right-aligned theme-gradient bubble + self avatar
 *  - **Received** (msg.uid != selfUid) → left-aligned surface bubble + peer avatar
 *
 * Timestamps live in two places (this is the "where to show send time" answer):
 *  1. a **per-bubble clock** (`HH:mm`) under every bubble, aligned to the
 *     bubble's side — so every message's exact time is one glance away; and
 *  2. a **centred time-group chip** (`今天 14:30` / `3/9 14:30`) shown above the
 *     first message of a new time cluster (gap > 5 min or a new calendar day),
 *     computed from the chronologically-older neighbour. No synthetic list
 *     items — the chip is a GONE-by-default header inside each bubble row, so
 *     the VM's message list + DiffUtil keying stay untouched.
 *
 * The sent bubble's brand gradient is built at runtime from
 * [Shaft.getThemeColor] (NOT `?attr/colorPrimary`: this screen overlays a full
 * Material3 theme for its StateLayout, which shadows colorPrimary — see NavExt).
 *
 * DiffUtil keyed on [ChatMessageEntity.localKey]. `reverseLayout = true` means
 * position 0 is the newest (bottom); position `p+1` is therefore the message
 * just *older* than position `p`.
 */
class ChatMessageAdapter(
    private val selfUid: Long,
    private val isGlobal: Boolean = false,
    private val onLongClick: ((ChatMessageEntity) -> Unit)? = null,
    private val onAvatarClick: ((uid: Long) -> Unit)? = null,
) : BaseListAdapter<ChatMessageEntity, ChatMessageAdapter.BubbleHolder>(
    diffCallback(keySelector = { it.localKey })
) {

    /** Self avatar URL — set once the logged-in user's profile is known. */
    var selfAvatarUrl: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyAvatarChanged(VIEW_TYPE_SENT)
        }

    /** Peer avatar URL — set after the peer's profile fetch completes (1v1 only). */
    var peerAvatarUrl: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyAvatarChanged(VIEW_TYPE_RECEIVED)
        }

    private var cachedPalette: V3Palette? = null
    private var nameColors: IntArray? = null

    private fun palette(ctx: Context): V3Palette = cachedPalette ?: run {
        val brand = runCatching { Color.parseColor(Shaft.getThemeColor()) }
            .getOrDefault(ContextCompat.getColor(ctx, R.color.v3_purple))
        val isDark = (ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        V3Palette(brand, isDark).also { cachedPalette = it }
    }

    /** Curated group-chat name palette; picked per sender uid for legibility. */
    private fun nameColor(ctx: Context, uid: Long): Int {
        val colors = nameColors ?: intArrayOf(
            ContextCompat.getColor(ctx, R.color.v3_blue),
            ContextCompat.getColor(ctx, R.color.v3_pink),
            ContextCompat.getColor(ctx, R.color.v3_green),
            ContextCompat.getColor(ctx, R.color.v3_purple),
            ContextCompat.getColor(ctx, R.color.v3_orange),
            ContextCompat.getColor(ctx, R.color.v3_gold),
        ).also { nameColors = it }
        val idx = ((uid % colors.size) + colors.size).toInt() % colors.size
        return colors[idx]
    }

    private fun notifyAvatarChanged(viewType: Int) {
        for (i in 0 until itemCount) {
            if (getItemViewType(i) == viewType) notifyItemChanged(i, PAYLOAD_AVATAR)
        }
    }

    override fun getDataItemViewType(item: ChatMessageEntity, position: Int): Int =
        if (item.uid == selfUid) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): BubbleHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layoutRes = if (viewType == VIEW_TYPE_SENT) {
            R.layout.chat_bubble_sent
        } else {
            R.layout.chat_bubble_received
        }
        return BubbleHolder(inflater, parent, layoutRes, viewType == VIEW_TYPE_SENT)
    }

    override fun onBindDataViewHolder(holder: BubbleHolder, item: ChatMessageEntity) {
        val avatarUrl = if (item.uid == selfUid) selfAvatarUrl else peerAvatarUrl
        // Chronologically-older neighbour drives the time-group boundary.
        val pos = holder.bindingAdapterPosition
        val older = if (pos != RecyclerView.NO_POSITION) currentList.getOrNull(pos + 1) else null
        val ctx = holder.itemView.context
        holder.bind(
            msg = item,
            avatarUrl = avatarUrl,
            older = older,
            isGlobal = isGlobal,
            palette = palette(ctx),
            nameColor = if (isGlobal && item.uid != selfUid) nameColor(ctx, item.uid) else 0,
            onAvatarClick = onAvatarClick,
        )
        holder.itemView.setOnLongClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongClick?.invoke(item)
            true
        }
    }

    class BubbleHolder(
        inflater: LayoutInflater,
        parent: ViewGroup,
        layoutRes: Int,
        private val isSent: Boolean,
    ) : RecyclerView.ViewHolder(inflater.inflate(layoutRes, parent, false)) {

        private val bubble: View = itemView.findViewById(R.id.bubble)
        private val tvContent: TextView = itemView.findViewById(R.id.tv_content)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvTimeGroup: TextView = itemView.findViewById(R.id.tv_time_group)
        private val ivAvatar: ImageView? = itemView.findViewById(R.id.iv_avatar)
        private val tvMonogram: TextView? = itemView.findViewById(R.id.tv_monogram) // received anon
        private val tvName: TextView? = itemView.findViewById(R.id.tv_name)   // received only
        private val ivState: ImageView? = itemView.findViewById(R.id.iv_state) // sent only

        fun bind(
            msg: ChatMessageEntity,
            avatarUrl: String?,
            older: ChatMessageEntity?,
            isGlobal: Boolean,
            palette: V3Palette,
            nameColor: Int,
            onAvatarClick: ((uid: Long) -> Unit)?,
        ) {
            val ctx = itemView.context
            val d = ctx.resources.displayMetrics.density
            val text = msg.text.orEmpty()

            // Content: cap bubble width at ~70% of the screen (narrow phones →
            // tablets stay balanced), render pure-emoji messages jumbo & bubble-
            // less, and linkify URLs.
            tvContent.text = text
            tvContent.maxWidth = (ctx.resources.displayMetrics.widthPixels * BUBBLE_WIDTH_RATIO).toInt()
            val jumbo = isJumboEmoji(text)
            val hasLinks = !jumbo && LinkifyCompat.addLinks(tvContent, Linkify.WEB_URLS)
            if (hasLinks) {
                tvContent.movementMethod = LinkMovementMethod.getInstance()
                tvContent.setLinkTextColor(if (isSent) Color.WHITE else palette.primary)
            } else {
                tvContent.movementMethod = null
            }
            tvContent.setTextSize(
                TypedValue.COMPLEX_UNIT_SP, if (jumbo) JUMBO_TEXT_SP else NORMAL_TEXT_SP,
            )

            tvTime.text = clock(msg.ts)

            // Time-group chip above the first message of a new time cluster.
            val showTimeGroup = older == null || isNewTimeGroup(older.ts, msg.ts)
            if (showTimeGroup) {
                tvTimeGroup.visibility = View.VISIBLE
                tvTimeGroup.text = timeGroupLabel(ctx, msg.ts)
            } else {
                tvTimeGroup.visibility = View.GONE
            }

            // Slack/Discord-style grouping: avatar + sender name render only on
            // the first message of a same-sender run (or right after a time
            // separator) — and the rhythm follows suit: tight gap inside a run,
            // wider gap between runs.
            val isGroupStart = showTimeGroup || older == null || older.uid != msg.uid
            val topGapDp = when {
                showTimeGroup -> 4
                isGroupStart -> 12
                else -> 2
            }
            itemView.updatePadding(top = (topGapDp * d).toInt(), bottom = (2 * d).toInt())

            val padH = (14 * d).toInt()
            val padV = (9 * d).toInt()

            if (isSent) {
                if (jumbo) {
                    bubble.background = null
                    bubble.setPadding(0, 0, 0, 0)
                } else {
                    bubble.setPadding(padH, padV, padH, padV)
                    // Brand gradient bubble (primary → +40° hue), tail top-right.
                    bubble.background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(palette.primary, palette.scrollProgressMid),
                    ).apply {
                        cornerRadii = floatArrayOf(
                            18 * d, 18 * d, 6 * d, 6 * d, 18 * d, 18 * d, 18 * d, 18 * d,
                        )
                    }
                }
                // Optimistic rows fade until the WS echo confirms; failed rows
                // surface a red marker (retry is a future affordance).
                bubble.alpha = if (msg.state == SendState.Sending) 0.6f else 1f
                ivState?.visibility = if (msg.state == SendState.Failed) View.VISIBLE else View.GONE
            } else {
                if (jumbo) {
                    bubble.background = null
                    bubble.setPadding(0, 0, 0, 0)
                } else {
                    bubble.setPadding(padH, padV, padH, padV)
                    bubble.setBackgroundResource(R.drawable.bg_chat_bubble_received)
                }
                bubble.alpha = 1f
                // Sender name only in the public room (1v1 identifies the peer
                // in the toolbar), and only at a group start; per-uid colour
                // for group legibility.
                tvName?.let { nameView ->
                    val name = msg.displayName?.takeIf { it.isNotBlank() }
                    if (isGroupStart && isGlobal && name != null) {
                        nameView.visibility = View.VISIBLE
                        nameView.text = name
                        nameView.setTextColor(nameColor)
                    } else {
                        nameView.visibility = View.GONE
                    }
                }
            }

            // Avatar (+ anon monogram) shows at a group start; otherwise the
            // slot stays as an INVISIBLE spacer so grouped bubbles keep indent.
            ivAvatar?.let { iv ->
                if (isGroupStart) {
                    iv.visibility = View.VISIBLE
                    bindAvatar(iv, tvMonogram, avatarUrl, nameColor, msg.displayName)
                    iv.setOnClickListener(
                        onAvatarClick?.let { cb -> View.OnClickListener { cb(msg.uid) } }
                    )
                    iv.isClickable = onAvatarClick != null
                } else {
                    iv.visibility = View.INVISIBLE
                    tvMonogram?.visibility = View.GONE
                    iv.setOnClickListener(null)
                    iv.isClickable = false
                }
            }
        }

        private fun bindAvatar(
            iv: ImageView,
            monogram: TextView?,
            avatarUrl: String?,
            nameColor: Int,
            name: String?,
        ) {
            if (avatarUrl.isNullOrBlank()) {
                Glide.with(iv).clear(iv)
                if (nameColor != 0) {
                    // Anonymous public-room user: solid identity colour + white
                    // monogram instead of an empty grey circle.
                    iv.setImageDrawable(ColorDrawable(nameColor))
                    monogram?.apply {
                        visibility = View.VISIBLE
                        text = monogramOf(name)
                    }
                } else {
                    iv.setImageResource(R.drawable.chat_avatar_placeholder)
                    monogram?.visibility = View.GONE
                }
                return
            }
            monogram?.visibility = View.GONE
            Glide.with(iv)
                .load(GlideUrlChild(avatarUrl))
                .placeholder(R.drawable.chat_avatar_placeholder)
                .into(iv)
        }
    }

    companion object {
        const val VIEW_TYPE_SENT = 0
        const val VIEW_TYPE_RECEIVED = 1
        private const val PAYLOAD_AVATAR = "avatar"

        /** Below this gap, consecutive messages share one time group. */
        private const val TIME_GROUP_GAP_MS = 5 * 60 * 1000L

        /** Bubble text is capped at this fraction of the screen width. */
        private const val BUBBLE_WIDTH_RATIO = 0.70

        private const val JUMBO_TEXT_SP = 40f
        private const val NORMAL_TEXT_SP = 15f

        /** First grapheme of a display name, for the anonymous-avatar monogram. */
        private fun monogramOf(name: String?): String {
            if (name.isNullOrBlank()) return "?"
            return String(Character.toChars(name.codePointAt(0)))
        }

        /**
         * True when a message is 1–3 emoji and nothing else — rendered jumbo &
         * bubble-less (iMessage/Telegram style). Modifiers (ZWJ, variation
         * selectors, skin tones, keycap, regional indicators) and whitespace
         * are tolerated; any real text disqualifies it.
         */
        private fun isJumboEmoji(raw: String): Boolean {
            val s = raw.trim()
            if (s.isEmpty() || s.length > 24) return false
            var i = 0
            var hasEmoji = false
            while (i < s.length) {
                val cp = s.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    isEmojiScalar(cp) -> hasEmoji = true
                    cp in 0x1F1E6..0x1F1FF -> hasEmoji = true                 // regional indicators
                    cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 -> Unit
                    cp in 0x1F3FB..0x1F3FF -> Unit                            // skin-tone modifiers
                    Character.isWhitespace(cp) -> Unit
                    else -> return false
                }
            }
            return hasEmoji && graphemeCount(s) in 1..3
        }

        private fun isEmojiScalar(cp: Int): Boolean =
            cp in 0x1F300..0x1FAFF ||
                cp in 0x1F000..0x1F0FF ||
                cp in 0x2600..0x27BF ||
                cp in 0x2B00..0x2BFF ||
                cp in 0x231A..0x231B ||
                cp in 0x23E9..0x23FA ||
                cp == 0x24C2 ||
                cp in 0x25AA..0x25FF ||
                cp in 0x2934..0x2935

        private fun graphemeCount(s: String): Int {
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            var count = 0
            while (it.next() != BreakIterator.DONE) count++
            return count
        }

        private fun isNewTimeGroup(olderTs: Long, ts: Long): Boolean =
            ts - olderTs > TIME_GROUP_GAP_MS || !sameDay(olderTs, ts)

        private fun clock(ts: Long): String =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

        private fun sameDay(a: Long, b: Long): Boolean {
            val ca = Calendar.getInstance().apply { timeInMillis = a }
            val cb = Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
                ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
        }

        private fun timeGroupLabel(ctx: Context, ts: Long): String {
            val now = Calendar.getInstance()
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            val clock = clock(ts)
            val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            return when {
                sameDay(ts, now.timeInMillis) ->
                    "${ctx.getString(R.string.timeline_today)} $clock"
                sameDay(ts, yesterday.timeInMillis) ->
                    "${ctx.getString(R.string.timeline_yesterday)} $clock"
                cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(ts))
                else ->
                    SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date(ts))
            }
        }
    }
}
