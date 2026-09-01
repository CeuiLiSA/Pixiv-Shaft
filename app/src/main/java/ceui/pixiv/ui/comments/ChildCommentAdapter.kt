package ceui.pixiv.ui.comments

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellChildCommentBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.api.model.Comment
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.findActionReceiverOrNull
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.ui.user.binding_loadUserIcon
import ceui.pixiv.utils.DateParse
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

/**
 * 一条主评论下面挂着的「子回复」列表项。原先靠 CommentChildHolder + CommonAdapter
 * (@ItemHolder / ListItemViewHolder) 渲染,现改为标准 RecyclerView.ListAdapter,不再依赖
 * 那套注解处理器框架。[parentCommentId] / [illustArthurId] 对同一条主评论是常量,随 item 携带。
 */
data class ChildCommentItem(
    val parentCommentId: Long,
    val comment: Comment,
    val illustArthurId: Long,
) {
    val isArthurCommented: Boolean
        get() = illustArthurId == comment.user.id
}

/**
 * 子回复列表适配器:androidx [ListAdapter] + [DiffUtil] + ViewBinding。
 * 在 CommentCardRenderer 的子 RecyclerView 上复用(设一次,后续只 submitList)。
 */
class ChildCommentAdapter : ListAdapter<ChildCommentItem, ChildCommentViewHolder>(DIFF) {

    /** 快照只读模式：隐藏回复/删除按钮，禁止在线操作。 */
    var readOnly: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChildCommentViewHolder {
        val binding = CellChildCommentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChildCommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChildCommentViewHolder, position: Int) {
        holder.bind(getItem(position), readOnly)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChildCommentItem>() {
            override fun areItemsTheSame(oldItem: ChildCommentItem, newItem: ChildCommentItem): Boolean =
                oldItem.comment.id == newItem.comment.id

            override fun areContentsTheSame(oldItem: ChildCommentItem, newItem: ChildCommentItem): Boolean =
                oldItem.comment == newItem.comment && oldItem.illustArthurId == newItem.illustArthurId
        }
    }
}

class ChildCommentViewHolder(
    private val binding: CellChildCommentBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: ChildCommentItem, readOnly: Boolean = false) {
        val comment = item.comment
        val context = binding.root.context

        // 头像 / 名字 / 时间(原本靠 XML @{holder...} 绑,现全部在代码里手绑)
        binding.userIcon.binding_loadUserIcon(comment.user)
        binding.userName.text = comment.user.name
        binding.commentTime.text = DateParse.displayCreateDate(comment.date)

        val hasStamp = comment.stamp != null
        binding.commentStamp.isVisible = hasStamp
        if (hasStamp) {
            Glide.with(context).load(GlideUrlChild(comment.stamp?.stamp_url))
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

        binding.arthurLabel.isVisible = item.isArthurCommented
        applyV3CommentAccents(
            context = context,
            isAuthor = item.isArthurCommented,
            avatar = binding.userIcon,
            badge = binding.arthurLabel,
            reply = binding.reply,
        )

        binding.reply.isVisible = !readOnly && SessionManager.loggedInUid != comment.user.id
        binding.delete.isVisible = !readOnly && SessionManager.loggedInUid == comment.user.id

        if (!readOnly) {
            binding.root.setOnClickListener { sender ->
                sender.findActionReceiverOrNull<CommentActionReceiver>()?.onClickComment(comment)
            }
            binding.root.setOnLongClickListener { sender ->
                sender.findActionReceiverOrNull<CommentActionReceiver>()
                    ?.onLongClickComment(sender, comment, item.parentCommentId)
                true
            }
            binding.userIcon.setOnClick {
                ObjectPool.update(comment.user)
                it.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(comment.user.id)
            }
            binding.userName.setOnClick {
                ObjectPool.update(comment.user)
                it.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(comment.user.id)
            }
            binding.reply.setOnClick { sender ->
                sender.findActionReceiverOrNull<CommentActionReceiver>()
                    ?.onClickReply(comment, item.parentCommentId)
            }
            binding.delete.setOnClick { sender ->
                sender.findActionReceiverOrNull<CommentActionReceiver>()
                    ?.onClickDeleteComment(sender, comment, item.parentCommentId)
            }
        }
    }
}
