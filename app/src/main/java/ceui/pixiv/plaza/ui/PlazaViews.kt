package ceui.pixiv.plaza.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.api.model.Illust
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.plaza.linkedPages
import ceui.pixiv.plaza.linkedWork
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.session.SessionManager
import ceui.pixiv.sticker.StickerImageView
import ceui.pixiv.sticker.StickerPicker
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.notification.routeNotificationTargetUrl
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.theme.*
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import jp.wasabeef.glide.transformations.BlurTransformation

/** Same blur as the artwork feeds' spoiler tiles (IllustStaggerRenderer). */
private const val LINKED_WORK_BLUR_RADIUS = 25
private const val LINKED_WORK_BLUR_SAMPLING = 3

/**
 * A linked work is masked by the feeds' own rule (muted, or AI under the blur setting) and,
 * because the plaza is one public feed for every account, also when the work is R-18.
 */
internal fun linkedWorkSpoilered(work: Illust): Boolean =
    work.isR18File() || IllustMuteStore.isMuted(work.id) || IllustNovelFilter.shouldBlurAi(work)

/**
 * Empty / error / not-found state on the feeds-framework recipe (`fragment_feed.xml`):
 * a 120dp illustration tinted with the readable accent at 60%, one 14sp explanation,
 * and at most one real action. Used centred on the feed and under "Comments (0)" on a post.
 */
internal class PlazaStateView(context: Context) : LinearLayout(context) {
    private val image =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            imageTintList =
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(V3Palette.from(context).textAccent, 153)
                )
        }
    private val text =
        context.label("", 14f, 400, context.color(R.color.v3_text_2)).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            lineHeightRatio(1.6f)
        }
    private var button: TextView? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(
            image,
            LayoutParams(context.dp(120), context.dp(120)).apply { bottomMargin = context.dp(8) },
        )
        addView(text, LayoutParams(-2, -2))
    }

    fun show(
        imageRes: Int,
        message: CharSequence,
        actionText: String? = null,
        action: (() -> Unit)? = null,
    ) {
        image.setImageResource(imageRes)
        text.text = message
        button?.let(::removeView)
        button = null
        if (actionText != null && action != null) {
            button =
                context.pillButton(actionText, primary = true) { action() }.also {
                    addView(it, LayoutParams(-2, -2).apply { topMargin = context.dp(4) })
                }
        }
        isVisible = true
    }

    fun hide() {
        isVisible = false
    }
}

internal fun Context.openPost(id: Long) =
    startActivity(
        Intent(this, TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA_POST_DETAIL.key)
            putExtra(PlazaPostDetailFragment.EXTRA_POST_ID, id)
        }
    )

internal fun Context.openComposer(replyTo: Long? = null) =
    startActivity(
        Intent(this, TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA_COMPOSE.key)
            replyTo?.let { putExtra(PlazaComposeFragment.ARG_REPLY_TO, it) }
        }
    )

internal fun Context.objectLabel(type: String?) =
    when (type) {
        "illust" -> getString(R.string.plaza_object_illust)
        "manga" -> getString(R.string.plaza_object_manga)
        "novel" -> getString(R.string.plaza_object_novel)
        else -> getString(R.string.plaza_object_user)
    }

internal fun Context.openObject(type: String?, id: Long) {
    routeNotificationTargetUrl(
        "pixiv://${when (type) { "user" -> "users"; "novel" -> "novels"; else -> "illusts" }}/$id"
    )
}

