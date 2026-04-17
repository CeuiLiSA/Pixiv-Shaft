package ceui.pixiv.ui.detail

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UserActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.adapters.LAdapter
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.SectionV3ArtistBinding
import ceui.lisa.databinding.SectionV3AuthorWorksBinding
import ceui.lisa.databinding.SectionV3CommentsBinding
import ceui.lisa.databinding.SectionV3DescriptionBinding
import ceui.lisa.databinding.SectionV3DetailPanelBinding
import ceui.lisa.databinding.SectionV3HeroBinding
import ceui.lisa.databinding.SectionV3RelatedHeaderBinding
import ceui.lisa.databinding.SectionV3SeriesBinding
import ceui.lisa.databinding.SectionV3StatsBinding
import ceui.lisa.databinding.SectionV3TagsBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.UserBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.ProgressTextButton
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.google.android.flexbox.FlexboxLayout
import de.hdodenhof.circleimageview.CircleImageView
import java.text.NumberFormat

class ArtworkDetailAdapter(
    private val fragment: androidx.fragment.app.Fragment
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val palette: V3Palette = V3Palette.from(fragment.requireContext())
    private val items = mutableListOf<ArtworkDetailItem>()
    private val animatedViewTypes = mutableSetOf<Int>()

    fun submitItems(newItems: List<ArtworkDetailItem>) {
        val t = SystemClock.elapsedRealtime()
        val oldItems = items.toList()
        items.clear()
        items.addAll(newItems)

        if (oldItems.size == newItems.size &&
            oldItems.zip(newItems).all { (a, b) -> viewTypeOf(a) == viewTypeOf(b) }
        ) {
            var changedCount = 0
            for (i in newItems.indices) {
                if (oldItems[i] != newItems[i]) {
                    notifyItemChanged(i)
                    changedCount++
                }
            }
            Log.d(
                TAG,
                "submitItems: same structure, $changedCount changed, ${SystemClock.elapsedRealtime() - t}ms"
            )
        } else if (oldItems.size < newItems.size &&
            oldItems.indices.all { viewTypeOf(oldItems[it]) == viewTypeOf(newItems[it]) }
        ) {
            // Items appended at the end (load more related)
            for (i in oldItems.indices) {
                if (oldItems[i] != newItems[i]) notifyItemChanged(i)
            }
            notifyItemRangeInserted(oldItems.size, newItems.size - oldItems.size)
            Log.d(
                TAG,
                "submitItems: appended ${newItems.size - oldItems.size} items, ${SystemClock.elapsedRealtime() - t}ms"
            )
        } else {
            notifyDataSetChanged()
            Log.d(
                TAG,
                "submitItems: structural change ${oldItems.size}->${newItems.size}, ${SystemClock.elapsedRealtime() - t}ms"
            )
        }
    }

    fun findIndex(predicate: (ArtworkDetailItem) -> Boolean): Int {
        return items.indexOfFirst(predicate)
    }

    fun updateItem(index: Int, item: ArtworkDetailItem) {
        if (index in items.indices && items[index] != item) {
            items[index] = item
            notifyItemChanged(index)
        }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = viewTypeOf(items[position])

    private fun viewTypeOf(item: ArtworkDetailItem): Int = when (item) {
        is ArtworkDetailItem.Hero -> TYPE_HERO
        is ArtworkDetailItem.Series -> TYPE_SERIES
        is ArtworkDetailItem.Desc -> TYPE_DESC
        is ArtworkDetailItem.Stats -> TYPE_STATS
        is ArtworkDetailItem.Tags -> TYPE_TAGS
        is ArtworkDetailItem.Artist -> TYPE_ARTIST
        is ArtworkDetailItem.DetailPanel -> TYPE_DETAIL
        is ArtworkDetailItem.Comments -> TYPE_COMMENTS
        is ArtworkDetailItem.AuthorWorks -> TYPE_AUTHOR_WORKS
        is ArtworkDetailItem.RelatedHeader -> TYPE_RELATED_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO -> HeroVH(SectionV3HeroBinding.inflate(inflater, parent, false))
            TYPE_SERIES -> SeriesVH(SectionV3SeriesBinding.inflate(inflater, parent, false))
            TYPE_DESC -> DescVH(SectionV3DescriptionBinding.inflate(inflater, parent, false))
            TYPE_STATS -> StatsVH(SectionV3StatsBinding.inflate(inflater, parent, false))
            TYPE_TAGS -> TagsVH(SectionV3TagsBinding.inflate(inflater, parent, false))
            TYPE_ARTIST -> ArtistVH(SectionV3ArtistBinding.inflate(inflater, parent, false))
            TYPE_DETAIL -> DetailPanelVH(
                SectionV3DetailPanelBinding.inflate(
                    inflater,
                    parent,
                    false
                )
            )

            TYPE_COMMENTS -> CommentsVH(SectionV3CommentsBinding.inflate(inflater, parent, false))
            TYPE_AUTHOR_WORKS -> AuthorWorksVH(
                SectionV3AuthorWorksBinding.inflate(
                    inflater,
                    parent,
                    false
                )
            )

            TYPE_RELATED_HEADER -> RelatedHeaderVH(
                SectionV3RelatedHeaderBinding.inflate(
                    inflater,
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val t = SystemClock.elapsedRealtime()
        val item = items[position]
        when {
            holder is HeroVH && item is ArtworkDetailItem.Hero -> holder.bind(item.illust)
            holder is SeriesVH && item is ArtworkDetailItem.Series -> holder.bind(item.illust)
            holder is DescVH && item is ArtworkDetailItem.Desc -> holder.bind(item.caption)
            holder is StatsVH && item is ArtworkDetailItem.Stats -> holder.bind(item.illust)
            holder is TagsVH && item is ArtworkDetailItem.Tags -> holder.bind(item.illust)
            holder is ArtistVH && item is ArtworkDetailItem.Artist -> holder.bind(
                item.illust,
                item.fullUser
            )

            holder is DetailPanelVH && item is ArtworkDetailItem.DetailPanel -> holder.bind(item.illust)
            holder is CommentsVH && item is ArtworkDetailItem.Comments -> holder.bind(item)
            holder is AuthorWorksVH && item is ArtworkDetailItem.AuthorWorks -> holder.bind(item)
            holder is RelatedHeaderVH && item is ArtworkDetailItem.RelatedHeader -> holder.bind(item)
        }
        val elapsed = SystemClock.elapsedRealtime() - t
        if (elapsed > 2) {
            Log.d(
                TAG,
                "onBindViewHolder pos=$position type=${getItemViewType(position)} took ${elapsed}ms"
            )
        }

        // Entrance animation: only the first time each view type appears
        val vt = getItemViewType(position)
        if (vt !in animatedViewTypes) {
            animatedViewTypes.add(vt)
            val view = holder.itemView
            view.alpha = 0f
            view.translationY = 16.ppppx.toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator(2.5f))
                .start()
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams) {
            lp.isFullSpan = true
        }
    }

    private val ctx: Context get() = fragment.requireContext()

    // =================== ViewHolders ===================

    inner class HeroVH(private val b: SectionV3HeroBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean) {
            b.heroTitle.text = illust.title
            b.metaType.text = when (illust.type) {
                "manga" -> ctx.getString(R.string.v3_type_manga)
                "ugoira" -> ctx.getString(R.string.v3_type_ugoira)
                else -> ctx.getString(R.string.v3_type_illustration)
            }
            b.metaDate.text = Common.getLocalYYYYMMDDHHMMString(illust.create_date)
            b.metaPages.text = if (illust.page_count == 1) ctx.getString(R.string.v3_page_count_one)
            else ctx.getString(R.string.v3_page_count_many, illust.page_count)
        }
    }

    inner class SeriesVH(private val b: SectionV3SeriesBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean) {
            val series = illust.series ?: return
            b.seriesName.text = series.title
            // Apply themed series strip
            val d = ctx.resources.displayMetrics.density
            b.seriesStrip.background = palette.seriesStripBg(20f * d)
            b.root.setOnClickListener {
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
                intent.putExtra(Params.MANGA_SERIES_ID, series.id)
                ctx.startActivity(intent)
            }
            applyTouchScale(b.root)
        }
    }

    inner class DescVH(private val b: SectionV3DescriptionBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(caption: String) {
            b.description.setHtml(caption)
        }
    }

    inner class StatsVH(private val b: SectionV3StatsBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean) {
            val fmt = NumberFormat.getNumberInstance()
            b.statViews.text = fmt.format(illust.total_view)
            b.statBookmarks.text = fmt.format(illust.total_bookmarks)
        }
    }

    inner class TagsVH(private val b: SectionV3TagsBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean) {
            b.tagsFlow.removeAllViews()
            illust.tags?.forEach { tag ->
                val tv = TextView(ctx).apply {
                    text = buildString {
                        append("# ")
                        append(tag.name ?: "")
                        if (!tag.translated_name.isNullOrBlank()) {
                            append("  "); append(tag.translated_name)
                        }
                    }
                    textSize = 13f
                    setTextColor(palette.textTag)
                    background = palette.tagLockedBg(999f * resources.displayMetrics.density)
                    setPadding(14.ppppx, 7.ppppx, 14.ppppx, 7.ppppx)
                    layoutParams = FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 8.ppppx, 8.ppppx) }
                    setOnClickListener {
                        val intent = Intent(ctx, SearchActivity::class.java)
                        intent.putExtra(Params.KEY_WORD, tag.name)
                        intent.putExtra(Params.INDEX, 0)
                        ctx.startActivity(intent)
                    }
                }
                applyTouchScale(tv, 0.94f)
                b.tagsFlow.addView(tv)
            }
        }
    }

    inner class ArtistVH(private val b: SectionV3ArtistBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean, fullUser: UserBean?) {
            val user = illust.user ?: return
            b.artistName.text = user.name
            b.artistHandle.text = "@${user.account ?: ""}"
            Glide.with(ctx)
                .load(GlideUtil.getUrl(user.profile_image_urls?.medium))
                .error(R.drawable.no_profile)
                .into(b.artistAvatar)

            b.artistCard.setOnClickListener {
                val intent = Intent(ctx, UserActivity::class.java)
                intent.putExtra(Params.USER_ID, user.id)
                ctx.startActivity(intent)
            }
            applyTouchScale(b.artistCard)

            if (fullUser != null) {
                bindFollowState(fullUser)
                b.artistBio.isVisible = !fullUser.comment.isNullOrBlank()
                if (b.artistBio.isVisible) b.artistBio.text = fullUser.comment
            } else {
                b.followBtn.text = ctx.getString(R.string.follow)
                palette.applyFollowBtn(b.followBtn)
                b.followBtn.setTextColor(Color.WHITE)
                b.artistBio.isVisible = false
            }
        }

        private fun bindFollowState(user: UserBean) {
            if (user.isIs_followed) {
                b.followBtn.text = ctx.getString(R.string.unfollow)
                palette.applyUnfollowBtn(b.followBtn)
                b.followBtn.setOnClick { fragment.unfollowUser(it as ProgressTextButton, user.id) }
            } else {
                b.followBtn.text = ctx.getString(R.string.follow)
                palette.applyFollowBtn(b.followBtn)
                b.followBtn.setTextColor(Color.WHITE)
                b.followBtn.setOnClick {
                    fragment.followUser(
                        it as ProgressTextButton,
                        user.id,
                        Params.TYPE_PUBLIC
                    )
                }
                b.followBtn.setOnLongClickListener {
                    fragment.followUser(b.followBtn, user.id, Params.TYPE_PRIVATE); true
                }
            }
        }
    }

    inner class DetailPanelVH(private val b: SectionV3DetailPanelBinding) :
        RecyclerView.ViewHolder(b.root) {

        private var expanded = true

        fun bind(illust: IllustsBean) {
            b.detailGrid.removeAllViews()
            buildChips(illust)
            b.detailHeader.setOnClickListener {
                expanded = !expanded
                val grid = b.detailGrid;
                val arrow = b.detailArrow
                if (!expanded) {
                    grid.animate().alpha(0f).translationY(-12.ppppx.toFloat()).setDuration(250)
                        .setInterpolator(DecelerateInterpolator(2f))
                        .withEndAction { grid.isVisible = false; grid.translationY = 0f }.start()
                    arrow.animate().rotation(180f).setDuration(300).start()
                } else {
                    grid.alpha = 0f; grid.translationY = -12.ppppx.toFloat(); grid.isVisible = true
                    grid.animate().alpha(1f).translationY(0f).setDuration(350)
                        .setInterpolator(DecelerateInterpolator(2f)).start()
                    arrow.animate().rotation(0f).setDuration(300).start()
                }
            }
        }

        private fun buildChips(illust: IllustsBean) {
            fun s(resId: Int) = ctx.getString(resId)
            val chips = listOf(
                s(R.string.v3_detail_artwork_id) to illust.id.toString(),
                s(R.string.v3_detail_user_id) to (illust.user?.id?.toString() ?: "--"),
                s(R.string.v3_detail_type) to when (illust.type) {
                    "manga" -> s(R.string.v3_type_manga)
                    "ugoira" -> s(R.string.v3_type_ugoira)
                    else -> s(R.string.v3_type_illustration)
                },
                s(R.string.v3_detail_resolution) to "${illust.width} × ${illust.height}",
                s(R.string.v3_detail_pages) to illust.page_count.toString(),
                s(R.string.v3_detail_ai) to if (illust.illust_ai_type == 2) s(R.string.v3_detail_ai_yes) else s(
                    R.string.v3_detail_ai_no
                ),
                s(R.string.v3_detail_restriction) to when {
                    illust.x_restrict == 1 -> "R-18"
                    illust.x_restrict == 2 -> "R-18G"
                    else -> s(R.string.v3_detail_all_ages)
                },
                s(R.string.v3_detail_published) to Common.getLocalYYYYMMDDHHMMString(illust.create_date)
            )
            for (i in chips.indices step 2) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    if (i > 0) layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8.ppppx }
                }
                row.addView(createDetailChip(chips[i].first, chips[i].second, illust))
                if (i + 1 < chips.size) {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(8.ppppx, 1)
                    })
                    row.addView(createDetailChip(chips[i + 1].first, chips[i + 1].second, illust))
                }
                b.detailGrid.addView(row)
            }
        }

        private fun createDetailChip(
            label: String,
            value: String,
            illust: IllustsBean
        ): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.v3_detail_chip_bg)
                setPadding(12.ppppx, 10.ppppx, 12.ppppx, 10.ppppx)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = label.uppercase(); textSize = 9f
                    setTextColor(ctx.getColor(R.color.v3_text_3)); letterSpacing = 0.08f; alpha =
                    0.7f
                })
                val artworkIdLabel = ctx.getString(R.string.v3_detail_artwork_id)
                val userIdLabel = ctx.getString(R.string.v3_detail_user_id)
                val aiLabel = ctx.getString(R.string.v3_detail_ai)
                val restrictionLabel = ctx.getString(R.string.v3_detail_restriction)
                addView(TextView(ctx).apply {
                    text = value; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setTypeface(
                        if (label == artworkIdLabel || label == userIdLabel) Typeface.MONOSPACE else typeface,
                        Typeface.BOLD
                    )
                    setTextColor(
                        when {
                            label == artworkIdLabel || label == userIdLabel -> palette.textAccent
                            label == aiLabel && illust.illust_ai_type == 2 -> ctx.getColor(R.color.v3_purple)
                            label == aiLabel -> ctx.getColor(R.color.v3_green)
                            label == restrictionLabel && illust.x_restrict > 0 -> ctx.getColor(R.color.v3_pink)
                            label == restrictionLabel -> ctx.getColor(R.color.v3_blue)
                            else -> ctx.getColor(R.color.v3_text_1)
                        }
                    )
                    alpha = if (label == artworkIdLabel || label == userIdLabel) 1f else 0.8f
                })
                setOnClickListener { Common.copy(ctx, value) }
            }
        }
    }

    inner class CommentsVH(private val b: SectionV3CommentsBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: ArtworkDetailItem.Comments) {
            b.commentsList.removeAllViews()
            b.commentsCount.text = item.comments.size.toString()
            item.comments.forEach { comment ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, 14.ppppx, 0, 14.ppppx)
                }
                val avatar = CircleImageView(ctx).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(36.ppppx, 36.ppppx).apply { marginEnd = 12.ppppx }
                }
                comment.user.profile_image_urls?.medium?.let {
                    Glide.with(ctx).load(GlideUrlChild(it)).circleCrop().into(avatar)
                }
                row.addView(avatar)
                val content = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val header = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(TextView(ctx).apply {
                    text = comment.user.name; textSize = 13f
                    setTextColor(ctx.getColor(R.color.v3_text_1)); setTypeface(
                    typeface,
                    Typeface.BOLD
                )
                })
                header.addView(TextView(ctx).apply {
                    text = comment.displayCommentDate(); textSize = 11f
                    setTextColor(ctx.getColor(R.color.v3_text_3)); setPadding(8.ppppx, 0, 0, 0)
                })
                content.addView(header)
                if (!comment.comment.isNullOrBlank()) {
                    content.addView(TextView(ctx).apply {
                        text = comment.comment; textSize = 13f
                        setTextColor(ctx.getColor(R.color.v3_text_1))
                        alpha = 0.72f
                        setPadding(0, 4.ppppx, 0, 0); setLineSpacing(0f, 1.65f)
                    })
                }
                if (comment.stamp?.stamp_url != null) {
                    val sv = com.google.android.material.imageview.ShapeableImageView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(80.ppppx, 80.ppppx)
                            .apply { topMargin = 6.ppppx }
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                            .setAllCornerSizes(8.ppppx.toFloat())
                            .build()
                    }
                    Glide.with(ctx).load(GlideUrlChild(comment.stamp!!.stamp_url!!)).into(sv)
                    content.addView(sv)
                }
                row.addView(content)
                if (b.commentsList.childCount > 0) {
                    b.commentsList.addView(View(ctx).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(ctx.getColor(R.color.v3_border_1))
                    })
                }
                b.commentsList.addView(row)
            }
            b.commentsMore.setOnClick {
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                intent.putExtra(Params.ILLUST_ID, item.illustId)
                intent.putExtra(Params.ILLUST_TITLE, item.illustTitle)
                ctx.startActivity(intent)
            }
        }
    }

    inner class AuthorWorksVH(private val b: SectionV3AuthorWorksBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var lAdapter: LAdapter? = null
        private val worksList = mutableListOf<IllustsBean>()

        fun bind(item: ArtworkDetailItem.AuthorWorks) {
            b.authorWorksLabel.text =
                ctx.getString(R.string.v3_author_works, item.authorName).uppercase()
            b.authorWorksSeeAll.setTextColor(palette.textAccent)

            if (lAdapter == null) {
                lAdapter = LAdapter(worksList, ctx)
                lAdapter!!.setOnItemClickListener { _, position, _ ->
                    val pageData = PageData(worksList)
                    Container.get().addPageToMap(pageData)
                    val intent = Intent(ctx, VActivity::class.java)
                    intent.putExtra(Params.POSITION, position)
                    intent.putExtra(Params.PAGE_UUID, pageData.uuid)
                    ctx.startActivity(intent)
                }
                b.authorWorksRv.layoutManager =
                    LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
                b.authorWorksRv.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(outRect: android.graphics.Rect, view: View,
                        parent: RecyclerView, state: RecyclerView.State) {
                        outRect.right = 8.ppppx
                    }
                })
                b.authorWorksRv.adapter = lAdapter
                val lp = b.authorWorksRv.layoutParams
                lp.height = lAdapter!!.imageSize +
                    ctx.resources.getDimensionPixelSize(R.dimen.sixteen_dp)
                b.authorWorksRv.layoutParams = lp
            }

            if (worksList != item.works) {
                worksList.clear()
                worksList.addAll(item.works)
                lAdapter?.notifyDataSetChanged()
            }

            b.authorWorksSeeAll.setOnClickListener {
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画作品")
                intent.putExtra(Params.USER_ID, item.userId)
                ctx.startActivity(intent)
            }
        }
    }

    inner class RelatedHeaderVH(private val b: SectionV3RelatedHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: ArtworkDetailItem.RelatedHeader) {
            b.relatedSeeMore.setTextColor(palette.textAccent)
            b.relatedSeeMore.setOnClick {
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关作品")
                intent.putExtra(Params.ILLUST_ID, item.illustId)
                intent.putExtra(Params.ILLUST_TITLE, item.illustTitle)
                ctx.startActivity(intent)
            }
        }
    }

    // =================== Helpers ===================

    private fun applyTouchScale(view: View, scale: Float = 0.97f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(200)
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f)
                    .scaleY(1f).setDuration(200).start()
            }
            false
        }
    }

    companion object {
        private const val TAG = "ArtworkV3Adapter"
        const val TYPE_HERO = 0
        const val TYPE_SERIES = 1
        const val TYPE_DESC = 2
        const val TYPE_STATS = 3
        const val TYPE_TAGS = 4
        const val TYPE_ARTIST = 5
        const val TYPE_DETAIL = 6
        const val TYPE_COMMENTS = 7
        const val TYPE_AUTHOR_WORKS = 8
        const val TYPE_RELATED_HEADER = 9
    }
}
