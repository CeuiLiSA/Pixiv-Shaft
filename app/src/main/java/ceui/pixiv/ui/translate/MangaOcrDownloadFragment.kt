package ceui.pixiv.ui.translate

import android.os.Bundle
import ceui.lisa.R
import ceui.pixiv.ui.common.DownloadableModel
import ceui.pixiv.ui.common.ModelDownloadFragment
import ceui.pixiv.ui.common.ModelDownloadManager

class MangaOcrDownloadFragment : ModelDownloadFragment() {

    override fun resolveModel(): DownloadableModel {
        val name = arguments?.getString(ARG_MODEL_NAME) ?: MangaOcrModel.MANGA_OCR_BASE.name
        return MangaOcrModel.values().first { it.name == name }
    }

    override fun getManager(): ModelDownloadManager = MangaOcrModelManager
    override fun titleRes() = R.string.string_manga_ocr_download_title
    override fun subtitleRes() = R.string.string_manga_ocr_download_subtitle
    override fun doneTextRes() = R.string.string_manga_ocr_download_done

    companion object {
        private const val ARG_MODEL_NAME = "manga_ocr_model_name"

        @JvmStatic
        fun newInstance(modelName: String): MangaOcrDownloadFragment {
            return MangaOcrDownloadFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME, modelName)
                }
            }
        }
    }
}