internal class PostAdapter(
    private val onLike: (PlazaPost) -> Unit,
    private val onDelete: (PlazaPost) -> Unit,
    private val onImage: (PlazaPost, Int, View) -> Unit,
    private val detailId: Long = 0,
    private val onReact: (PlazaPost, String) -> Unit = { _, _ -> },
    private val onReply: ((PlazaPost) -> Unit)? = null,
    /** Every bind, including rebinds while scrolling: the hook that notices expired media URLs. */
    private val onBind: (PlazaPost) -> Unit = {},
) :
    ListAdapter<PlazaPost, PostAdapter.Holder>(
        object : DiffUtil.ItemCallback<PlazaPost>() {
            override fun areItemsTheSame(a: PlazaPost, b: PlazaPost) = a.id == b.id

            override fun areContentsTheSame(a: PlazaPost, b: PlazaPost) = a == b

            // Rebind in the same holder; default change cross-fades would blink the entire post.
            override fun getChangePayload(oldItem: PlazaPost, newItem: PlazaPost): Any = Unit
        }
    ) {
    var busy: Set<Long> = emptySet()
        set(value) {
            if (field != value) {
                val changed = field + value
                field = value
                currentList.forEachIndexed { i, p ->
                    if (p.id in changed) notifyItemChanged(i, Unit)
                }
            }
        }

    /** Detail only: comments finished loading and there are none; rendered under the parent. */
    var emptyComments: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                val index = currentList.indexOfFirst { it.id == detailId }
                if (index >= 0) notifyItemChanged(index, Unit)
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(PostView(parent.context))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val post = getItem(position)
        onBind(post)
        holder.view.bind(
            post,
            post.id in busy,
            detailId == post.id,
            onLike,
            onDelete,
            onImage,
            onReact,
            detailId > 0 && detailId != post.id,
            onReply,
            emptyComments = emptyComments && detailId == post.id,
        )
    }

    override fun onViewRecycled(holder: Holder) {
        holder.view.clear()
    }

    class Holder(val view: PostView) : RecyclerView.ViewHolder(view)
}

/**
 * One post in three roles: an edge-to-edge feed row (hairline-separated, like the novel and
 * comment lists), the content-first parent on the detail page, and a connected 20/5 comment row
 * under it. The child order is stable (heading first, the image grid a direct child) so
 * recycling between roles only re-applies spacing and surfaces.
 */
