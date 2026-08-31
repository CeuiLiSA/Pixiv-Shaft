package ceui.pixiv.plaza.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellPlazaPostBinding
import ceui.lisa.databinding.CellPlazaRefIllustBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.pixiv.chat.base.BaseListAdapter
import ceui.lisa.network.PlazaIllustRef
import ceui.lisa.network.PlazaPost
import ceui.lisa.network.PlazaUserRef
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 广场 feed adapter。每条卡片一个 [PlazaPostViewHolder]。
 *
 * 引用 illust 走 LinearLayout 容器,布局按数量切:
 *   - 1 → 单图按原比例 wrap (限 maxHeight)
 *   - 2 → 横排 2 方块
 *   - 3 → 左大 + 右 2 小 (Twitter 风)
 *   - 4 → 2x2 方块
 *   - 5..9 → 3 列方块
 *
 * 用户点击事件回调 onMore (作者点 ⋯) / onRefIllustClick (点引用图)
 * 通过构造参数透出,避免在 Holder 里持 Fragment ref。
 */
class PlazaFeedAdapter(
    private val selfUid: Long,
    private val onMore: (PlazaPost, View) -> Unit,
    private val onCardClick: (PlazaPost) -> Unit,
) : BaseListAdapter<PlazaPost, PlazaFeedAdapter.PlazaPostViewHolder>(
    diffCallback(keySelector = { it.id })
) {

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): PlazaPostViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = CellPlazaPostBinding.inflate(inflater, parent, false)
        return PlazaPostViewHolder(binding, selfUid, onMore, onCardClick)
    }

    override fun onBindDataViewHolder(holder: PlazaPostViewHolder, item: PlazaPost) {
        holder.bind(item)
    }

    class PlazaPostViewHolder(
        private val binding: CellPlazaPostBinding,
        private val selfUid: Long,
        private val onMore: (PlazaPost, View) -> Unit,
        private val onCardClick: (PlazaPost) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: PlazaPost) {
            bindPlazaPostCard(
                binding = binding,
                post = post,
                selfUid = selfUid,
                onMore = onMore,
                onCardClick = onCardClick,
            )
        }
    }
}

/**
 * 把一个 [CellPlazaPostBinding] 当模板,用 post 数据填好整张卡片 —— 包括头像 /
 * 名字 / 时间 / 正文 / 引用 illust grid / 引用 user chips / ⋯ 菜单 / 卡片整体 click。
 *
 * Feed 的 RecyclerView Holder 用,详情页 PlazaPostDetailFragment 也用 (复用视觉一致)。
 * onMore 仅在 selfUid 匹配时挂监听 + 显示按钮。onCardClick 传 null 表示不挂(详情页
 * 已经在自己内部,无需再 navigate)。
 */
internal fun bindPlazaPostCard(
    binding: CellPlazaPostBinding,
    post: PlazaPost,
    selfUid: Long,
    onMore: ((PlazaPost, View) -> Unit)?,
    onCardClick: ((PlazaPost) -> Unit)?,
) {
    val ctx = binding.root.context

    binding.displayName.text = post.display_name ?: post.uid.toString()
    binding.postTime.text = formatRelativeTime(ctx, post.ts)
    binding.bodyText.text = post.text

    // 作者头像 —— server 没下发,先用占位。后续可以从 ObjectPool.get<User>(post.uid)
    // 或本地 chat profile 缓存补头像。
    binding.avatar.setImageResource(R.drawable.chat_avatar_placeholder)

    // ⋯ 菜单只在自己的帖子上显示。当前菜单只有「删除」,展示给别人没意义。
    val isMine = selfUid > 0L && post.uid == selfUid
    binding.moreBtn.isVisible = isMine && onMore != null
    if (isMine && onMore != null) {
        binding.moreBtn.setOnClickListener { v -> onMore(post, v) }
    } else {
        binding.moreBtn.setOnClickListener(null)
    }

    // 卡片整体点击 —— feed 页跳详情。详情页 onCardClick = null 不挂。
    // 排除头像 / illust 缩略 / ⋯ 这些已有自己 click 的子区。
    if (onCardClick != null) {
        binding.card.setOnClickListener { onCardClick(post) }
    } else {
        binding.card.setOnClickListener(null)
        binding.card.isClickable = false
    }

    bindIllustGrid(binding, post.refs.illust)
    bindUserRefs(binding, post.refs.user)
    bindActionChips(binding, post)
}

