package ceui.pixiv.ui.upscale

import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.FragmentManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.DialogRembgModelPickerBinding
import ceui.lisa.utils.Local
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.PixivDialog

class RembgModelPickerDialog : PixivDialog(R.layout.dialog_rembg_model_picker) {

    private val binding by viewBinding(DialogRembgModelPickerBinding::bind)

    var onModelSelected: ((RembgModel) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewFirstCreated(view: View) {
        super.onViewFirstCreated(view)

        binding.cardIsnetAnime.setOnClick {
            onModelSelected?.invoke(RembgModel.ISNET_ANIME)
            dismissAllowingStateLoss()
        }

        binding.cardU2netp.setOnClick {
            onModelSelected?.invoke(RembgModel.U2NETP)
            dismissAllowingStateLoss()
        }

        binding.btnCancel.setOnClick {
            dismissAllowingStateLoss()
        }

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
        private const val TAG = "RembgModelPickerDialog"

        fun getSavedModel(): RembgModel? {
            val name = Shaft.sSettings.defaultRembgModel
            if (name.isNullOrEmpty()) return null
            return RembgModel.values().firstOrNull { it.name == name }
        }

        private fun saveModel(model: RembgModel) {
            Shaft.sSettings.defaultRembgModel = model.name
            Local.setSettings(Shaft.sSettings)
        }

        fun pickOrUseDefault(fragmentManager: FragmentManager, onReady: (RembgModel) -> Unit) {
            val saved = getSavedModel()
            if (saved != null) {
                onReady(saved)
            } else {
                if (fragmentManager.findFragmentByTag(TAG) != null) return
                val dialog = RembgModelPickerDialog()
                dialog.onModelSelected = { model ->
                    saveModel(model)
                    onReady(model)
                }
                dialog.show(fragmentManager, TAG)
            }
        }
    }
}
