package ceui.pixiv.ui.translate

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.DialogMangaTranslatePrepBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.DownloadableModel
import ceui.pixiv.ui.common.ModelImportController
import ceui.pixiv.ui.common.ModelDownloadManager
import ceui.pixiv.ui.search.v3.V3BottomSheetBase
import ceui.pixiv.utils.setOnClick
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.widget.ImageView
import android.widget.TextView

/**
 * 「翻译漫画」首次准备 sheet —— 把 CTD 检测模型 + Manga-OCR 识别模型两次下载
 * 串成单一 UX:一个 CTA、两条 row 各自显示进度,全部就绪后回调 [onReady] 让父
 * Activity 直接进翻译流水线,零跳转零再点击。
 *
 * Lifecycle 注意:
 * - 下载用 viewLifecycleOwner.lifecycleScope,sheet dismiss / 旋转重建时自动 cancel
 *   (一次性首次设置流,不做跨配置变化的持久化)
 * - [onReady] 是 transient lambda(不走 SavedState),配合 viewLifecycleOwner 一并消失,
 *   旋转后需用户重新打开 sheet。够用,因为这是 first-install 场景。
 */
class MangaTranslatePrepSheet : V3BottomSheetBase() {

    override val maxHeightFraction: Float = 0.92F

    private var _binding: DialogMangaTranslatePrepBinding? = null
    private val binding get() = _binding!!

    private var onReady: (() -> Unit)? = null
    private var downloadJob: Job? = null

