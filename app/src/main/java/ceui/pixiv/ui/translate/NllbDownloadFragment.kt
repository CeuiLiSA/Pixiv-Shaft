package ceui.pixiv.ui.translate

import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.ui.common.DownloadableModel
import ceui.pixiv.ui.common.ModelDownloadFragment
import ceui.pixiv.ui.common.ModelDownloadManager

class NllbDownloadFragment : ModelDownloadFragment() {

    override fun resolveModel(): DownloadableModel {
        val name = arguments?.getString(ARG_MODEL_NAME) ?: NllbTranslationModel.NLLB_600M.name
        return NllbTranslationModel.values().first { it.name == name }
    }

    override fun getManager(): ModelDownloadManager = NllbModelManager
    override fun titleRes() = R.string.string_nllb_download_title
    override fun subtitleRes() = R.string.string_nllb_download_subtitle
    override fun doneTextRes() = R.string.string_nllb_download_done

    companion object {
        private const val ARG_MODEL_NAME = "nllb_model_name"

        @JvmStatic
        fun newInstance(modelName: String): NllbDownloadFragment {
            return NllbDownloadFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME, modelName)
                }
            }
        }
    }
}
