package ceui.pixiv.ui.upscale

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.FragmentManager
import ceui.lisa.R
import ceui.lisa.databinding.DialogModelPickerBinding
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.PixivDialog

class ModelPickerDialog : PixivDialog(R.layout.dialog_model_picker) {

    private val binding by viewBinding(DialogModelPickerBinding::bind)

    var onModelSelected: ((UpscaleModel) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewFirstCreated(view: View) {
        super.onViewFirstCreated(view)

        binding.cardEsrgan.setOnClick {
            onModelSelected?.invoke(UpscaleModel.REAL_ESRGAN)
            dismissAllowingStateLoss()
        }

        binding.cardCugan.setOnClick {
            onModelSelected?.invoke(UpscaleModel.REAL_CUGAN)
            dismissAllowingStateLoss()
        }

        binding.btnCancel.setOnClick {
            dismissAllowingStateLoss()
        }

        animateEntrance()
    }

    private fun animateEntrance() {
        binding.heroIcon.apply {
            scaleX = 0f
            scaleY = 0f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setInterpolator(OvershootInterpolator(2f))
                .setStartDelay(150)
                .start()
        }
    }

    companion object {
        private const val TAG = "ModelPickerDialog"

        fun show(fragmentManager: FragmentManager, onSelected: (UpscaleModel) -> Unit) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            val dialog = ModelPickerDialog()
            dialog.onModelSelected = onSelected
            dialog.show(fragmentManager, TAG)
        }
    }
}
