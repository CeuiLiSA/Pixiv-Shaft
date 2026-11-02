package ceui.pixiv.db

import android.app.Activity
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.dialog.WitDialog

/**
 * 「数据库发生死锁/长排队」提示框。由 [DbCallTracker] 独占创建与关闭，别的地方不要直接 new。
 *
 * 三条硬约束：
 * - **不可返回取消**：`setCancelable(false)` + 点外面不关。库没恢复之前它挡在页面上，
 *   这是刻意的——用户点别的东西也只会卡住，不如把「为什么卡」直接摆出来。
 * - **唯一的出口是「恢复」**：[DbCallTracker] 探测到库重新响应就调 [dismissQuietly]。
 * - **「复制日志」不关窗**：它只是把现场拷走，关掉窗口应该由「库好了」来触发。
 */
class DbStallDialog private constructor(
    private val dialog: WitDialog,
    /** 这个弹窗挂在哪个页面上。[DbCallTracker] 用它判断页面销毁时要不要解绑。 */
    val host: Activity,
) {

    fun dismissQuietly() {
        runCatching { if (dialog.isShowing) dialog.dismiss() }
    }

    companion object {

        fun show(host: Activity, diagnostics: () -> String): DbStallDialog {
            val dialog = WitDialog.MessageDialogBuilder(host)
                .setTitle(R.string.db_stall_title)
                .setMessage(R.string.db_stall_message)
                .addAction(R.string.db_stall_copy_log) { dlg, _ ->
                    // 只复制、不关窗：关窗的唯一条件是库恢复。
                    Common.copy(host, diagnostics())
                }
                .create()
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
            return DbStallDialog(dialog, host)
        }
    }
}
