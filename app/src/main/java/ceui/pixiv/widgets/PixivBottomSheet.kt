package ceui.pixiv.widgets

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.pixiv.ui.common.NavFragmentViewModel
import ceui.pixiv.utils.letDrawBehindNavBar
import ceui.pixiv.utils.screenHeight
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

open class PixivBottomSheet(layoutId: Int) : BottomSheetDialogFragment(layoutId) {

    private val fragmentViewModel: NavFragmentViewModel by viewModels()
    protected val viewModel by activityViewModels<DialogViewModel>()

    // 本 App 主题继承 QMUI,没定义 bottomSheetDialogTheme,不指定的话 BottomSheetDialog 会回落到
    // Theme.Design.Light.BottomSheetDialog —— 容器永远白底,夜间模式下文字又跟了 night 色,
    // 白底灰字(issue #1042)。统一走 reader 系 sheet 的做法:edgeToEdge overlay + 容器透明,
    // 背景由各 sheet 布局 root 自己铺 v3_bg(跟随日夜)。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.skipCollapsed = true
        behavior.maxHeight = (screenHeight * 0.75F).roundToInt()
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.setBackgroundColor(Color.TRANSPARENT)
        // 让 sheet 背景铺到屏幕底,消除底部 nav bar 那条透明缝/黑条。
        bottomSheet.letDrawBehindNavBar()
    }

    open fun onViewFirstCreated(view: View) {

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        if (fragmentViewModel.viewCreatedTime.value == null) {
            onViewFirstCreated(view)
        }

        fragmentViewModel.viewCreatedTime.value = System.currentTimeMillis()
    }
}