internal class PostView(
    context: Context,
    private val viewerUid: () -> Long = { SessionManager.loggedInUid },
    private val spoilered: (Illust) -> Boolean = ::linkedWorkSpoilered,
) : LinearLayout(context) {
    /**
     * One cell of the media grid: an upload (signed media URL, opens the plaza viewer) or a page
     * of the linked work (pximg URL loaded on the reader's own connection, opens the artwork).
     * [key] decides whether a rebind can keep the ImageView; [url] alone changing means the same
     * bytes behind a rotated signature, so only a failed request reloads.
     */
    private class Tile(
        val key: String,
        val width: Int,
        val height: Int,
        val url: String,
        val blur: Boolean,
        val model: () -> GlideUrl?,
        val open: (View) -> Unit,
    )

    private val palette = V3Palette.from(context)
    private val imageRequests = Glide.with(this)
    private var renderedAvatar: Pair<Long, String?>? = null
    private var renderedTiles: List<Tile>? = null
    private var renderedWidth = -1
    private var detailMode = false
    private val avatar =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
        }
    private val name = context.label("", 15f, 600)
    private val time = context.label("", 12f, 500, context.color(R.color.v3_text_3))
    private val more =
        IconButton(
            context,
            R.drawable.ic_more_vert_black_24dp,
            context.getString(R.string.plaza_more_menu),
            context.color(R.color.v3_text_3),
        )
    private val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val title = context.label("", 16f, 600)
    private val body = context.label("", 15f, 400, context.color(R.color.v3_text_2))
    private val images = PlazaIllustGrid(context).apply { orientation = VERTICAL }
    private val reference = context.label("", 13f, 600, palette.textAccent)
    private val reactions = FlexboxLayout(context).apply { flexWrap = FlexWrap.WRAP }
    private val comments = LinearLayout(context).apply { orientation = VERTICAL }
    private val commentPreviewFill = ColorUtils.compositeColors(palette.alpha08, palette.cardFill)
    private val commentPreviewText = readablePreviewColor(context.color(R.color.v3_text_2))
    private val commentPreviewAccent = readablePreviewColor(palette.textAccent)
    private val commentsTitle = context.sectionLabel("")
    // Under "Comments (0)" on the detail page: the feeds-style empty state, tall enough to read
    // as a real area rather than a stray line (feeds centres it in a full pane).
    private val commentsEmpty =
        PlazaStateView(context).apply {
            minimumHeight = context.dp(320)
            isVisible = false
        }
    private val commentFooter = context.label("", 12f, 500, context.color(R.color.v3_text_3))

    private fun readablePreviewColor(color: Int): Int {
        val foreground = ColorUtils.compositeColors(color, commentPreviewFill)
        val text = context.color(R.color.v3_text_1)
        for (step in 0..10) {
            val candidate = ColorUtils.blendARGB(foreground, text, step / 10f)
            if (ColorUtils.calculateContrast(candidate, commentPreviewFill) >= 4.5) return candidate
        }
        return text
    }

    init {
        orientation = VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2)
        avatar.background = palette.pillSecondary(context.dpF(25f), context.hairlinePx())
        heading.addView(
            avatar,
            LayoutParams(context.dp(48), context.dp(48)).apply { marginEnd = context.dp(12) },
        )
        val identity =
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(name)
                addView(time, LayoutParams(-1, -2).apply { topMargin = context.dp(3) })
            }
        heading.addView(identity, LayoutParams(0, -2, 1f))
        heading.addView(
            more,
            LayoutParams(context.dp(48), context.dp(48)).apply { marginEnd = -context.dp(12) },
        )
        addView(heading)
        fun group(v: View, gap: Int = 12) {
            addView(v, LayoutParams(-1, -2).apply { topMargin = context.dp(gap) })
        }
        group(title)
        group(body, 4)
        group(images)
        group(reference)
        group(reactions)
        group(comments, 12)
        group(commentsTitle)
        group(commentsEmpty, 0)
        group(commentFooter, 8)
        body.lineHeightRatio(1.6f)
        reference.background =
            context.ripple(
                shape(context.dpF(12f), palette.alpha08, palette.alpha15, context.hairlinePx()),
                shape(context.dpF(12f), Color.WHITE),
            )
        reference.gravity = Gravity.CENTER_VERTICAL
        reference.setPadding(context.dp(12), context.dp(8), context.dp(10), context.dp(8))
        reference.minHeight = context.dp(40)
        ContextCompat.getDrawable(context, R.drawable.ic_v3_chevron_24)?.mutate()?.let {
            it.setTint(palette.textAccent)
            it.setBounds(0, 0, context.dp(16), context.dp(16))
            reference.setCompoundDrawablesRelative(null, null, it, null)
            reference.compoundDrawablePadding = context.dp(4)
        }
        reference.pressScale()
        comments.setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        commentsTitle.setPadding(0, context.dp(12), 0, 0)
    }

    fun bind(
        post: PlazaPost,
        busy: Boolean,
        detail: Boolean,
        onLike: (PlazaPost) -> Unit,
        onDelete: (PlazaPost) -> Unit,
        onImage: (PlazaPost, Int, View) -> Unit,
        onReact: (PlazaPost, String) -> Unit = { _, _ -> },
        comment: Boolean = false,
        onReply: ((PlazaPost) -> Unit)? = null,
        emptyComments: Boolean = false,
    ) {
        if (detailMode != detail) {
            renderedTiles = null
            detailMode = detail
        }
        applySurface(detail, comment)
        name.text = post.displayName
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        val avatarKey = post.uid to post.avatarUrl
        if (renderedAvatar != avatarKey) {
            imageRequests
                .load(post.avatarUrl?.let { GlideUrlChild(it) })
                .placeholder(R.drawable.chat_avatar_placeholder)
                .circleCrop()
                .into(avatar)
            renderedAvatar = avatarKey
        }
        avatar.contentDescription = context.getString(R.string.plaza_view_profile, post.displayName)
        avatar.setOnClickListener { context.openObject("user", post.uid) }
        time.text =
            DateFormat.getDateInstance(DateFormat.MEDIUM, context.resources.configuration.locales[0])
                .format(Date(post.createdAt))
        title.text = post.title
        title.isVisible = post.title.isNotBlank()
        title.textSize = if (detail) 24f else 16f
        title.typeface = context.v3Font(if (detail) 700 else 600)
        title.lineHeightRatio(if (detail) 1.3f else 1.4f)
        title.maxLines = if (detail) Int.MAX_VALUE else 2
        title.ellipsize = TextUtils.TruncateAt.END
        body.text = post.text
        body.isVisible = post.text.isNotBlank()
        body.textSize = if (detail) 16f else 15f
        body.setTextColor(context.color(if (detail) R.color.v3_text_1 else R.color.v3_text_2))
        body.maxLines = if (detail || comment) Int.MAX_VALUE else 3
        body.ellipsize = TextUtils.TruncateAt.END
        body.lineHeightRatio(if (detail) 1.7f else 1.6f)
        fun spacing(v: View, gap: Int, start: Int = 0) {
            v.layoutParams =
                (v.layoutParams as LayoutParams).apply {
                    topMargin = context.dp(gap)
                    marginStart = context.dp(start)
                }
        }
        spacing(title, if (detail) 16 else 12)
        spacing(body, if (post.title.isBlank()) 12 else 6)
        spacing(images, if (detail) 16 else 12)
        spacing(reference, if (detail) 16 else 12)
        spacing(reactions, if (detail) 12 else 8)
        spacing(comments, 12)
        spacing(commentsTitle, 20)
        setOnClickListener { if (!detail) context.openPost(post.id) }
        more.isVisible = !detail
        more.isEnabled = !busy
        more.setOnClickListener { context.showPostMenu(post, onDelete) }
        reference.isVisible = post.objectId != null
        reference.text =
            post.objectId
                ?.let {
                    context.getString(
                        R.string.plaza_reference_label,
                        context.objectLabel(post.objectType),
                        it,
                    )
                }
                .orEmpty()
        reference.setOnClickListener {
            post.objectId?.let { context.openObject(post.objectType, it) }
        }
        reference.layoutParams =
            (reference.layoutParams as LayoutParams).apply { width = LayoutParams.WRAP_CONTENT }
        val tiles = tilesOf(post, onImage)
        images.isVisible = tiles.isNotEmpty()
        images.onMeasured = { bindImages(tiles) }
        bindImages(tiles)
        bindReactions(post, busy, onLike, onReact, onReply)
        bindComments(post, detail, comment, onReply)
        commentsTitle.isVisible = detail
        commentsTitle.text = context.getString(R.string.plaza_comments_title, post.replyCount)
        if (detail && emptyComments)
            commentsEmpty.show(R.mipmap.empty_img, context.getString(R.string.plaza_comments_empty))
        else commentsEmpty.hide()
        if (comment) {
            (avatar.layoutParams as LayoutParams).apply {
                width = context.dp(36)
                height = context.dp(36)
                marginEnd = context.dp(8)
                avatar.layoutParams = this
            }
            time.isVisible = false
            name.textSize = 14f
            more.isVisible = false
            heading.gravity = Gravity.TOP
            listOf(title, body, images, reference, reactions, comments, commentFooter).forEach { v ->
                spacing(
                    v,
                    if (v == body && post.title.isBlank()) -16
                    else if (v == body) 4 else if (v == commentFooter) 8 else 12,
                    44,
                )
            }
        } else {
            (avatar.layoutParams as LayoutParams).apply {
                width = context.dp(48)
                height = context.dp(48)
                marginEnd = context.dp(12)
                avatar.layoutParams = this
            }
            name.textSize = 15f
            time.isVisible = true
            heading.gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun applySurface(detail: Boolean, comment: Boolean) {
        val lp = layoutParams as? ViewGroup.MarginLayoutParams
        when {
            detail -> {
                background = null
                foreground = null
                isClickable = false
                isFocusable = false
                setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
                lp?.setMargins(0, 0, 0, 0)
            }
            comment -> {
                // Comments are edge-to-edge like the artwork comment list; hairlines come from
                // the list decoration.
                background = null
                foreground = context.ripple(null, shape(0f, Color.WHITE))
                isClickable = true
                isFocusable = true
                setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                lp?.setMargins(0, 0, 0, 0)
            }
            else -> {
                // Feed rows are edge-to-edge like the novel and comment lists: no card, the
                // hairline between rows comes from the list's BottomDividerDecoration.
                background = null
                foreground =
                    context.ripple(null, shape(0f, Color.WHITE))
                isClickable = true
                isFocusable = true
                setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
                lp?.setMargins(0, 0, 0, 0)
            }
        }
        if (lp != null) layoutParams = lp
    }

    private fun bindReactions(
        post: PlazaPost,
        busy: Boolean,
        onLike: (PlazaPost) -> Unit,
        onReact: (PlazaPost, String) -> Unit,
        onReply: ((PlazaPost) -> Unit)?,
    ) {
        reactions.removeAllViews()
        val numbers = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0])
        fun chip(
            text: String,
            icon: Int? = null,
            selected: Boolean = false,
            description: String,
            stickerId: Long? = null,
            iconTint: Int? = null,
            click: (ImageView?) -> Unit,
        ) {
            // 32dp visual pill inside a 48dp touch target; selected = tint fill + stroke.
            val outer =
                FrameLayout(context).apply {
                    minimumHeight = context.dp(48)
                    minimumWidth = context.dp(48)
                    isClickable = true
                    isFocusable = true
                    isEnabled = !busy
                    isSelected = selected
                    alpha = if (busy) .6f else 1f
                    contentDescription = description
                    pressScale(.94f)
                }
            val inner =
                LinearLayout(context).apply {
                    minimumWidth = context.dp(44)
                    gravity = Gravity.CENTER
                    isDuplicateParentStateEnabled = true
                    setPadding(context.dp(12), 0, context.dp(12), 0)
                    background =
                        context.ripple(
                            if (selected)
                                shape(999f, palette.alpha20, palette.alpha30, context.hairlinePx())
                            else shape(999f, palette.alpha08),
                            shape(999f, Color.WHITE),
                        )
                }
            var glyph: ImageView? = null
            if (stickerId != null) {
                // The 64px asset is a quality tier; on screen the sticker matches an 18sp emoji.
                val size =
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        18f,
                        resources.displayMetrics,
                    ).toInt()
                inner.addView(
                    StickerImageView(context).apply { bind(stickerId, resourceSize = 64) },
                    LayoutParams(size, size),
                )
            }
            if (icon != null) {
                glyph =
                    ImageView(context).apply {
                        setImageResource(icon)
                        imageTintList =
                            ColorStateList.valueOf(
                                iconTint
                                    ?: if (selected) palette.textAccent
                                    else context.color(R.color.v3_text_2)
                            )
                    }
                inner.addView(glyph, LayoutParams(context.dp(20), context.dp(20)))
            }
            if (text.isNotEmpty()) {
                val split =
                    if (icon == null && stickerId == null) text.split(" ", limit = 2)
                    else listOf(text.trim())
                split.forEachIndexed { index, value ->
                    val emoji = split.size == 2 && index == 0
                    inner.addView(
                        context
                            .label(
                                value,
                                if (emoji) 18f else 14f,
                                if (emoji) 400 else 600,
                                if (selected) palette.textAccent
                                else context.color(R.color.v3_text_2),
                            )
                            .apply { if (!emoji) fontFeatureSettings = "tnum" },
                        LayoutParams(-2, -2).apply {
                            if (index > 0 || icon != null || stickerId != null)
                                marginStart = context.dp(6)
                        },
                    )
                }
            }
            outer.setOnClickListener { click(glyph) }
            outer.addView(
                inner,
                FrameLayout.LayoutParams(
                    -2,
                    context
                        .dp(32)
                        .coerceAtLeast(
                            (context.resources.configuration.fontScale * context.dp(32)).toInt()
                        ),
                    Gravity.CENTER,
                ),
            )
            reactions.addView(
                outer,
                FlexboxLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(4) },
            )
        }
        chip(
            if (post.likeCount > 0) numbers.format(post.likeCount) else "",
            if (post.liked) R.drawable.ic_like_heart_fill else R.drawable.ic_like_heart_outline,
            post.liked,
            if (post.liked) context.getString(R.string.plaza_unlike)
            else context.getString(R.string.plaza_like),
            iconTint = if (post.liked) context.color(R.color.v3_pink) else null,
        ) { glyph ->
            // One short bounce on the heart; the count follows the server response.
            if (glyph != null && motionEnabled()) {
                glyph.animate().cancel()
                glyph.scaleX = 1f
                glyph.scaleY = 1f
                glyph.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120)
                    .withEndAction {
                        glyph.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(OvershootInterpolator()).start()
                    }
                    .start()
            }
            onLike(post)
        }
        post.reactions.forEach { reaction ->
            val count = numbers.format(reaction.count)
            chip(
                if (reaction.stickerId != null) count else "${reaction.emoji} $count",
                selected = reaction.selected,
                description =
                    context.getString(
                        R.string.plaza_react_emoji,
                        if (reaction.stickerId != null) context.getString(R.string.sticker_title)
                        else reaction.emoji,
                    ),
                stickerId = reaction.stickerId,
            ) {
                onReact(post, reaction.emoji)
            }
        }
        chip(
            "",
            R.drawable.chat_ic_emoji,
            description = context.getString(R.string.plaza_add_reaction),
        ) {
            StickerPicker.show(context) { sticker ->
                onReact(post, "sticker:${sticker.stickerId}")
            }
        }
        chip(
            if (post.replyCount > 0 && detailMode.not()) numbers.format(post.replyCount) else "",
            R.drawable.ic_baseline_comment_24,
            description = context.getString(R.string.plaza_reply_post),
        ) {
            if (onReply != null) onReply(post) else context.openPost(post.id)
        }
    }

    private fun bindComments(
        post: PlazaPost,
        detail: Boolean,
        comment: Boolean,
        onReply: ((PlazaPost) -> Unit)?,
    ) {
        for (i in 0 until comments.childCount) clearImageRequests(comments.getChildAt(i))
        comments.removeAllViews()
        comments.background =
            if (comment) null else shape(context.dpF(12f), commentPreviewFill)
        val previewPadding = context.dp(if (comment) 0 else 12)
        comments.setPadding(previewPadding, previewPadding, previewPadding, previewPadding)
        comments.isVisible = !detail && post.commentsPreview.isNotEmpty()
        post.commentsPreview.forEach { preview ->
            if (comment) {
                val row = LinearLayout(context).apply { gravity = Gravity.TOP }
                val photo =
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription =
                            context.getString(R.string.plaza_view_profile, preview.displayName)
                        setOnClickListener { context.openObject("user", preview.uid) }
                    }
                imageRequests
                    .load(preview.avatarUrl?.let { GlideUrlChild(it) })
                    .placeholder(R.drawable.chat_avatar_placeholder)
                    .circleCrop()
                    .into(photo)
                row.addView(
                    photo,
                    LayoutParams(context.dp(32), context.dp(32)).apply {
                        marginEnd = context.dp(8)
                    },
                )
                val content = LinearLayout(context).apply { orientation = VERTICAL }
                content.addView(context.label(preview.displayName, 14f, 600))
                content.addView(
                    context.label(preview.text, 14f, 400, context.color(R.color.v3_text_2)).apply {
                        lineHeightRatio(1.5f)
                    },
                    LayoutParams(-1, -2).apply { topMargin = context.dp(2) },
                )
                content.addView(
                    context.label(context.getString(R.string.plaza_reply), 12f, 600, palette.textAccent)
                        .apply {
                            minHeight = context.dp(32)
                            gravity = Gravity.CENTER_VERTICAL
                            setOnClickListener { context.openPost(preview.id) }
                        },
                    LayoutParams(-2, -2).apply { topMargin = context.dp(4) },
                )
                row.addView(content, LayoutParams(0, -2, 1f))
                row.setOnClickListener { context.openPost(preview.id) }
                comments.addView(
                    row,
                    LayoutParams(-1, -2).apply {
                        if (comments.childCount > 0) topMargin = context.dp(16)
                    },
                )
                return@forEach
            }
            val prefix = context.getString(R.string.plaza_preview_author, preview.displayName)
            val text =
                context.label("$prefix ${preview.text}", 14f, 400, commentPreviewText).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    text =
                        SpannableString(text).apply {
                            setSpan(
                                ForegroundColorSpan(context.color(R.color.v3_text_1)),
                                0,
                                prefix.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    setOnClickListener { context.openPost(preview.id) }
                }
            comments.addView(
                text,
                LayoutParams(-1, -2).apply {
                    if (comments.childCount > 0) topMargin = context.dp(8)
                },
            )
        }
        if (post.replyCount > 0)
            comments.addView(
                context
                    .label(
                        context.resources.getQuantityString(
                            R.plurals.plaza_reply_count,
                            post.replyCount,
                            post.replyCount,
                        ),
                        12f,
                        600,
                        if (comment) palette.textAccent else commentPreviewAccent,
                    )
                    .apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minHeight = context.dp(32)
                        setOnClickListener { context.openPost(post.id) }
                    },
                LayoutParams(-2, -2).apply { topMargin = context.dp(8) },
            )
        commentFooter.isVisible = comment
        commentFooter.text =
            context.getString(
                R.string.plaza_reply_time,
                DateUtils.getRelativeTimeSpanString(context, post.createdAt, false),
            )
        commentFooter.setOnClickListener {
            if (onReply != null) onReply(post) else context.openPost(post.id)
        }
    }

    /**
     * Uploads are the author's choice of media and win outright; the linked work only fills
     * in when there are none, and its images never pass through plaza storage.
     */
    private fun tilesOf(post: PlazaPost, onImage: (PlazaPost, Int, View) -> Unit): List<Tile> {
        if (post.images.isNotEmpty()) {
            val viewer = viewerUid()
            return post.images.mapIndexed { index, image ->
                Tile(
                    key = "media:$viewer:${image.mediaId}",
                    width = image.width,
                    height = image.height,
                    url = image.url,
                    blur = false,
                    model = {
                        image.url.takeIf { it.isNotBlank() }?.let { PlazaMediaUrl(image, viewer) }
                    },
                ) { photo ->
                    onImage(post, index, photo)
                }
            }
        }
        val work = post.linkedWork() ?: return emptyList()
        val pages = work.linkedPages()
        if (pages.isEmpty()) return emptyList()
        val blur = spoilered(work)
        return pages.map { page ->
            // A lone page is shown at full width, where pixiv's 600x1200 "large" holds up.
            val url = if (pages.size == 1) page.large?.takeIf { it.isNotBlank() } ?: page.medium else page.medium
            Tile(
                key = "work:${work.id}:${page.index}",
                width = if (page.index == 0) work.width.coerceAtLeast(1) else 1,
                height = if (page.index == 0) work.height.coerceAtLeast(1) else 1,
                url = url,
                blur = blur,
                model = { GlideUrlChild(url) },
            ) {
                context.openObject(post.objectType, work.id)
            }
        }
    }

    private fun bindImages(items: List<Tile>) {
        val old = renderedTiles
        val sameContent =
            old != null &&
                old.size == items.size &&
                old.indices.all { i ->
                    val a = old[i]
                    val b = items[i]
                    a.key == b.key && a.width == b.width && a.height == b.height && a.blur == b.blur
                }
        if (sameContent && renderedWidth == images.width) {
            // Retain the drawable and in-flight request. Clicks must still receive the new URL.
            var index = 0
            for (r in 0 until images.childCount) {
                val row = images.getChildAt(r) as ViewGroup
                for (c in 0 until row.childCount) {
                    val photo = row.getChildAt(c) as ImageView
                    val position = index++
                    val tile = items[position]
                    photo.setOnClickListener { tile.open(photo) }
                    val request = DrawableImageViewTarget(photo).request
                    if (
                        request?.isComplete != true &&
                            request?.isRunning != true &&
                            old!![position].url != tile.url
                    ) {
                        // A failed old signature must retry the latest transport URL.
                        imageRequests.clear(photo)
                        loadPhoto(photo, tile, photo.layoutParams.width, photo.layoutParams.height)
                    }
                }
            }
            renderedTiles = items
            return
        }
        clearImages()
        renderedTiles = items
        renderedWidth = images.width
        if (images.width <= 0 || items.isEmpty()) return
        val gap = context.dp(4)
        val columns =
            when (items.size) {
                1 -> 1
                2, 4 -> 2
                else -> 3
            }
        val tileWidth = (images.width - gap * (columns - 1)) / columns
        items.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(context)
            rowItems.forEachIndexed { column, tile ->
                val width =
                    if (items.size == 1 && tile.height > tile.width && !detailMode)
                        (images.width * .55f).toInt()
                    else tileWidth
                val height =
                    if (items.size == 1)
                        (width.toFloat() * tile.height / tile.width.coerceAtLeast(1))
                            .toInt()
                            .coerceAtMost(context.dp(if (detailMode) 900 else 480))
                    else tileWidth
                val photo =
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription =
                            context.getString(
                                R.string.plaza_image_accessibility,
                                rowIndex * columns + column + 1,
                                items.size,
                            )
                        background =
                            shape(context.dpF(if (items.size == 1) 16f else 12f), palette.alpha08)
                        clipToOutline = true
                    }
                row.addView(
                    photo,
                    LayoutParams(width, height).apply { if (column > 0) marginStart = gap },
                )
                loadPhoto(photo, tile, width, height)
                photo.setOnClickListener { tile.open(photo) }
            }
            images.addView(row, LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = gap })
        }
    }

    private fun loadPhoto(photo: ImageView, tile: Tile, width: Int, height: Int) {
        var request = imageRequests.load(tile.model()).override(width, height).dontAnimate()
        if (tile.blur) {
            // Glide decodes straight to a blurred bitmap; the transform is part of the cache key.
            request =
                request.apply(
                    bitmapTransform(
                        BlurTransformation(LINKED_WORK_BLUR_RADIUS, LINKED_WORK_BLUR_SAMPLING)
                    )
                )
        }
        request.error(android.R.drawable.ic_menu_report_image).into(photo)
    }

    private fun clearImageRequests(v: View) {
        if (v is ImageView) imageRequests.clear(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) clearImageRequests(v.getChildAt(i))
    }

    private fun clearImages() {
        clearImageRequests(images)
        images.removeAllViews()
    }

    fun clear() {
        imageRequests.clear(avatar)
        renderedAvatar = null
        clearImageRequests(comments)
        images.onMeasured = null
        renderedTiles = null
        renderedWidth = -1
        clearImages()
    }
}

internal fun Context.showPostMenu(post: PlazaPost, onDelete: (PlazaPost) -> Unit) {
    val mine = post.uid == SessionManager.loggedInUid
    val options =
        if (mine)
            arrayOf(
                getString(R.string.plaza_share_text),
                SpannableString(getString(R.string.plaza_delete_post)).apply {
                    setSpan(
                        ForegroundColorSpan(color(R.color.v3_danger)),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                },
            )
        else arrayOf(getString(R.string.plaza_share_text), getString(R.string.plaza_view_author),
            getString(R.string.plaza_report_post), getString(R.string.plaza_report_user),
            getString(R.string.plaza_block_user))
    WitDialog.MenuDialogBuilder(this)
        .addItems(options) { dialog, index ->
            dialog.dismiss()
            if (index >= 2) {
                showPlazaModeration(post.id, post.uid, listOf("post", "user", "block")[index - 2])
            } else if (index == 1) {
                if (mine) onDelete(post) else openObject("user", post.uid)
            } else
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                listOf(post.title, post.text)
                                    .filter { it.isNotBlank() }
                                    .joinToString("\n"),
                            )
                        },
                        getString(R.string.plaza_share_post),
                    )
                )
        }
        .show()
}