/**
 * 渲染 like / comment 计数 chip。Feed 卡片里只展示数值,点击转跳详情页统一
 * 处理 toggle(避免多入口同时改 like_count 漂移)。详情页 viewModel 走乐观
 * 更新 + server 权威 count 校正。
 */
private fun bindActionChips(binding: CellPlazaPostBinding, post: PlazaPost) {
    binding.likeCount.text = post.like_count.toString()
    binding.commentCount.text = post.comment_count.toString()
    binding.likeIcon.setImageResource(
        if (post.liked_by_viewer == true) R.drawable.ic_heart_filled_16
        else R.drawable.ic_heart_outline_16
    )
}

private fun bindIllustGrid(binding: CellPlazaPostBinding, illusts: List<PlazaIllustRef>) {
    val container = binding.illustGrid
    container.removeAllViews()
    if (illusts.isEmpty()) {
        container.isVisible = false
        return
    }
    container.isVisible = true

    // 屏宽 - illust_grid 容器自身左右 18dp margin。新版 cell 没 CardView
    // 包装,直接铺满父宽,卡片间用 divider 分隔。
    val cardWidth = container.resources.displayMetrics.widthPixels - 36.ppppx
    val gap = 4.ppppx

    when (illusts.size) {
        1 -> container.addView(buildSingleCell(container, illusts[0], cardWidth))
        2 -> {
            val cell = (cardWidth - gap) / 2
            container.addView(buildSquareRow(container, illusts, cell, gap))
        }
        3 -> container.addView(buildBigPlusTwoSmall(container, illusts, cardWidth, gap))
        4 -> {
            val cell = (cardWidth - gap) / 2
            container.addView(buildSquareRow(container, illusts.subList(0, 2), cell, gap))
            container.addView(buildSquareRow(container, illusts.subList(2, 4), cell, gap).also {
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = gap
            })
        }
        else -> {
            // 5..9: 每行 3 个方块
            val columns = 3
            val cell = (cardWidth - gap * (columns - 1)) / columns
            var idx = 0
            while (idx < illusts.size) {
                val end = minOf(idx + columns, illusts.size)
                val row = buildSquareRow(container, illusts.subList(idx, end), cell, gap)
                if (idx > 0) (row.layoutParams as LinearLayout.LayoutParams).topMargin = gap
                container.addView(row)
                idx += columns
            }
        }
    }
}

/**
 * 单图: 加载前用 4:3 占位高度撑住,Glide listener 在 onResourceReady 拿到原图比例后
 * 把 root 高度调到目标。比 adjustViewBounds 那条路稳 —— 后者依赖 Glide 触发的
 * requestLayout 链,在 wrap_content 容器里有概率不撑开。
 */
private fun buildSingleCell(parent: ViewGroup, ref: PlazaIllustRef, width: Int): View {
    val placeholderH = (width * 0.75f).toInt()
    val maxH = (width * 1.4f).toInt()
    val minH = (width * 0.4f).toInt()

    val cell = inflateCellShell(parent, ref, width, placeholderH)
    val thumbUrl = ref.meta?.thumb_url
    if (!thumbUrl.isNullOrEmpty()) {
        Glide.with(parent.context)
            .load(GlideUrlChild(thumbUrl))
            .placeholder(android.R.color.transparent)
            .listener(object : RequestListener<Drawable> {
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    val dw = resource.intrinsicWidth
                    val dh = resource.intrinsicHeight
                    if (dw > 0 && dh > 0) {
                        val targetH = (width.toFloat() * dh / dw).toInt().coerceIn(minH, maxH)
                        val lp = cell.root.layoutParams as LinearLayout.LayoutParams
                        if (lp.height != targetH) {
                            lp.height = targetH
                            cell.root.layoutParams = lp
                        }
                    }
                    return false
                }

                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean = false
            })
            .into(cell.thumb)
    }
    return cell.root
}

/**
 * 3 张图: 左 1 大方块 (边 = cardWidth/2),右两小竖排 (宽 = cardWidth/2)。
 * 整行高度 = 左方块边长。
 */
