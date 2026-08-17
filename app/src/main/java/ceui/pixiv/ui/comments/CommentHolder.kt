package ceui.pixiv.ui.comments

import android.content.Context
import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.content.ContextCompat
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import de.hdodenhof.circleimageview.CircleImageView

/**
 * V3 评论卡片的主题强调色收口:强调色一律取自 [V3Palette.from]（跟随主题 colorPrimary + 日夜自适应）。
 * - 回复/查看回复文字 → 强调色
 * - 「作者」角标 → 强调色 15% 填充 + 强调色文字
 * - 头像描边 → 作者用强调色环,普通评论用极淡中性边
 * 删除按钮保持 v3_danger（危险语义,不跟随主题）。
 *
 * 原本这里还有 CommentChildHolder / CellChildCommentViewHolder(@ItemHolder + ListItemViewHolder),
 * 已迁到标准 RecyclerView 的 [ChildCommentAdapter],本文件只留主/子评论共用的强调色 helper。
 */
internal fun applyV3CommentAccents(
    context: Context,
    isAuthor: Boolean,
    avatar: CircleImageView,
    badge: TextView,
    reply: TextView,
    showReply: TextView? = null,
) {
    val palette = V3Palette.from(context)
    val accent = palette.textAccent
    reply.setTextColor(accent)
    showReply?.setTextColor(accent)
    badge.backgroundTintList = ColorStateList.valueOf(palette.alpha15)
    badge.setTextColor(accent)
    avatar.borderColor = if (isAuthor) accent
        else ContextCompat.getColor(context, R.color.v3_border_2)
}
