package ceui.pixiv.witstudio.dialog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ceui.pixiv.witstudio.databinding.WitDialogSwitchRowBinding

/**
 * 通用开关行 `wit_dialog_switch_row` 的取用入口。
 *
 * 存在的意义是「只有一处决定这行怎么填」：标题、副标题的有无、开关的无障碍描述与勾选态
 * 全在这里收敛。宿主只负责拿 [WitDialogSwitchRowBinding.witRowSwitch] 去读值、按需挂行点击。
 * 这样下一个弹窗不会再把 pb / fb 那两份抄来抄去，也不会再抄漏副标题的 GONE 或 saveEnabled。
 */
public object WitDialogSwitchRow {

    /** 按父容器 inflate 一行（不 attach，保留 XML 里的 match_parent 宽度）。 */
    @JvmStatic
    public fun inflate(parent: ViewGroup): WitDialogSwitchRowBinding =
        WitDialogSwitchRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)

    /**
     * 填入一行。
     *
     * [subtitle] 传 null / 空串即收起：连 text 一起清掉再 GONE，而不是只 GONE ——
     * 否则复用视图时「这行现在有没有副标题」在视图树里读不出来（同 WitMenuItemView.setStatus）。
     */
    @JvmStatic
    public fun bind(
        row: WitDialogSwitchRowBinding,
        title: CharSequence,
        subtitle: CharSequence?,
        checked: Boolean,
    ) {
        row.witRowTitle.text = title
        if (subtitle.isNullOrEmpty()) {
            row.witRowSubtitle.text = null
            row.witRowSubtitle.visibility = View.GONE
        } else {
            row.witRowSubtitle.text = subtitle
            row.witRowSubtitle.visibility = View.VISIBLE
        }
        row.witRowSwitch.contentDescription = title
        row.witRowSwitch.isChecked = checked
    }
}
