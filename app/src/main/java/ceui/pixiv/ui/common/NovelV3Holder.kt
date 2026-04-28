package ceui.pixiv.ui.common

import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelV3Binding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.DateParse
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.google.android.flexbox.FlexboxLayout
import java.text.NumberFormat

class NovelV3Holder(val novel: Novel) : ListItemHolder() {
    var isMultiSelectMode: Boolean = false
    var isSelected: Boolean = false

    init {
        ObjectPool.update(novel)
        novel.user?.let { ObjectPool.update(it) }
    }

    override fun getItemId(): Long = novel.id

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        if (other !is NovelV3Holder) return false
        return novel.id == other.novel.id
                && isMultiSelectMode == other.isMultiSelectMode
                && isSelected == other.isSelected
    }
}

@ItemHolder(NovelV3Holder::class)
class NovelV3ViewHolder(private val b: CellNovelV3Binding) :
    ListItemViewHolder<CellNovelV3Binding, NovelV3Holder>(b) {

    private val palette: V3Palette = V3Palette.from(context)
    private val fmt: NumberFormat = NumberFormat.getInstance()

    override fun onBindViewHolder(holder: NovelV3Holder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveNovel = ObjectPool.get<Novel>(holder.novel.id)
        val novel = liveNovel.value ?: holder.novel
        bind(novel, holder)

        // observe 收藏状态变化，收藏/取消后实时刷新按钮
        liveNovel.observe(lifecycleOwner) { updated ->
            if (updated != null) bindBookmark(updated, holder)
        }
    }

    private fun bind(novel: Novel, holder: NovelV3Holder) {
        bindCover(novel)
        bindTitle(novel)
        bindAuthor(novel)
        bindMeta(novel)
        bindBadges(novel)
        bindTags(novel)
        bindBookmark(novel, holder)
        bindMultiSelect(holder)
        bindClickActions(novel, holder)
    }

    // ── cover ───────────────────────────────────────────────────────

    private fun bindCover(novel: Novel) {
        val url = novel.image_urls?.let {
            it.medium ?: it.square_medium ?: it.large
        }
        Glide.with(context)
            .load(GlideUtil.getUrl(url))
            .placeholder(R.color.v3_surface_2)
            .error(R.color.v3_surface_2)
            .centerCrop()
            .into(b.novelCover)
    }

    // ── title ───────────────────────────────────────────────────────

    private fun bindTitle(novel: Novel) {
        b.novelTitle.text = novel.title ?: ""
    }

    // ── author ──────────────────────────────────────────────────────

    private fun bindAuthor(novel: Novel) {
        val user = novel.user
        if (user != null) {
            b.authorRow.isVisible = true
            b.authorName.text = user.name ?: ""
            Glide.with(context)
                .load(GlideUtil.getUrl(user.profile_image_urls?.medium))
                .placeholder(R.drawable.no_profile)
                .error(R.drawable.no_profile)
                .into(b.authorAvatar)
            b.authorRow.setOnClick { sender ->
                sender.findActionReceiverOrNull<UserActionReceiver>()
                    ?.onClickUser(user.id)
            }
        } else {
            b.authorRow.isVisible = false
        }
    }

    // ── meta: word count + date ─────────────────────────────────────

    private fun bindMeta(novel: Novel) {
        val wordCount = novel.text_length
        if (wordCount != null && wordCount > 0) {
            b.wordCount.isVisible = true
            b.wordCount.text = context.getString(
                R.string.v3_novel_word_count,
                fmt.format(wordCount)
            )
        } else {
            b.wordCount.isVisible = false
        }

        b.publishDate.text = DateParse.getTimeAgo(context, novel.create_date)
    }

    // ── badges ──────────────────────────────────────────────────────

    private fun bindBadges(novel: Novel) {
        b.badgeR18.isVisible = novel.is_x_restricted == true || (novel.x_restrict ?: 0) > 0
        b.badgeOriginal.isVisible = novel.is_original == true
    }


    // ── tags ────────────────────────────────────────────────────────

    private fun bindTags(novel: Novel) {
        val tags = novel.tags
        if (!tags.isNullOrEmpty()) {
            b.tagsSection.isVisible = true
            b.tagsFlow.removeAllViews()
            val density = context.resources.displayMetrics.density
            val tagBg = palette.tagLockedBg(999f * density).constantState

            val maxTags = 6 // show at most 6 tags in preview
            tags.take(maxTags).forEachIndexed { index, tag ->
                val tv = android.widget.TextView(context).apply {
                    text = buildString {
                        append("# ")
                        append(tag.name ?: "")
                        if (!tag.translated_name.isNullOrBlank()) {
                            append("  ")
                            append(tag.translated_name)
                        }
                    }
                    textSize = 11f
                    setTextColor(palette.textTag)
                    background = tagBg?.newDrawable()?.mutate()
                    setPaddingRelative(10.ppppx, 5.ppppx, 10.ppppx, 5.ppppx)
                    layoutParams = FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        setMargins(0, 0, 6.ppppx, 6.ppppx)
                        flexShrink = 0f
                    }
                    setOnClickListener {
                        val intent = Intent(context, SearchActivity::class.java).apply {
                            putExtra(Params.KEY_WORD, tag.name)
                            putExtra(Params.INDEX, 1) // 1 = novel search tab
                        }
                        context.startActivity(intent)
                    }
                }
                applyTouchScale(tv, 0.94f)
                b.tagsFlow.addView(tv)
            }

            // "+N" overflow indicator
            if (tags.size > maxTags) {
                val overflow = android.widget.TextView(context).apply {
                    text = "+${tags.size - maxTags}"
                    textSize = 11f
                    setTextColor(palette.textSecondary)
                    background = tagBg?.newDrawable()?.mutate()
                    setPaddingRelative(10.ppppx, 5.ppppx, 10.ppppx, 5.ppppx)
                    layoutParams = FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        setMargins(0, 0, 6.ppppx, 6.ppppx)
                        flexShrink = 0f
                    }
                }
                b.tagsFlow.addView(overflow)
            }
        } else {
            b.tagsSection.isVisible = false
        }
    }


    // ── bookmark button ─────────────────────────────────────────────

    private fun bindBookmark(novel: Novel, holder: NovelV3Holder) {
        val bookmarked = novel.is_bookmarked == true
        b.bookmarkBtn.setImageResource(
            if (bookmarked) R.drawable.icon_liked else R.drawable.icon_not_liked
        )
        b.bookmarkBtn.imageTintList = if (bookmarked) {
            null
        } else {
            android.content.res.ColorStateList.valueOf(context.getColor(R.color.v3_text_3))
        }
        b.bookmarkBtn.setOnClick { sender ->
            sender.findActionReceiverOrNull<NovelActionReceiver>()
                ?.onClickBookmarkNovel(sender as ProgressIndicator, holder.novel.id)
        }
    }

    // ── multi-select ────────────────────────────────────────────────

    private fun bindMultiSelect(holder: NovelV3Holder) {
        b.selectIndicator.isVisible = holder.isMultiSelectMode
        if (holder.isMultiSelectMode) {
            if (holder.isSelected) {
                b.selectIndicator.setImageResource(R.drawable.ic_check_circle_black_24dp)
                b.selectIndicator.clearColorFilter()
            } else {
                b.selectIndicator.setImageResource(R.drawable.ic_checkbox_off)
                b.selectIndicator.setColorFilter(context.getColor(R.color.v3_text_3))
            }
        }
    }

    // ── root click ──────────────────────────────────────────────────

    private fun bindClickActions(novel: Novel, holder: NovelV3Holder) {
        b.root.setOnClick { sender ->
            if (holder.isMultiSelectMode) {
                sender.findActionReceiverOrNull<NovelMultiSelectReceiver>()
                    ?.onToggleNovelSelection(holder.novel.id)
                return@setOnClick
            }
            sender.findActionReceiverOrNull<NovelActionReceiver>()
                ?.onClickNovel(holder.novel.id)
        }
        applyTouchScale(b.root, 0.98f)
    }

    // ── touch scale helper ──────────────────────────────────────────

    private fun applyTouchScale(view: View, scale: Float = 0.97f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(scale).scaleY(scale).setDuration(200).start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            false
        }
    }
}
