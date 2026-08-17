package ceui.pixiv.utils

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Material 在 edge-to-edge 下会给 design_bottom_sheet 垫一条导航栏高度的底 padding。
 * 当 sheet 背景透明或自绘时,这条 padding 区会露出后面的 scrim —— 表现为底部一条黑缝/
 * 灰带,甚至能透视到下层页面内容(见用户反馈的导出格式 / 下载选项 sheet)。
 *
 * 把 Material 的 inset listener 换成 noop 并清零 padding,让 sheet 内容自己的背景一路铺
 * 到屏幕底,缝隙消失。这与 [ceui.pixiv.ui.search.v3.V3BottomSheetBase] 对同一现象的处理
 * 是同一套做法,只是抽成公共扩展供 reader / novel / comic 各包的 sheet 共用。
 *
 * 在 [BottomSheetDialogFragment.onStart] 或 dialog 的 setOnShowListener 里调用
 * (此时 design_bottom_sheet 已经存在)。
 */
fun BottomSheetDialogFragment.letSheetDrawBehindNavBar() {
    val sheet = dialog?.findViewById<View>(
        com.google.android.material.R.id.design_bottom_sheet
    ) ?: return
    sheet.letDrawBehindNavBar()
}

/**
 * design_bottom_sheet 视图版,供已在 setOnShowListener 里拿到 sheet 引用的调用方复用,
 * 避免再 findViewById 一次。语义同 [letSheetDrawBehindNavBar]。
 *
 * 做两件事:
 *  A. 把 Material 给 design_bottom_sheet 装的 inset listener 换成 noop 并清零它的 padding
 *     —— 让内容自己的背景一路铺到屏幕底,消除底部那条透明缝/黑条。
 *  B. 给内容 child 的底 padding 叠加「导航栏 / 输入法」中较高的那个 —— 让最后一行内容抬离
 *     手势条(safe area),键盘弹起时则整块内容抬到键盘之上。
 *     内容 child 的背景仍会铺满含 padding 的区域,所以背景照样到屏幕底,只是内容上移。
 * A 保证「延伸进 safe area」,B 保证「内容不贴着手势条、也不被键盘盖住」。
 */
fun View.letDrawBehindNavBar() {
    // A
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets -> insets }
    setPadding(0, 0, 0, 0)
    // B —— 内容底 padding = 手势条 safe area 本身(不叠加各 sheet 自身参差的 XML
    //    paddingBottom,也不额外留白),这样所有 sheet 底距一致、内容正好贴着 safe area。
    val content = (this as? ViewGroup)?.getChildAt(0)
    if (content != null) {
        // 内容若是可滚动容器(如 ReaderSettingsPanel 的 NestedScrollView),clipToPadding
        // 默认 true 会把底 padding 区的内容裁掉(最后一行被切)——关掉,让内容滚进 padding、
        // 完整显示且滚到底时抬离手势条。对不滚动的 sheet 无副作用。
        (content as? ViewGroup)?.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            // 必须把 Type.ime() 一起吃进来(getInsets 传 or 起来的 type 就是逐边取 max):
            // 这一族 sheet 全部 edgeToEdge,Material 会 setDecorFitsSystemWindows(window,false),
            // dialog window 从此不随软键盘 resize/pan —— 只垫 systemBars 的话,带输入框的 sheet
            // (如 TagEditSheet 底部那条输入条)一点输入框就被键盘整个盖住(issue #1024)。
            // 垫上之后 sheet 长高一个键盘的量、内容被抬到键盘之上,背景照旧铺到屏幕底。
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            ).bottom
            v.updatePadding(bottom = bottom)
            insets
        }
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * 自绘背景的 sheet(内容 root 自带圆角背景)专用:把 design_bottom_sheet 本身设透明——否则
 * 主题默认的 sheet 背景(如 bg_dialog_header 白色 nine-patch)会盖在圆角外、暗色模式露白;
 * 再 [letDrawBehindNavBar] 让内容背景铺进底部 safe area。圆角与配色全由内容 root 的背景负责。
 */
fun BottomSheetDialogFragment.makeSheetTransparentAndFillNavBar() {
    val sheet = dialog?.findViewById<View>(
        com.google.android.material.R.id.design_bottom_sheet
    ) ?: return
    sheet.setBackgroundColor(Color.TRANSPARENT)
    sheet.letDrawBehindNavBar()
}
