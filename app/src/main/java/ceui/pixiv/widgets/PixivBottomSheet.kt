package ceui.pixiv.widgets

import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import ceui.lisa.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

open class PixivBottomSheet(layoutId: Int) : BottomSheetDialogFragment(layoutId) {

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}