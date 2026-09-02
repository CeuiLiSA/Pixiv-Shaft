package ceui.pixiv.ui.comments

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.CellCommentBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.utils.DateParse
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.ui.common.findActionReceiverOrNull
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.ui.user.binding_loadUserIcon
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import kotlin.math.roundToInt

/**
 * 主评论卡片的渲染逻辑，从 [CommentsFragment] 里搬出来单独放一个文件——对齐 legacy
 * `CommentHolder.kt` 的关注点分离（数据怎么画不挤在 Fragment 里，Fragment 只管编排）。
 *
 * 是 [CommentsFragment] 的扩展函数而非顶层 Fragment 扩展：renderer 只在
 * `onCreateRenderers()`（每次 onViewCreated 都重新构建，随 view 生死）里用一次，
 * 引用 [CommentsFragment] 的 `viewLifecycleOwner` / `findActionReceiverOrNull` 安全，
 * 不违反 feedViewModels 的零捕获约定（那条约定管的是 VM 长期持有的 FeedSource/mapper）。
 */
fun CommentsFragment.commentCardRenderer(): FeedRenderer<CommentFeedItem, CellCommentBinding> =
    feedRenderer<CommentFeedItem, CellCommentBinding>(
        inflate = CellCommentBinding::inflate,
        create = { cell ->
            val binding = cell.binding
            if (!isSnapshotMode) {
                binding.root.setOnClickListener {
                    cell.itemOrNull?.let { item ->
                        findActionReceiverOrNull<CommentActionReceiver>()?.onClickComment(item.comment)
                    }
                }
                binding.root.setOnLongClickListener { sender ->
                    cell.itemOrNull?.let { item ->
                        findActionReceiverOrNull<CommentActionReceiver>()
                            ?.onLongClickComment(sender, item.comment, 0L)
                    }
                    true
                }
                binding.userIcon.setOnClick {
                    cell.itemOrNull?.let { item ->
                        ObjectPool.update(item.comment.user)
                        findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(item.comment.user.id)
                    }
                }
                binding.userName.setOnClick {
                    cell.itemOrNull?.let { item ->
                        ObjectPool.update(item.comment.user)
                        findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(item.comment.user.id)
                    }
                }
                binding.reply.setOnClick {
                    cell.itemOrNull?.let { item ->
                        findActionReceiverOrNull<CommentActionReceiver>()?.onClickReply(item.comment, 0L)
                    }
                }
                binding.showReply.setOnClick { sender ->
                    cell.itemOrNull?.let { item ->
                        findActionReceiverOrNull<CommentActionReceiver>()
                            ?.onClickShowMoreReply(sender, item.comment.id)
                    }
                }
                binding.delete.setOnClick { sender ->
                    cell.itemOrNull?.let { item ->
                        findActionReceiverOrNull<CommentActionReceiver>()
                            ?.onClickDeleteComment(sender, item.comment, 0L)
                    }
                }
            }
        },
        recycle = { cell ->
            cancelCommentTranslationAnimation(cell.binding.translationBlock)
            cell.binding.userIcon.clearGlideOnRecycle()
            // 同步清掉 binding_loadUserIcon 的去重 tag,否则复用的 view 换绑到同头像 URL 的另一条
            // 评论时会误判"已加载"跳过重绘,而图其实已被上面 clear() 清空,头像永久空白
            cell.binding.userIcon.setTag(R.id.user_head_icon_tag, null)
            cell.binding.commentStamp.clearGlideOnRecycle()
        },
        // 译文写入 / 摘除只动 translations:走 payload 只重绑译文块 + 子回复列表,头像 / 图章的
        // Glide 请求、胶囊图标的 drawable 重建统统跳过。
        changePayload = { oldItem, newItem ->
            if (oldItem.copy(translations = newItem.translations) == newItem) TRANSLATIONS_PAYLOAD else null
        },
        bindPayloads = { cell, payloads ->
            if (payloads.all { it === TRANSLATIONS_PAYLOAD }) {
                bindTranslation(cell)
                bindChildComments(cell)
                true
            } else {
                false
            }
        },
    ) { cell ->
        val item = cell.item
        val comment = item.comment
        val binding = cell.binding
        val context = binding.root.context

        binding.userIcon.binding_loadUserIcon(comment.user)
        binding.userName.text = comment.user.name
        binding.commentTime.text = DateParse.displayCreateDate(comment.date)

        val hasStamp = comment.stamp != null
        binding.commentStamp.isVisible = hasStamp
        if (hasStamp) {
            Glide.with(binding.commentStamp).load(GlideUrlChild(comment.stamp?.stamp_url))
                .placeholder(R.drawable.bg_loading_placeholder)
                .into(binding.commentStamp)
        }
        binding.commentContent.isVisible = !hasStamp
        if (!hasStamp) {
            binding.commentContent.text = CommentEmojiSpanner.format(
                context,
                comment.comment,
                binding.commentContent.textSize.toInt(),
            )
        }
        bindTranslation(cell)

        binding.arthurLabel.isVisible = item.isArthurCommented
        applyV3CommentAccents(
            context = context,
            isAuthor = item.isArthurCommented,
            avatar = binding.userIcon,
            badge = binding.arthurLabel,
            reply = binding.reply,
            showReply = binding.showReply,
        )
        // 图标统一收口成 14dp 小尺寸:24dp 原生图标底 12sp 粗体文字会显得又大又偏上,
        // 视觉上跟文字不对齐;缩小之后 TextView 默认的纵向居中足够对齐,不需要额外 gravity 补偿
        val accent = V3Palette.from(context).textAccent
        val danger = ContextCompat.getColor(context, R.color.v3_danger)
        binding.reply.setCompoundDrawablesRelative(
            pillIcon(context, R.drawable.chat_ic_reply, accent), null, null, null
        )
        binding.showReply.setCompoundDrawablesRelative(
            null, null, pillIcon(context, R.drawable.ic_baseline_keyboard_arrow_down_24, accent), null
        )
        binding.delete.setCompoundDrawablesRelative(
            pillIcon(context, R.drawable.ic_delete_black_24dp, danger), null, null, null
        )

        binding.reply.isVisible = !isSnapshotMode && SessionManager.loggedInUid != comment.user.id
        binding.delete.isVisible = !isSnapshotMode && SessionManager.loggedInUid == comment.user.id
        // A locally sent reply can be shown before the pre-existing server thread is expanded.
        // Keep the affordance until that complete thread has actually been fetched.
        binding.showReply.isVisible = !isSnapshotMode && comment.has_replies == true && !item.repliesLoaded

        bindChildComments(cell)
    }

