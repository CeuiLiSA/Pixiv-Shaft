package ceui.pixiv.plaza.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.plaza.PlazaImage
import ceui.pixiv.plaza.PlazaPost
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.notification.routeNotificationTargetUrl
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.Glide

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun Context.label(value: String, size: Float = 14f, bold: Boolean = false) =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(context, R.color.v3_text_1))
        typeface =
            ResourcesCompat.getFont(
                context,
                if (bold) R.font.plaza_inter_semi_bold else R.font.plaza_inter_regular,
            )
        includeFontPadding = false
    }

internal fun Context.action(value: String, primary: Boolean = false) =
    label(value, 14f, true).apply {
        val p = V3Palette.from(context)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background =
            if (primary) p.pillPrimary(dp(20).toFloat()) else p.pillSecondary(dp(20).toFloat())
        setTextColor(if (primary) p.onPrimary else p.textAccent)
        isClickable = true
        isFocusable = true
    }

internal fun TextView.figmaLineHeight(multiplier: Float) {
    setLineSpacing(0f, 1f)
    androidx.core.widget.TextViewCompat.setLineHeight(this, (textSize * multiplier).toInt())
}

internal class FigmaIcon(context: Context, drawable: Int, description: String, circle: Boolean) :
    FrameLayout(context) {
    val icon =
        ImageView(context).apply {
            setImageResource(drawable)
            imageTintList =
                android.content.res.ColorStateList.valueOf(
                    V3Palette.from(context).floatingPillContent
                )
        }

    init {
        minimumWidth = context.dp(48)
        minimumHeight = context.dp(48)
        contentDescription = description
        isClickable = true
        isFocusable = true
        val surface = FrameLayout(context)
        if (circle)
            surface.background =
                V3Palette.from(context).pillSecondary(context.dp(20).toFloat(), context.dp(1))
        surface.addView(icon, LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER))
        addView(surface, LayoutParams(context.dp(40), context.dp(40), Gravity.CENTER))
    }
}

internal fun Context.figmaIcon(drawable: Int, description: String, circle: Boolean = false) =
    FigmaIcon(this, drawable, description, circle)

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
        "pixiv://${when(type) { "user" -> "users"
 "novel" -> "novels"
 else -> "illusts" }}/$id"
    )
}

internal class PostAdapter(
    private val onLike: (PlazaPost) -> Unit,
    private val onDelete: (PlazaPost) -> Unit,
    private val onImage: (PlazaPost, Int, View) -> Unit,
    private val detailId: Long = 0,
    private val onReact: (PlazaPost, String) -> Unit = { _, _ -> },
    private val onReply: ((PlazaPost) -> Unit)? = null,
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(PostView(parent.context))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val post = getItem(position)
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
        )
    }

    override fun onViewRecycled(holder: Holder) {
        holder.view.clear()
    }

    class Holder(val view: PostView) : RecyclerView.ViewHolder(view)
}

