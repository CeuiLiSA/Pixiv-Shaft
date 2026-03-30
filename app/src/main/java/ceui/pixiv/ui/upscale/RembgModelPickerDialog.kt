package ceui.pixiv.ui.upscale

import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.FragmentManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.DialogRembgModelPickerBinding
import ceui.lisa.utils.Common
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

        updateModelStatus()

        binding.cardIsnetAnime.setOnClick {
            selectModel(RembgModel.ISNET_ANIME)
        }

        binding.cardU2netp.setOnClick {
            selectModel(RembgModel.U2NETP)
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

    private fun updateModelStatus() {
        val ctx = context ?: return
        val isnetReady = RembgModelManager.isModelReady(ctx, RembgModel.ISNET_ANIME)
        binding.isnetStatusBadge.visibility = View.VISIBLE
        if (isnetReady) {
            binding.isnetStatusBadge.text = getString(R.string.string_rembg_model_ready)
            binding.isnetStatusBadge.setBackgroundResource(R.drawable.bg_upscale_resolution_badge)
            binding.isnetStatusBadge.setTextColor(0xFF7B6CF6.toInt())
        } else {
            binding.isnetStatusBadge.text = getString(R.string.string_rembg_model_needs_download)
            binding.isnetStatusBadge.setBackgroundResource(R.drawable.bg_rate_button_primary)
            binding.isnetStatusBadge.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun selectModel(model: RembgModel) {
        val ctx = context ?: return
        if (!model.bundledInApk && !RembgModelManager.isModelReady(ctx, model)) {
            dismissAllowingStateLoss()
            val intent = Intent(ctx, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "模型下载")
            intent.putExtra("model_name", model.name)
            ctx.startActivity(intent)
        } else {
            onModelSelected?.invoke(model)
            dismissAllowingStateLoss()
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
            if (saved != null && (saved.bundledInApk || RembgModelManager.isModelReady(Shaft.getContext(), saved))) {
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
