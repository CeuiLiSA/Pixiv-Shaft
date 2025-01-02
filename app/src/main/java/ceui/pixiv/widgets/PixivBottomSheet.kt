package ceui.pixiv.widgets

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import ceui.pixiv.ui.common.NavFragmentViewModel
import ceui.pixiv.utils.screenHeight
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

open class PixivBottomSheet(layoutId: Int) : BottomSheetDialogFragment(layoutId) {

    private val fragmentViewModel: NavFragmentViewModel by viewModels()
    protected val viewModel by activityViewModels<DialogViewModel>()

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.skipCollapsed = true
        behavior.maxHeight = (screenHeight * 0.75F).roundToInt()
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
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