private fun buildBigPlusTwoSmall(
    parent: ViewGroup,
    illusts: List<PlazaIllustRef>,
    cardWidth: Int,
    gap: Int,
): View {
    val bigSize = (cardWidth - gap) / 2
    val smallH = (bigSize - gap) / 2

    val row = LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bigSize)
    }
    row.addView(inflateCell(row, illusts[0], bigSize, bigSize).root)

    val rightCol = LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(bigSize, bigSize).apply {
            leftMargin = gap
        }
    }
    rightCol.addView(inflateCell(rightCol, illusts[1], bigSize, smallH).root)
    rightCol.addView(inflateCell(rightCol, illusts[2], bigSize, smallH).root.also {
        (it.layoutParams as LinearLayout.LayoutParams).topMargin = gap
    })
    row.addView(rightCol)
    return row
}

/** 一行 N 个等大方块,横向排列,中间用 gap 隔开。 */
private fun buildSquareRow(
    parent: ViewGroup,
    illusts: List<PlazaIllustRef>,
    cellSize: Int,
    gap: Int,
): LinearLayout {
    val row = LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cellSize)
    }
    illusts.forEachIndexed { i, ref ->
        val cell = inflateCell(row, ref, cellSize, cellSize)
        if (i > 0) (cell.root.layoutParams as LinearLayout.LayoutParams).leftMargin = gap
        row.addView(cell.root)
    }
    return row
}

/**
 * Inflate cell + 尺寸 + 占位 ID + click,但**不**调 Glide。
 * 加载图的策略由 caller 决定(多图直接 into,单图带 listener 调高度)。
 */
private fun inflateCellShell(
    parent: ViewGroup,
    ref: PlazaIllustRef,
    width: Int,
    height: Int,
): CellPlazaRefIllustBinding {
    val binding = CellPlazaRefIllustBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    binding.root.layoutParams = LinearLayout.LayoutParams(width, height)
    val ctx = binding.root.context
    if (ref.meta?.thumb_url.isNullOrEmpty()) {
        binding.thumb.setImageDrawable(null)
        binding.placeholderId.isVisible = true
        binding.placeholderId.text = ref.id.toString()
    } else {
        binding.placeholderId.isVisible = false
    }
    binding.root.setOnClickListener {
        val intent = Intent(ctx, TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA_OPEN_ILLUST.key)
        intent.putExtra(Params.ILLUST_ID, ref.id.toInt())
        ctx.startActivity(intent)
    }
    return binding
}

/** 多图模式:inflateCellShell + 默认 Glide.into。 */
private fun inflateCell(
    parent: ViewGroup,
    ref: PlazaIllustRef,
    width: Int,
    height: Int,
): CellPlazaRefIllustBinding {
    val binding = inflateCellShell(parent, ref, width, height)
    val thumbUrl = ref.meta?.thumb_url
    if (!thumbUrl.isNullOrEmpty()) {
        Glide.with(binding.root.context)
            .load(GlideUrlChild(thumbUrl))
            .placeholder(android.R.color.transparent)
            .into(binding.thumb)
    }
    return binding
}

private fun bindUserRefs(binding: CellPlazaPostBinding, users: List<PlazaUserRef>) {
    val container = binding.userRefsRow
    container.removeAllViews()
    if (users.isEmpty()) {
        container.isVisible = false
        return
    }
    container.isVisible = true
    val ctx = container.context
    for (u in users) {
        val displayName = u.meta?.name ?: u.id.toString()
        val chip = TextView(ctx).apply {
            text = ctx.getString(R.string.plaza_referenced_user, displayName)
            textSize = 12f
            setPadding(12.ppppx, 6.ppppx, 12.ppppx, 6.ppppx)
            setBackgroundResource(R.drawable.bg_v3_chip)
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 8.ppppx, 8.ppppx) }
        }
        container.addView(chip)
    }
}

/** ts (毫秒) 相对当前时间格式化。 */
internal fun formatRelativeTime(ctx: android.content.Context, tsMillis: Long): String {
    val deltaMs = (System.currentTimeMillis() - tsMillis).coerceAtLeast(0L)
    val deltaSec = deltaMs / 1000
    return when {
        deltaSec < 60 -> ctx.getString(R.string.plaza_just_now)
        deltaSec < 3600 -> ctx.getString(R.string.plaza_minutes_ago, (deltaSec / 60).toInt())
        deltaSec < 86_400 -> ctx.getString(R.string.plaza_hours_ago, (deltaSec / 3600).toInt())
        deltaSec < 7 * 86_400 -> ctx.getString(R.string.plaza_days_ago, (deltaSec / 86_400).toInt())
        else -> {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            sdf.format(java.util.Date(tsMillis))
        }
    }
}
