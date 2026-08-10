package ceui.lisa.fragments

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import ceui.lisa.R

/**
 * 历史选择态变化的增量 payload（按引用识别），三个历史 tab 的 renderer 共用。
 *
 * 存在的理由是图片不能跟着选择态重绑：勾选会把 isSelected 回灌进 FeedItem（equals 含选择态）,
 * DiffUtil 因此判「内容变了」；没有 payload 就走全量重绑，`Glide.load(...).into(image)` 会先
 * clear 旧请求，`ImageViewTarget.onLoadCleared` 立刻把 bitmap 换成占位色再起新请求 —— 封面闪一下。
 *
 * 而且这里连内存缓存都救不了：[ceui.lisa.utils.GlideUrlChild] 每次构造都新建一个捕获局部 map 的
 * headers lambda，`GlideUrl.equals` 要求 headers 也相等，于是新旧请求的 EngineKey 永远不同、
 * `SingleRequest.isEquivalentTo` 也永远为 false。占位色要等磁盘解码回来才被顶掉，所以闪烁是
 * 概率性的（取决于解码和下一帧绘制谁快）。headers 里带着 x-client-time 这类每次都变的字段，
 * 让它内容相等是另一件事，本处用增量绑定绕开这条触发路径。
 */
val PAYLOAD_HISTORY_SELECTION = Any()

/**
 * 浏览历史多选态的勾选角标渲染。三个 holder(插画/小说/用户)共用一份,保证三 tab
 * 的选中/未选中视觉一致。
 *  - 非选择态:隐藏
 *  - 选择态 + 已选:实心(?attr/colorPrimary)圆 + 白勾
 *  - 选择态 + 未选:半透明空心圈(无勾),提示"可点选"
 */
object HistorySelectBadge {

    /**
     * 选择态的全部视图效果：勾标 + 删除钮显隐。全量绑定与增量绑定都只经这一个入口，
     * 两条路径就不会漏掉其中一样而错位（增量路径少刷一样 = 选择态下删除钮还留在卡上）。
     */
    fun bindSelection(badge: ImageView, deleteItem: View, selectionMode: Boolean, selected: Boolean) {
        badge.isVisible = selectionMode
        deleteItem.isVisible = !selectionMode
        if (!selectionMode) return
        if (selected) {
            badge.setBackgroundResource(R.drawable.bulk_select_check_bg)
            badge.setImageResource(R.drawable.ic_check_24dp)
            badge.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } else {
            badge.setBackgroundResource(R.drawable.history_check_unselected)
            badge.setImageDrawable(null)
        }
    }

    /**
     * 增量绑定入口：payload 全是 [PAYLOAD_HISTORY_SELECTION] 时只刷勾标与删除钮（不碰 Glide）
     * 并返回 true；混进不认识的 payload 就返回 false，由框架回落全量绑定。
     *
     * 空列表也返回 false：`all {}` 对空集恒真，会把「没有任何变更信息」当成「只有选择态变了」
     * 而跳掉图片/文字的绑定。今天 FeedAdapter.bindInternal 已经把空 payload 路由到全量绑定、
     * 走不到这里，但这个判断是本函数自己的契约，不该寄存在调用方的实现细节上。
     */
    fun bindSelectionPayload(
        payloads: List<Any>,
        badge: ImageView,
        deleteItem: View,
        selectionMode: Boolean,
        selected: Boolean,
    ): Boolean {
        if (payloads.isEmpty()) return false
        if (!payloads.all { it === PAYLOAD_HISTORY_SELECTION }) return false
        bindSelection(badge, deleteItem, selectionMode, selected)
        return true
    }
}
