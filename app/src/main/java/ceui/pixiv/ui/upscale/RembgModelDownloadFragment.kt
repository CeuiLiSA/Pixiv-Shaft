package ceui.pixiv.ui.upscale

import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.ui.common.DownloadableModel
import ceui.pixiv.ui.common.ModelDownloadFragment
import ceui.pixiv.ui.common.ModelDownloadManager

class RembgModelDownloadFragment : ModelDownloadFragment() {

    override fun resolveModel(): DownloadableModel {
        val name = arguments?.getString(ARG_MODEL_NAME) ?: RembgModel.ISNET_ANIME.name
        return RembgModel.values().first { it.name == name }
    }

    override fun getManager(): ModelDownloadManager = RembgModelManager
    override fun titleRes() = R.string.string_rembg_model_download_title
    override fun subtitleRes() = R.string.string_rembg_model_download_subtitle
    override fun doneTextRes() = R.string.string_rembg_model_download_done

    companion object {
        private const val ARG_MODEL_NAME = "model_name"

        @JvmStatic
        fun newInstance(modelName: String): RembgModelDownloadFragment {
            return RembgModelDownloadFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME, modelName)
                }
            }
        }
    }
}
