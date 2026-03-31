package ceui.pixiv.ui.translate

import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.ui.common.DownloadableModel
import ceui.pixiv.ui.common.ModelDownloadFragment
import ceui.pixiv.ui.common.ModelDownloadManager

class TranslationModelDownloadFragment : ModelDownloadFragment() {

    override fun resolveModel(): DownloadableModel {
        val name = arguments?.getString(ARG_MODEL_NAME) ?: TranslationModel.OPUS_MT_JA_ZH.name
        return TranslationModel.values().first { it.name == name }
    }

    override fun getManager(): ModelDownloadManager = TranslationModelManager
    override fun titleRes() = R.string.string_translation_model_download_title
    override fun subtitleRes() = R.string.string_translation_model_download_subtitle
    override fun doneTextRes() = R.string.string_translation_model_download_done

    companion object {
        private const val ARG_MODEL_NAME = "translation_model_name"

        @JvmStatic
        fun newInstance(modelName: String): TranslationModelDownloadFragment {
            return TranslationModelDownloadFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME, modelName)
                }
            }
        }
    }
}
