package ceui.pixiv.chat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.animation.DecelerateInterpolator
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
    /** Tap on a bubble's quote block → the quoted message's `localKey` (= its client_msg_id). */
    private val onQuoteClick: ((quotedLocalKey: String) -> Unit)? = null,
) : BaseListAdapter<ChatMessageEntity, ChatMessageAdapter.BubbleHolder>(
    diffCallback(keySelector = { it.localKey })
) {

    /**
     * localKey of a row that should flash-highlight the next time it is bound
     * (set by [flashMessage] when the target isn't currently on screen — the
     * scroll brings it in, the bind fires the flash). One-shot.
     */
    private var pendingFlashKey: String? = null

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

    private fun palette(ctx: Context): V3Palette = cachedPalette ?: chatPalette(ctx).also { cachedPalette = it }

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

    /** Position of the row with [localKey] in the current list, or -1. */
    fun positionOf(localKey: String): Int = currentList.indexOfFirst { it.localKey == localKey }

    /**
     * Flash-highlight the row with [localKey] (jump-to-quoted-message feedback).
     * If its holder is attached, flash it now; otherwise arm [pendingFlashKey]
     * so the bind that happens when the caller scrolls it on screen flashes it.
     * Returns `false` when the row isn't in the list at all.
     */
    fun flashMessage(localKey: String, rv: RecyclerView): Boolean {
        val pos = positionOf(localKey)
        if (pos < 0) return false
        val holder = rv.findViewHolderForAdapterPosition(pos) as? BubbleHolder
        if (holder != null) {
            pendingFlashKey = null
            holder.flash(palette(rv.context))
        } else {
            pendingFlashKey = localKey
        }
        return true
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
        val palette = palette(ctx)
        // Quote accent follows the *quoted author's* identity: their name colour
        // in the public room (so the quote reads as "that person"), brand colour
        // for self / 1v1. Sent bubbles ignore this and use white-on-gradient.
        val quotedUid = item.replyToUid
        val quoteAccent = if (quotedUid != null && isGlobal && quotedUid != selfUid) {
            nameColor(ctx, quotedUid)
        } else {
            palette.primary
        }
        holder.bind(
            msg = item,
            avatarUrl = avatarUrl,
            older = older,
            isGlobal = isGlobal,
            selfUid = selfUid,
            palette = palette,
            nameColor = if (isGlobal && item.uid != selfUid) nameColor(ctx, item.uid) else 0,
            quoteAccent = quoteAccent,
            onAvatarClick = onAvatarClick,
            onQuoteClick = onQuoteClick,
        )
        holder.itemView.setOnLongClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongClick?.invoke(item)
            true
        }
        if (pendingFlashKey != null && pendingFlashKey == item.localKey) {
            pendingFlashKey = null
            holder.flash(palette)
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
        private val quote: View = itemView.findViewById(R.id.quote)
        private val quoteBar: View = itemView.findViewById(R.id.quote_bar)
        private val tvQuoteName: TextView = itemView.findViewById(R.id.tv_quote_name)
        private val tvQuoteText: TextView = itemView.findViewById(R.id.tv_quote_text)

        private var flashAnimator: ValueAnimator? = null

        fun bind(
            msg: ChatMessageEntity,
            avatarUrl: String?,
            older: ChatMessageEntity?,
            isGlobal: Boolean,
            selfUid: Long,
            palette: V3Palette,
            nameColor: Int,
            quoteAccent: Int,
            onAvatarClick: ((uid: Long) -> Unit)?,
            onQuoteClick: ((quotedLocalKey: String) -> Unit)?,
        ) {
            val ctx = itemView.context
            val d = ctx.resources.displayMetrics.density
            val text = msg.text.orEmpty()

            // A recycled holder may still be mid-flash from a jump-to-quote.
            cancelFlash()

            // Content: cap bubble width at ~70% of the screen (narrow phones →
            // tablets stay balanced), render pure-emoji messages jumbo & bubble-
            // less, and linkify URLs.
            tvContent.text = text
            val contentMax = (ctx.resources.displayMetrics.widthPixels * BUBBLE_WIDTH_RATIO).toInt()
            tvContent.maxWidth = contentMax
            // A quoted message forces the bubble chrome even for pure-emoji text —
            // the quote block needs a surface to sit on.
            val jumbo = !msg.isReply && isJumboEmoji(text)
            bindQuote(msg, selfUid, palette, quoteAccent, contentMax, d, onQuoteClick)
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

        /**
         * Quote block at the top of the bubble when [msg] is a reply.
         *
         *  - Sent (gradient) bubble: white 22% tonal container, white bar, white
         *    name, white 85% excerpt — everything stays on the brand gradient.
         *  - Received bubble: [quoteAccent] 8% fill + 15% hairline (one step
         *    lighter than the bubble's own surface), bar + name in the accent,
         *    excerpt in `v3_text_2`.
         *
         * Corner radius 12dp — one step in from the 18dp bubble so it reads as
         * a layer *inside* the bubble rather than a second bubble.
         */
        private fun bindQuote(
            msg: ChatMessageEntity,
            selfUid: Long,
            palette: V3Palette,
            quoteAccent: Int,
            contentMax: Int,
            d: Float,
            onQuoteClick: ((quotedLocalKey: String) -> Unit)?,
        ) {
            val quotedKey = msg.replyToCmid
            if (quotedKey == null) {
                quote.visibility = View.GONE
                quote.setOnClickListener(null)
                return
            }
            val ctx = itemView.context
            quote.visibility = View.VISIBLE

            val name = when {
                msg.replyToUid == selfUid -> ctx.getString(R.string.chat_self_label)
                !msg.replyToDisplayName.isNullOrBlank() -> msg.replyToDisplayName
                else -> "匿名_${msg.replyToUid}"   // server's anonymous-name convention
            }
            tvQuoteName.text = name
            val excerpt = msg.replyToText
            val unavailable = excerpt == null
            tvQuoteText.text = if (unavailable) ctx.getString(R.string.chat_reply_unavailable)
                               else excerpt!!.replace('\n', ' ')

            // Keep the quote inside the bubble's width cap: 10+12 padding, 3 bar, 9 gap.
            val innerMax = contentMax - (34 * d).toInt()
            tvQuoteName.maxWidth = innerMax
            tvQuoteText.maxWidth = innerMax

            val radius = 12 * d
            val fill: Int
            val stroke: Int
            val barColor: Int
            val nameColor: Int
            val textColor: Int
            if (isSent) {
                fill = 0x38FFFFFF
                stroke = 0
                barColor = Color.WHITE
                nameColor = Color.WHITE
                textColor = 0xD9FFFFFF.toInt()
            } else {
                fill = V3Palette.withAlpha(quoteAccent, if (palette.isDark) 0.14f else 0.08f)
                stroke = V3Palette.withAlpha(quoteAccent, 0.15f)
                barColor = quoteAccent
                nameColor = quoteAccent
                textColor = ContextCompat.getColor(ctx, R.color.v3_text_2)
            }
            val container = GradientDrawable().apply {
                cornerRadius = radius
                setColor(fill)
                if (stroke != 0) setStroke(maxOf(1, (0.5f * d).toInt()), stroke)
            }
            // Ripple clipped to the rounded container so the tap reads as "this block".
            val rippleColor = if (isSent) 0x33FFFFFF else V3Palette.withAlpha(quoteAccent, 0.16f)
            quote.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleColor),
                container,
                GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) },
            )
            quoteBar.background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(barColor)
            }
            tvQuoteName.setTextColor(nameColor)
            tvQuoteText.setTextColor(textColor)
            tvQuoteText.alpha = if (unavailable) 0.7f else 1f

            if (onQuoteClick != null && !unavailable) {
                quote.isClickable = true
                quote.setOnClickListener { onQuoteClick(quotedKey) }
            } else {
                quote.isClickable = false
                quote.setOnClickListener(null)
            }
        }

        /** Row-wide tonal flash (brand 18% → 0 over ~900ms) used by jump-to-quote. */
        fun flash(palette: V3Palette) {
            cancelFlash()
            val color = V3Palette.withAlpha(palette.primary, 0.18f)
            val bg = ColorDrawable(color)
            itemView.background = bg
            flashAnimator = ValueAnimator.ofInt(255, 0).apply {
                duration = 900L
                startDelay = 150L
                interpolator = DecelerateInterpolator()
                addUpdateListener { bg.alpha = it.animatedValue as Int }
                doOnEndOrCancel { itemView.background = null; flashAnimator = null }
                start()
            }
        }

        private fun cancelFlash() {
            flashAnimator?.cancel()
            flashAnimator = null
            itemView.background = null
        }

        private fun ValueAnimator.doOnEndOrCancel(block: () -> Unit) {
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = block()
                override fun onAnimationCancel(animation: android.animation.Animator) = block()
            })
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

/**
 * The chat screen's V3 palette: brand colour from [Shaft.getThemeColor] (NOT
 * `?attr/colorPrimary` — this screen overlays a full Material3 theme for its
 * StateLayout, which shadows colorPrimary; see NavExt) + current night mode.
 * Shared by the bubble adapter and the composer's reply strip so both paint
 * the same accent.
 */
internal fun chatPalette(ctx: Context): V3Palette {
    val brand = runCatching { Color.parseColor(Shaft.getThemeColor()) }
        .getOrDefault(ContextCompat.getColor(ctx, R.color.v3_purple))
    val isDark = (ctx.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return V3Palette(brand, isDark)
}
