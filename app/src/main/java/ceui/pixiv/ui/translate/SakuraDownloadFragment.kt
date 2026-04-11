package ceui.pixiv.ui.translate

import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.ui.common.DownloadableModel
import ceui.pixiv.ui.common.ModelDownloadFragment
import ceui.pixiv.ui.common.ModelDownloadManager

class SakuraDownloadFragment : ModelDownloadFragment() {

    override fun resolveModel(): DownloadableModel {
        val name = arguments?.getString(ARG_MODEL_NAME) ?: SakuraModel.SAKURA_1_5B.name
        return SakuraModel.values().first { it.name == name }
    }

    override fun getManager(): ModelDownloadManager = SakuraModelManager
    override fun titleRes() = R.string.string_sakura_download_title
    override fun subtitleRes() = R.string.string_sakura_download_subtitle
    override fun doneTextRes() = R.string.string_sakura_download_done

    companion object {
        private const val ARG_MODEL_NAME = "sakura_model_name"

        @JvmStatic
        fun newInstance(modelName: String): SakuraDownloadFragment {
            return SakuraDownloadFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME, modelName)
                }
            }
        }
    }
}
