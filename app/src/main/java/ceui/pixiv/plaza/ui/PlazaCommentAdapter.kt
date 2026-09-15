package ceui.pixiv.plaza.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellPlazaCommentBinding
import ceui.lisa.databinding.CellPlazaCommentsTitleBinding
import ceui.lisa.databinding.CellPlazaPostBinding
import ceui.lisa.network.PlazaComment
import ceui.lisa.network.PlazaPost
import ceui.pixiv.chat.base.BaseListAdapter

/**
 * 评论列表 adapter。bind cell_plaza_comment;判断评论作者 = 帖子作者时挂「作者」标。
 *
 * RecyclerView 用 ListAdapter + DiffUtil(BaseListAdapter 封装),发新评论时
 * submitList 增量刷新,不全量重 bind。
 */
class PlazaCommentAdapter(
    /** 帖子作者 uid,用来判断每条评论是不是「作者」回复。 */
    private val postAuthorUid: Long,
) : BaseListAdapter<PlazaComment, PlazaCommentAdapter.VH>(
    diffCallback(keySelector = { it.id })
) {

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = CellPlazaCommentBinding.inflate(inflater, parent, false)
        return VH(binding, postAuthorUid)
    }

    override fun onBindDataViewHolder(holder: VH, item: PlazaComment) {
        holder.bind(item)
    }

    class VH(
        private val binding: CellPlazaCommentBinding,
        private val postAuthorUid: Long,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(c: PlazaComment) {
            val ctx = binding.root.context
            binding.displayName.text = c.display_name ?: c.uid.toString()
            binding.bodyText.text = c.text
            binding.postTime.text = formatRelativeTime(ctx, c.ts)
            binding.avatar.setImageResource(R.drawable.chat_avatar_placeholder)
            binding.authorTag.isVisible = c.uid == postAuthorUid && postAuthorUid > 0L
        }
    }
}

/**
 * "评论 (N)" 单行 header adapter。ConcatAdapter 拼在评论列表上面。
 * setTotal(n) 触发 notify。
 */
class PlazaCommentsTitleAdapter : RecyclerView.Adapter<PlazaCommentsTitleAdapter.VH>() {

    private var total: Int = 0

    fun setTotal(n: Int) {
        if (n == total) return
        total = n
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellPlazaCommentsTitleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(total)
    }

    class VH(val binding: CellPlazaCommentsTitleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(total: Int) {
            binding.title.text = binding.root.context
                .getString(R.string.plaza_comments_title, total)
        }
    }
}

/**
 * 帖子卡片 header adapter。复用 cell_plaza_post 视觉,通过 [bindPlazaPostCard]
 * 渲染。submitPost(p) 触发 rebind;p=null 表示还没加载,占位空 view。
 */
class PlazaPostHeaderAdapter(
    private val selfUid: Long,
    private val onMore: ((PlazaPost, View) -> Unit)?,
    private val onLike: () -> Unit,
    private val onComment: () -> Unit,
) : RecyclerView.Adapter<PlazaPostHeaderAdapter.VH>() {

    private var post: PlazaPost? = null

    fun submitPost(p: PlazaPost) {
        // 首帧 post 从 null → 非空时 itemCount 从 0 涨到 1,必须用 inserted 通知,
        // 否则 RecyclerView 还以为没条目,VH 永不会 onCreate,头部就空了。
        val firstSet = post == null
        post = p
        if (firstSet) notifyItemInserted(0) else notifyItemChanged(0)
    }

    override fun getItemCount(): Int = if (post != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellPlazaPostBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = post ?: return
        bindPlazaPostCard(
            binding = holder.binding,
            post = p,
            selfUid = selfUid,
            onMore = onMore,
            onCardClick = null, // 已经在详情页里,卡片不再可点
        )
        holder.binding.likeChip.setOnClickListener { onLike() }
        holder.binding.commentChip.setOnClickListener { onComment() }
    }

    class VH(val binding: CellPlazaPostBinding) : RecyclerView.ViewHolder(binding.root)
}

/**
 * 评论列表为空时的占位 cell。让用户知道"还没人评论",不是 loading 也不是 error。
 */
class PlazaCommentsEmptyAdapter : RecyclerView.Adapter<PlazaCommentsEmptyAdapter.VH>() {

    private var visible: Boolean = false

    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        if (v) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val tv = TextView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            gravity = android.view.Gravity.CENTER
            setPadding(0, 64, 0, 64)
            textSize = 14f
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            text = ctx.getString(R.string.plaza_comments_empty)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // 静态文案,无需 rebind
    }

    class VH(view: View) : RecyclerView.ViewHolder(view)
}