private fun CommentsFragment.bindTranslation(cell: FeedCell<CommentFeedItem, CellCommentBinding>) {
    val commentId = cell.item.comment.id
    bindCommentTranslation(
        block = cell.binding.translationBlock,
        textView = cell.binding.translationText,
        translation = cell.item.translations[commentId],
        reveal = pendingTranslationReveals.remove(commentId),
    )
}

private fun CommentsFragment.bindChildComments(cell: FeedCell<CommentFeedItem, CellCommentBinding>) {
    val item = cell.item
    val binding = cell.binding
    val context = binding.root.context
    if (item.childComments.isNotEmpty()) {
        binding.childCommentsList.isVisible = true
        if (binding.childCommentsList.itemDecorationCount == 0) {
            binding.childCommentsList.addItemDecoration(
                BottomDividerDecoration(context, R.drawable.list_divider_no_end, marginLeft = 12.ppppx)
            )
        }
        // 复用已挂在子 RecyclerView 上的 adapter(复用 cell 不重复 new),2026 RecyclerView 实践
        val childAdapter = binding.childCommentsList.adapter as? ChildCommentAdapter
            ?: ChildCommentAdapter().also {
                binding.childCommentsList.layoutManager = LinearLayoutManager(context)
                binding.childCommentsList.adapter = it
            }
        childAdapter.readOnly = isSnapshotMode
        childAdapter.pendingTranslationReveals = pendingTranslationReveals
        childAdapter.submitList(item.childComments.map { childComment ->
            ChildCommentItem(
                parentCommentId = item.comment.id,
                comment = childComment,
                illustArthurId = item.illustArthurId,
                translation = item.translations[childComment.id],
            )
        })
    } else {
        binding.childCommentsList.isVisible = false
        (binding.childCommentsList.adapter as? ChildCommentAdapter)?.submitList(emptyList())
    }
}

/** [CommentFeedItem] 只有 translations 变了时的 change payload,见 [commentCardRenderer]。 */
private val TRANSLATIONS_PAYLOAD = Any()

/** 操作胶囊图标:统一缩到 [sizeDp] 并着色。drawableStartCompat/EndCompat 在 XML 里只能吃图标
 *  原生 24dp，必须在代码里 setBounds 才能真正改渲染尺寸——mutate() 避免污染其他地方共用的
 *  同一份 drawable 缓存实例。 */
private fun pillIcon(
    context: Context,
    @DrawableRes resId: Int,
    @ColorInt tint: Int,
    sizeDp: Int = 14,
): Drawable {
    val drawable = ContextCompat.getDrawable(context, resId)!!.mutate()
    drawable.setTint(tint)
    val sizePx = (sizeDp * context.resources.displayMetrics.density).roundToInt()
    drawable.setBounds(0, 0, sizePx, sizePx)
    return drawable
}