    private val ctdModel = ComicTextDetectorModel.CTD_BASE
    private val ocrModel = MangaOcrModel.MANGA_OCR_BASE
    private var importController: ModelImportController? = null
    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        importController?.handlePickedUri(uri)
    }

    /** Activity 在 show 前注入:两模型都就绪后 sheet 自动 dismiss 并触发这条 callback。 */
    fun setOnReady(callback: () -> Unit) {
        onReady = callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogMangaTranslatePrepBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloadJob?.cancel()
        downloadJob = null
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        binding.prepSubtitle.text = getString(
            R.string.manga_translate_prep_subtitle, totalSizeLabel(),
        )

        // CTD row 静态文本
        binding.ctdName.text = getString(R.string.manga_translate_prep_model_ctd)
        binding.ctdSizeBadge.text = ctdModel.sizeLabel
        // OCR row 静态文本
        binding.ocrName.text = getString(R.string.manga_translate_prep_model_ocr)
        binding.ocrSizeBadge.text = ocrModel.sizeLabel

        renderInitialStatus(ctx)

        binding.btnPrimary.setOnClick { onPrimaryClick(ctx) }
        binding.btnSecondary.setOnClick { dismissAllowingStateLoss() }
        binding.ctdImportLink.setOnClick { showCopyOrImportDialog(isCtd = true) }
        binding.ocrImportLink.setOnClick { showCopyOrImportDialog(isCtd = false) }
    }

    /** 复制链接 / 导入已下载好的文件：CTD 与 OCR 各自独立入口，复用同一个对话框与导入流程。 */
    private fun showCopyOrImportDialog(isCtd: Boolean) {
        if (_binding == null) return
        val ctx = requireContext()
        val manager = if (isCtd) ComicTextDetectorModelManager else MangaOcrModelManager
        val model = if (isCtd) ctdModel else ocrModel
        importController = ModelImportController(this, importPicker, manager, model) { success ->
            if (_binding == null) return@ModelImportController
            if (success) {
                renderRowState(ctx, isCtd, RowState.Ready)
                binding.btnPrimary.text = if (bothReady(ctx)) {
                    getString(R.string.manga_translate_prep_cta_ready)
                } else {
                    getString(R.string.manga_translate_prep_cta)
                }
                Common.showToast(ctx.getString(R.string.model_download_import_success))
            } else {
                Common.showToast(ctx.getString(R.string.model_download_import_invalid))
            }
        }
        importController?.showCopyOrImportDialog()
    }

    /** 根据当前模型 ready 状态决定初始 CTA 文案。两个都 ready 直接亮「开始翻译」。 */
    private fun renderInitialStatus(ctx: Context) {
        renderRowState(ctx, isCtd = true,
            state = rowStateFor(ctx, ComicTextDetectorModelManager, ctdModel))
        renderRowState(ctx, isCtd = false,
            state = rowStateFor(ctx, MangaOcrModelManager, ocrModel))
        binding.btnPrimary.text = if (bothReady(ctx)) {
            getString(R.string.manga_translate_prep_cta_ready)
        } else {
            getString(R.string.manga_translate_prep_cta)
        }
    }

    private fun bothReady(ctx: Context): Boolean =
        ComicTextDetectorModelManager.isModelReady(ctx, ctdModel) &&
            MangaOcrModelManager.isModelReady(ctx, ocrModel)

    /**
     * CTA 三态:
     * - 都就绪 → 直接 fire onReady + dismiss
     * - 至少一个缺 → 顺序下完缺的那个 / 那几个,完成后 fire onReady + dismiss
     */
    private fun onPrimaryClick(ctx: Context) {
        if (downloadJob?.isActive == true) return

        if (bothReady(ctx)) {
            fireReadyAndDismiss()
            return
        }

        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.btnPrimary.isClickable = false
            // 灰一档表示「正在跑」,button_press_alpha 不覆盖 enabled 状态,得手动 dim
            binding.btnPrimary.alpha = 0.55F
            binding.btnPrimary.text = getString(R.string.manga_translate_prep_cta_running)

            // 顺序下:先小的 CTD(57MB)再 OCR(91MB),小的先就绪给用户视觉反馈
            val ctdOk = ensureModel(
                ctx,
                isCtd = true,
                manager = ComicTextDetectorModelManager,
                model = ctdModel,
            )
            if (!ctdOk) {
                onDownloadFailed()
                return@launch
            }
            val ocrOk = ensureModel(
                ctx,
                isCtd = false,
                manager = MangaOcrModelManager,
                model = ocrModel,
            )
            if (!ocrOk) {
                onDownloadFailed()
                return@launch
            }

            fireReadyAndDismiss()
        }
    }

    private fun onDownloadFailed() {
        if (_binding == null) return
        binding.btnPrimary.isClickable = true
        binding.btnPrimary.alpha = 1F
        binding.btnPrimary.text = getString(R.string.manga_translate_prep_cta_retry)
    }

    private fun fireReadyAndDismiss() {
        onReady?.invoke()
        dismissAllowingStateLoss()
    }

    /**
     * 如果 [model] 已就绪直接返回 true;否则起一次下载,过程中实时刷 row 的 status text +
     * linear progress + 右侧 spinner。
     */
    private suspend fun ensureModel(
        ctx: Context,
        isCtd: Boolean,
        manager: ModelDownloadManager,
        model: DownloadableModel,
    ): Boolean {
        if (manager.isModelReady(ctx, model)) {
            renderRowState(ctx, isCtd, RowState.Ready)
            return true
        }
        renderRowState(ctx, isCtd, RowState.Downloading(bytesRead = 0L, totalBytes = -1L))
        val ok = manager.downloadModel(ctx, model) { bytesRead, totalBytes ->
            // 回调在 IO 线程;binding 不要直接访问,post 到主线程
            view?.post {
                if (_binding == null) return@post
                renderRowState(ctx, isCtd, RowState.Downloading(bytesRead, totalBytes))
            }
        }
        if (_binding == null) return ok
        renderRowState(ctx, isCtd, if (ok) RowState.Ready else RowState.Failed)
        return ok
    }

    private sealed class RowState {
        object Pending : RowState()
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : RowState()
        object Ready : RowState()
        object Failed : RowState()
    }

    private fun rowStateFor(
        ctx: Context,
        manager: ModelDownloadManager,
        model: DownloadableModel,
    ): RowState =
        if (manager.isModelReady(ctx, model)) RowState.Ready else RowState.Pending

    /** 三件套(status text + linear progress + 右侧 spinner/icon)按 [state] 一次性刷。 */
    private fun renderRowState(ctx: Context, isCtd: Boolean, state: RowState) {
        val statusText: TextView
        val progress: LinearProgressIndicator
        val statusIcon: ImageView
        val spinner: CircularProgressIndicator
        val importLink: TextView
        val descRes: Int
        if (isCtd) {
            statusText = binding.ctdStatus
            progress = binding.ctdProgress
            statusIcon = binding.ctdStatusIcon
            spinner = binding.ctdStatusSpinner
            importLink = binding.ctdImportLink
            descRes = R.string.manga_translate_prep_model_ctd_desc
        } else {
            statusText = binding.ocrStatus
            progress = binding.ocrProgress
            statusIcon = binding.ocrStatusIcon
            spinner = binding.ocrStatusSpinner
            importLink = binding.ocrImportLink
            descRes = R.string.manga_translate_prep_model_ocr_desc
        }

        // 只有失败态才把 status 文案做成可点击的重试入口，其他状态一律清掉监听
        statusText.isClickable = false
        statusText.setOnClickListener(null)

        when (state) {
            RowState.Pending -> {
                statusText.text = getString(R.string.manga_translate_prep_status_pending, getString(descRes))
                statusText.setTextColor(ctx.getColor(R.color.v3_text_3))
                progress.visibility = View.GONE
                spinner.visibility = View.GONE
                // 待下载状态不画右侧 icon —— 之前那个下载箭头会让人误以为是「点这里下载这一个」,
                // 状态文案左侧已经说了「待下载」,右边留空,只有下载中 / 完成 / 失败才挂图标。
                statusIcon.visibility = View.GONE
                importLink.visibility = View.VISIBLE
            }
            is RowState.Downloading -> {
                val readMB = String.format("%.1f MB", state.bytesRead / 1_048_576.0)
                val totalMB = if (state.totalBytes > 0)
                    String.format("%.1f MB", state.totalBytes / 1_048_576.0)
                else "—"
                val percent = if (state.totalBytes > 0)
                    ((state.bytesRead * 100) / state.totalBytes).toInt().coerceIn(0, 100)
                else 0
                statusText.text = getString(
                    R.string.manga_translate_prep_status_downloading,
                    readMB, totalMB, "$percent%",
                )
                statusText.setTextColor(ctx.getColor(R.color.v3_text_2))
                progress.visibility = View.VISIBLE
                if (state.totalBytes > 0) {
                    progress.isIndeterminate = false
                    progress.setProgressCompat(percent, true)
                } else {
                    progress.isIndeterminate = true
                }
                spinner.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
                importLink.visibility = View.GONE
            }
            RowState.Ready -> {
                statusText.text = getString(R.string.manga_translate_prep_status_ready)
                statusText.setTextColor(palette.textAccent)
                progress.visibility = View.GONE
                spinner.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.ic_file_download_done_24dp)
                statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(palette.primary)
                importLink.visibility = View.GONE
            }
            RowState.Failed -> {
                statusText.text = getString(R.string.manga_translate_prep_status_failed)
                statusText.setTextColor(ctx.getColor(R.color.buttonTextRed))
                progress.visibility = View.GONE
                spinner.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.ic_error_black_24dp)
                statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ctx.getColor(R.color.buttonTextRed),
                )
                importLink.visibility = View.VISIBLE
                statusText.isClickable = true
                statusText.setOnClickListener { onPrimaryClick(ctx) }
            }
        }
    }

    /** "N MB" —— hero subtitle 用的合计大小,从 sizeLabel 解析数字相加。 */
    private fun totalSizeLabel(): String {
        val total = parseSizeMb(ctdModel.sizeLabel) + parseSizeMb(ocrModel.sizeLabel)
        return "${total}MB"
    }

    private fun parseSizeMb(label: String): Int =
        Regex("(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        const val TAG = "MangaTranslatePrepSheet"
    }
}