/** Figma post-feed/post-detail: 16dp gutters, 50dp avatar, 12dp groups, 2dp media grid. */
internal class PostView(
    context: Context,
    private val viewerUid: () -> Long = { SessionManager.loggedInUid },
) : LinearLayout(context) {
    private val imageRequests = Glide.with(this)
    private var renderedAvatar: Pair<Long, String?>? = null
    private var renderedImages: List<PlazaImage>? = null
    private var renderedWidth = -1
    private var renderedViewerUid: Long? = null
    private var detailMode = false
    private val avatar =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
        }
    private val name = context.label("", 15f, true)
    private val time = context.label("", 13f)
    private val more =
        context.figmaIcon(
            R.drawable.ic_plaza_figma_more,
            context.getString(R.string.plaza_more_menu),
        )
    private val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val title = context.label("", 17f, true)
    private val body = context.label("", 15f)
    private val images = PlazaIllustGrid(context).apply { orientation = VERTICAL }
    private val reference = context.label("", 14f)
    private val reactions =
        com.google.android.flexbox.FlexboxLayout(context).apply {
            flexWrap = com.google.android.flexbox.FlexWrap.WRAP
        }
    private val comments = LinearLayout(context).apply { orientation = VERTICAL }
    private val commentsTitle = context.label("", 15f)
    private val commentFooter = context.label("", 13f)

    init {
        orientation = VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2)
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        setBackgroundColor(V3Palette.from(context).cardFill)
        avatar.background = V3Palette.from(context).pillSecondary(context.dp(25).toFloat())
        heading.addView(
            avatar,
            LayoutParams(context.dp(50), context.dp(50)).apply { marginEnd = context.dp(12) },
        )
        val identity =
            LinearLayout(context).apply {
                orientation = VERTICAL
                addView(name)
                addView(time, LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
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
        group(commentFooter, 8)
        body.setTextColor(ContextCompat.getColor(context, R.color.v3_text_2))
        body.figmaLineHeight(1.35f)
        time.setTextColor(ContextCompat.getColor(context, R.color.v3_text_3))
        reference.setTextColor(V3Palette.from(context).textAccent)
        reference.background = V3Palette.from(context).pillSecondary(context.dp(8).toFloat())
        reference.setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        reference.minHeight = context.dp(32)
        comments.setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        comments.background =
            GradientDrawable().apply {
                cornerRadius = context.dp(6).toFloat()
                setColor(ContextCompat.getColor(context, R.color.v3_bg))
            }
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
    ) {
        if (detailMode != detail) {
            renderedImages = null
            detailMode = detail
        }
        setBackgroundColor(
            if (detail || comment) ContextCompat.getColor(context, R.color.v3_bg)
            else V3Palette.from(context).cardFill
        )
        name.text = post.displayName
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(
            context.dp(16),
            context.dp(if (detail) 0 else 12),
            context.dp(16),
            context.dp(12),
        )
        val avatarKey = post.uid to post.avatarUrl
        if (renderedAvatar != avatarKey) {
            imageRequests
                .load(post.avatarUrl?.let { ceui.lisa.utils.GlideUrlChild(it) })
                .placeholder(R.drawable.chat_avatar_placeholder)
                .circleCrop()
                .into(avatar)
            renderedAvatar = avatarKey
        }
        avatar.contentDescription = context.getString(R.string.plaza_view_profile, post.displayName)
        avatar.setOnClickListener { context.openObject("user", post.uid) }
        time.text =
            java.text.DateFormat.getDateInstance(
                    java.text.DateFormat.MEDIUM,
                    context.resources.configuration.locales[0],
                )
                .format(java.util.Date(post.createdAt))
        title.text = post.title
        title.isVisible = post.title.isNotBlank()
        title.textSize = if (detail) 24f else 17f
        title.typeface =
            ResourcesCompat.getFont(
                context,
                if (detail) R.font.plaza_inter_bold else R.font.plaza_inter_semi_bold,
            )
        title.figmaLineHeight(1.2f)
        title.maxLines = if (detail) Int.MAX_VALUE else 2
        body.text = post.text
        body.isVisible = post.text.isNotBlank()
        body.maxLines = if (detail || comment) Int.MAX_VALUE else 2
        body.ellipsize = android.text.TextUtils.TruncateAt.END
        fun spacing(v: View, gap: Int, start: Int = 0) {
            v.layoutParams =
                (v.layoutParams as LayoutParams).apply {
                    topMargin = context.dp(gap)
                    marginStart = context.dp(start)
                }
        }
        spacing(title, if (detail) 16 else 8)
        spacing(body, if (post.title.isBlank()) 12 else 4)
        spacing(images, if (detail) 16 else 12)
        spacing(reference, if (detail) 16 else 12)
        spacing(reactions, if (detail) 16 else 12)
        spacing(comments, 8)
        spacing(commentsTitle, 16)
        body.figmaLineHeight(1.35f)
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
        images.isVisible = post.images.isNotEmpty()
        images.onMeasured = {
            bindImages(post.images) { index, photo -> onImage(post, index, photo) }
        }
        bindImages(post.images) { index, photo -> onImage(post, index, photo) }
        reactions.removeAllViews()
        fun chip(
            text: String,
            icon: Int? = null,
            selected: Boolean = false,
            description: String,
            stickerId: Long? = null,
            click: () -> Unit,
        ) {
            val outer =
                FrameLayout(context).apply {
                    minimumHeight = context.dp(28)
                    minimumWidth = context.dp(44)
                    isClickable = true
                    isFocusable = true
                    isEnabled = !busy
                    isSelected = selected
                    contentDescription = description
                    setOnClickListener { click() }
                }
            val inner =
                LinearLayout(context).apply {
                    minimumWidth = context.dp(44)
                    gravity = Gravity.CENTER
                    setPadding(context.dp(8), 0, context.dp(8), 0)
                    background =
                        GradientDrawable().apply {
                            cornerRadius = context.dp(40).toFloat()
                            setColor(
                                if (selected) V3Palette.from(context).alpha20
                                else V3Palette.from(context).alpha08
                            )
                        }
                }
            if (stickerId != null) {
                // Figma reactions use an 18px emoji inside the shared 28px pill. The 64px
                // decoded asset is only a quality tier, not the on-screen sticker size.
                val size = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    18f,
                    resources.displayMetrics,
                ).toInt()
                inner.addView(
                    ceui.pixiv.sticker.StickerImageView(context).apply {
                        bind(stickerId, resourceSize = 64)
                    },
                    LayoutParams(size, size),
                )
            }
            if (icon != null)
                inner.addView(
                    ImageView(context).apply {
                        setImageResource(icon)
                        imageTintList =
                            android.content.res.ColorStateList.valueOf(
                                if (selected) V3Palette.from(context).textAccent
                                else ContextCompat.getColor(context, R.color.v3_text_2)
                            )
                    },
                    LayoutParams(context.dp(20), context.dp(20)),
                )
            if (text.isNotEmpty()) {
                val split =
                    if (icon == null && stickerId == null) text.split(" ", limit = 2)
                    else listOf(text.trim())
                split.forEachIndexed { index, value ->
                    inner.addView(
                        context
                            .label(value, if (split.size == 2 && index == 0) 18f else 15f)
                            .apply {
                                if (selected) setTextColor(V3Palette.from(context).textAccent)
                            },
                        LayoutParams(-2, -2).apply {
                            if (index > 0 || icon != null || stickerId != null)
                                marginStart = context.dp(4)
                        },
                    )
                }
            }
            outer.addView(
                inner,
                FrameLayout.LayoutParams(
                    -2,
                    context
                        .dp(28)
                        .coerceAtLeast(
                            (context.resources.configuration.fontScale * context.dp(28)).toInt()
                        ),
                    Gravity.CENTER,
                ),
            )
            reactions.addView(
                outer,
                com.google.android.flexbox.FlexboxLayout.LayoutParams(-2, -2).apply {
                    marginEnd = context.dp(4)
                    bottomMargin = context.dp(4)
                },
            )
        }
        chip(
            if (post.likeCount > 0)
                java.text.NumberFormat.getIntegerInstance(
                        context.resources.configuration.locales[0]
                    )
                    .format(post.likeCount)
            else "",
            if (post.liked) R.drawable.ic_plaza_figma_heart
            else R.drawable.ic_plaza_figma_heart_outline,
            post.liked,
            if (post.liked) context.getString(R.string.plaza_unlike)
            else context.getString(R.string.plaza_like),
        ) {
            onLike(post)
        }
        post.reactions.forEach { reaction ->
            val count = java.text.NumberFormat.getIntegerInstance(
                context.resources.configuration.locales[0],
            ).format(reaction.count)
            chip(
                if (reaction.stickerId != null) count else "${reaction.emoji} $count",
                selected = reaction.selected,
                description = context.getString(
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
            R.drawable.ic_plaza_figma_reaction,
            description = context.getString(R.string.plaza_add_reaction),
        ) {
            ceui.pixiv.sticker.StickerPicker.show(context) { sticker ->
                onReact(post, "sticker:${sticker.stickerId}")
            }
        }
        chip(
            "",
            R.drawable.ic_plaza_figma_comment,
            description = context.getString(R.string.plaza_reply_post),
        ) {
            if (onReply != null) onReply(post) else context.openPost(post.id)
        }
        for (i in 0 until comments.childCount) clearImageRequests(comments.getChildAt(i))
        comments.removeAllViews()
        comments.background =
            if (comment) null
            else
                GradientDrawable().apply {
                    cornerRadius = context.dp(6).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.v3_bg))
                }
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
                    .load(preview.avatarUrl?.let { ceui.lisa.utils.GlideUrlChild(it) })
                    .placeholder(R.drawable.chat_avatar_placeholder)
                    .circleCrop()
                    .into(photo)
                row.addView(
                    photo,
                    LayoutParams(context.dp(36), context.dp(36)).apply {
                        marginEnd = context.dp(8)
                    },
                )
                val content = LinearLayout(context).apply { orientation = VERTICAL }
                content.addView(context.label(preview.displayName, 15f, true))
                content.addView(
                    context.label(preview.text, 15f).apply {
                        figmaLineHeight(1.35f)
                        setTextColor(ContextCompat.getColor(context, R.color.v3_text_2))
                    },
                    LayoutParams(-1, -2).apply { topMargin = context.dp(2) },
                )
                content.addView(
                    context.label(context.getString(R.string.plaza_reply), 13f).apply {
                        setOnClickListener { context.openPost(preview.id) }
                    },
                    LayoutParams(-2, -2).apply { topMargin = context.dp(8) },
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
                context.label("$prefix ${preview.text}", 15f).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(context, R.color.v3_text_2))
                    text =
                        android.text.SpannableString(text).apply {
                            setSpan(
                                android.text.style.ForegroundColorSpan(
                                    ContextCompat.getColor(context, R.color.v3_text_1)
                                ),
                                0,
                                prefix.length,
                                0,
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
                        13f,
                    )
                    .apply {
                        setTextColor(V3Palette.from(context).textAccent)
                        gravity = Gravity.CENTER_VERTICAL
                        setOnClickListener { context.openPost(post.id) }
                    },
                LayoutParams(-2, -2).apply { topMargin = context.dp(12) },
            )
        commentFooter.isVisible = comment
        commentFooter.text =
            context.getString(
                R.string.plaza_reply_time,
                android.text.format.DateUtils.getRelativeTimeSpanString(
                    context,
                    post.createdAt,
                    false,
                ),
            )
        commentFooter.setTextColor(ContextCompat.getColor(context, R.color.v3_text_3))
        commentFooter.setOnClickListener {
            if (onReply != null) onReply(post) else context.openPost(post.id)
        }
        commentsTitle.isVisible = detail
        commentsTitle.text = context.getString(R.string.plaza_comments_title, post.replyCount)
        if (comment) {
            (avatar.layoutParams as LayoutParams).apply {
                width = context.dp(36)
                height = context.dp(36)
                marginEnd = context.dp(8)
                avatar.layoutParams = this
            }
            time.isVisible = false
            name.textSize = 15f
            more.isVisible = false
            heading.gravity = Gravity.TOP
            listOf(title, body, images, reference, reactions, comments, commentFooter).forEach { v
                ->
                spacing(
                    v,
                    if (v == body && post.title.isBlank()) -16
                    else if (v == body) 4 else if (v == commentFooter) 8 else 12,
                    44,
                )
            }
        } else {
            (avatar.layoutParams as LayoutParams).apply {
                width = context.dp(50)
                height = context.dp(50)
                marginEnd = context.dp(12)
                avatar.layoutParams = this
            }
            time.isVisible = true
            heading.gravity = Gravity.CENTER_VERTICAL
        }
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        val paint =
            android.graphics.Paint().apply {
                color = V3Palette.from(context).cardHairline
                strokeWidth = context.dp(1).coerceAtLeast(1).toFloat()
            }
        canvas.drawLine(
            paddingLeft.toFloat(),
            height - 1f,
            (width - paddingRight).toFloat(),
            height - 1f,
            paint,
        )
        if (commentsTitle.isVisible)
            canvas.drawLine(
                paddingLeft.toFloat(),
                commentsTitle.top - context.dp(8).toFloat(),
                (width - paddingRight).toFloat(),
                commentsTitle.top - context.dp(8).toFloat(),
                paint,
            )
    }

    private fun bindImages(items: List<PlazaImage>, click: (Int, View) -> Unit) {
        val viewer = viewerUid()
        val old = renderedImages
        val sameContent =
            old != null &&
                old.size == items.size &&
                old.indices.all { i ->
                    val a = old[i]
                    val b = items[i]
                    a.mediaId == b.mediaId &&
                        a.width == b.width &&
                        a.height == b.height &&
                        a.contentType == b.contentType
                }
        if (sameContent && renderedWidth == images.width && renderedViewerUid == viewer) {
            // Retain the drawable and in-flight request. Clicks must still receive the new URL.
            var index = 0
            for (r in 0 until images.childCount) {
                val row = images.getChildAt(r) as ViewGroup
                for (c in 0 until row.childCount) {
                    val photo = row.getChildAt(c) as ImageView
                    val position = index++
                    photo.setOnClickListener { click(position, photo) }
                    val request =
                        com.bumptech.glide.request.target.DrawableImageViewTarget(photo).request
                    if (
                        request?.isComplete != true &&
                            request?.isRunning != true &&
                            old!![position].url != items[position].url
                    ) {
                        // A failed old signature must retry the latest transport URL.
                        imageRequests.clear(photo)
                        loadPhoto(
                            photo,
                            items[position],
                            viewer,
                            photo.layoutParams.width,
                            photo.layoutParams.height,
                        )
                    }
                }
            }
            renderedImages = items
            return
        }
        clearImages()
        renderedImages = items
        renderedWidth = images.width
        renderedViewerUid = viewer
        if (images.width <= 0 || items.isEmpty()) return
        val gap = context.dp(2)
        val columns =
            when (items.size) {
                1 -> 1
                2,
                4 -> 2
                else -> 3
            }
        val tileWidth = (images.width - gap * (columns - 1)) / columns
        items.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(context)
            rowItems.forEachIndexed { column, image ->
                val width =
                    if (items.size == 1 && image.height > image.width && !detailMode)
                        (images.width * .35f).toInt()
                    else tileWidth
                val height =
                    if (items.size == 1)
                        (width.toFloat() * image.height / image.width.coerceAtLeast(1))
                            .toInt()
                            .coerceAtMost(context.dp(900))
                    else if (columns == 3) (tileWidth * 110f / 118f).toInt() else tileWidth
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
                            GradientDrawable().apply {
                                cornerRadius = context.dp(if (detailMode) 6 else 4).toFloat()
                                setColor(V3Palette.from(context).alpha08)
                            }
                        clipToOutline = true
                    }
                row.addView(
                    photo,
                    LayoutParams(width, height).apply { if (column > 0) marginStart = gap },
                )
                loadPhoto(photo, image, viewer, width, height)
                photo.setOnClickListener { click(rowIndex * columns + column, photo) }
            }
            images.addView(row, LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = gap })
        }
    }

    private fun loadPhoto(
        photo: ImageView,
        image: PlazaImage,
        viewer: Long,
        width: Int,
        height: Int,
    ) {
        imageRequests
            .load(image.url.takeIf { it.isNotBlank() }?.let { PlazaMediaUrl(image, viewer) })
            .override(width, height)
            .dontAnimate()
            .error(android.R.drawable.ic_menu_report_image)
            .into(photo)
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
        renderedImages = null
        renderedWidth = -1
        renderedViewerUid = null
        clearImages()
    }
}

internal fun Context.showPostMenu(post: PlazaPost, onDelete: (PlazaPost) -> Unit) {
    val mine = post.uid == SessionManager.loggedInUid
    val options =
        if (mine)
            arrayOf(getString(R.string.plaza_share_text), getString(R.string.plaza_delete_post))
        else arrayOf(getString(R.string.plaza_share_text), getString(R.string.plaza_view_author))
    ceui.pixiv.witstudio.dialog.WitDialog.MenuDialogBuilder(this)
        .addItems(options) { dialog, index ->
            dialog.dismiss()
            if (index == 1) {
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
