package ceui.pixiv.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 「复制链接或导入已下载好的文件」统一控制器，供模型下载页与漫画翻译首次准备 sheet 共用。
 *
 * 文件选择姿势对齐项目还原/导入代码（FragmentHistoryTabs / SynonymDictFragment）：
 * 宿主 Fragment 用 registerForActivityResult(ActivityResultContracts.OpenDocument) 注册 launcher，
 * 通过 MIME 类型数组设置 zip 相关文件类型；选中后在 IO 线程读取文件，
 * 交给 [ModelDownloadManager.importModel] 做与现网下载一致的校验安装。
 */
class ModelImportController(
    private val fragment: Fragment,
    private val picker: ActivityResultLauncher<Array<String>>,
    private val manager: ModelDownloadManager,
    private val model: DownloadableModel,
    private val onImportStarted: () -> Unit = {},
    private val onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    private val onImported: (Boolean) -> Unit,
) {
    /** 防重入：导入进行中再次选文件直接忽略，避免并发写同一个临时 zip / staging 目录。 */
    private var importInFlight = false

    fun showCopyOrImportDialog() {
        val ctx = fragment.requireContext()
        val builder = QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.model_download_dialog_title)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .setMessage(fragment.getString(R.string.model_download_dialog_message))

        if (model.downloadUrl != null) {
            builder.addAction(
                0,
                fragment.getString(R.string.model_download_copy_link),
                QMUIDialogAction.ACTION_PROP_POSITIVE,
            ) { dialog, _ ->
                dialog.dismiss()
                copyLink(ctx, model)
            }
        }
        builder.addAction(
            0,
            fragment.getString(R.string.model_download_import_file),
            QMUIDialogAction.ACTION_PROP_POSITIVE,
        ) { dialog, _ ->
            dialog.dismiss()
            launchPicker()
        }
        builder.show()
    }

    /** 系统缺少可用的文件选择器（DocumentsUI/文件管理器）时启动会抛异常，兜底弹 toast 而不是崩页面。 */
    private fun launchPicker() {
        try {
            picker.launch(ZIP_MIME_TYPES)
        } catch (e: ActivityNotFoundException) {
            Timber.e(e, "model import: no activity for document picker")
            Common.showToast(fragment.getString(R.string.model_download_picker_unavailable))
        }
    }

    /** 文件选择回调统一入口：拿到 URI 后在 IO 线程导入，校验结果回抛给 [onImported]。 */
    fun handlePickedUri(uri: Uri?) {
        if (uri == null || importInFlight) return
        val ctx = fragment.context ?: return
        if (!fragment.isAdded || fragment.view == null) return
        importInFlight = true
        onImportStarted()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                manager.importModel(ctx, model, uri, onProgress)
            }
            importInFlight = false
            onImported(success)
        }
    }

    private fun copyLink(ctx: Context, model: DownloadableModel) {
        val url = model.downloadUrl ?: return
        if (ClipBoardUtils.setPrimaryClip(ctx, ClipData.newPlainText("model-download-link", url))) {
            Common.showToast(ctx.getString(R.string.msg_link_copied))
        }
    }

    companion object {
        private val ZIP_MIME_TYPES =
            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
    }
}
