package ceui.pixiv.ui.upscale

import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.FragmentManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.DialogModelPickerBinding
import ceui.lisa.utils.Local
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

        /** Resolve the saved default model from settings, or null if not set yet. */
        fun getSavedModel(): UpscaleModel? {
            val name = Shaft.sSettings.defaultUpscaleModel
            if (name.isNullOrEmpty()) return null
            return UpscaleModel.values().firstOrNull { it.name == name }
        }

        /** Save chosen model to settings. */
        private fun saveModel(model: UpscaleModel) {
            Shaft.sSettings.defaultUpscaleModel = model.name
            Local.setSettings(Shaft.sSettings)
        }

        /**
         * Show picker dialog unconditionally (for settings page).
         * Saves choice but does NOT invoke [onSelected].
         */
        fun show(fragmentManager: FragmentManager, onSelected: (UpscaleModel) -> Unit) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            val dialog = ModelPickerDialog()
            dialog.onModelSelected = onSelected
            dialog.show(fragmentManager, TAG)
        }

        /**
         * Central entry point for all upscale actions.
         * If a default model is already saved, calls [onReady] immediately.
         * Otherwise shows the picker, saves the choice, then calls [onReady].
         */
        fun pickOrUseDefault(fragmentManager: FragmentManager, onReady: (UpscaleModel) -> Unit) {
            val saved = getSavedModel()
            if (saved != null) {
                onReady(saved)
            } else {
                show(fragmentManager) { model ->
                    saveModel(model)
                    onReady(model)
                }
            }
        }
    }
}
