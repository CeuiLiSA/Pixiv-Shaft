package ceui.pixiv.ui.interpolate

import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.ui.common.DownloadableModel
import ceui.pixiv.ui.common.ModelDownloadFragment
import ceui.pixiv.ui.common.ModelDownloadManager

class RifeDownloadFragment : ModelDownloadFragment() {

    override fun resolveModel(): DownloadableModel {
        val name = arguments?.getString(ARG_MODEL_NAME) ?: RifeModel.RIFE_V4_6.name
        return RifeModel.values().first { it.name == name }
    }

    override fun getManager(): ModelDownloadManager = RifeModelManager
    override fun titleRes() = R.string.string_rife_download_title
    override fun subtitleRes() = R.string.string_rife_download_subtitle
    override fun doneTextRes() = R.string.string_rife_download_done

    companion object {
        private const val ARG_MODEL_NAME = "rife_model_name"

        @JvmStatic
        fun newInstance(modelName: String): RifeDownloadFragment {
            return RifeDownloadFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME, modelName)
                }
            }
        }
    }
}
