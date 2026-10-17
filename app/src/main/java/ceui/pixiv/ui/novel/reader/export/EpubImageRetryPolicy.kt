package ceui.pixiv.ui.novel.reader.export

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import ceui.lisa.R
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogBuilder
import ceui.pixiv.witstudio.dialog.WitDialogView
import ceui.pixiv.witstudio.theme.WitDisplay
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.AppUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class FailedImageInfo(
    val key: String,
    val url: String,
    val error: Throwable?,
    val novelId: String? = null,
    val chapterTitle: String? = null,
    val token: ContentToken? = null,
) {
    fun formatForClipboard(): String = buildString {
        appendLine("图片 key: $key")
        appendLine("URL: $url")
        if (!novelId.isNullOrEmpty()) appendLine("小说 ID: $novelId")
        if (!chapterTitle.isNullOrEmpty()) appendLine("章节: $chapterTitle")
        if (error != null) {
            appendLine("错误: ${error.message ?: error::class.java.simpleName}")
        }
    }
}

sealed class RetryDecision {
    object Retry : RetryDecision()
    object Ignore : RetryDecision()
}

sealed class EpubImageRetryPolicy {
    class Foreground(val activity: Activity? = null) : EpubImageRetryPolicy()
    class Batch(val activity: Activity? = null) : EpubImageRetryPolicy()
    object Auto : EpubImageRetryPolicy()

    companion object {
        val Default: EpubImageRetryPolicy get() = Foreground()
    }
}

object RecentNovelExportTracker {
    @Volatile
    private var hasFailedImages: Boolean = false

    @JvmStatic
    fun markFailed() {
        hasFailedImages = true
    }

    @JvmStatic
    fun consumeFailed(): Boolean {
        val failed = hasFailedImages
        hasFailedImages = false
        return failed
    }

    @JvmStatic
    fun checkAndShowToast(activity: Activity) {
        if (consumeFailed()) {
            Common.showToast(activity.getString(R.string.toast_recent_novel_export_has_failed_images))
        }
    }
}

class PromptWithCheckboxDialogBuilder(context: Context) :
    WitDialogBuilder<PromptWithCheckboxDialogBuilder>(context) {

    private var mMessage: CharSequence? = null
    private var mCheckboxText: CharSequence? = null
    private var mCheckboxHint: CharSequence? = null
    private var checked: Boolean = false
    private var checkBoxView: AppCompatImageView? = null
    private var messageView: AppCompatTextView? = null

    fun setMessage(message: CharSequence?): PromptWithCheckboxDialogBuilder = apply {
        mMessage = message
        syncMessage()
    }
    fun setCheckboxText(text: CharSequence?): PromptWithCheckboxDialogBuilder = apply { mCheckboxText = text }
    fun setCheckboxHint(hint: CharSequence?): PromptWithCheckboxDialogBuilder = apply {
        mCheckboxHint = hint
        syncMessage()
    }
    fun setChecked(checked: Boolean): PromptWithCheckboxDialogBuilder = apply {
        this.checked = checked
        syncCheckBox()
    }

    val isChecked: Boolean get() = checked

    private fun syncCheckBox() {
        val view = checkBoxView ?: return
        view.setImageResource(
            if (checked) ceui.pixiv.witstudio.R.drawable.wit_ic_dialog_checkbox_on
            else ceui.pixiv.witstudio.R.drawable.wit_ic_dialog_checkbox_off
        )
        view.imageTintList = ColorStateList.valueOf(
            if (checked) palette.textAccent
            else ContextCompat.getColor(view.context, ceui.pixiv.witstudio.R.color.wit_text_3)
        )
        syncMessage()
    }

    private fun syncMessage() {
        val tv = messageView ?: return
        val base = mMessage ?: ""
        tv.text = if (checked && !mCheckboxHint.isNullOrEmpty()) {
            "$base\n\n$mCheckboxHint"
        } else {
            base
        }
    }

    override fun onCreateContent(
        dialog: WitDialog,
        parent: WitDialogView,
        context: Context,
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = WitDisplay.dp2px(context, 24)
            setPadding(
                padH,
                WitDisplay.dp2px(
                    context,
                    if (hasTitle()) 12 else 24,
                ),
                padH,
                WitDisplay.dp2px(context, 20),
            )
        }
        if (!mMessage.isNullOrEmpty()) {
            val tv = AppCompatTextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ContextCompat.getColor(context, ceui.pixiv.witstudio.R.color.wit_text_2))
                setLineSpacing(
                    WitDisplay.dp2pxF(context, 4f),
                    1f,
                )
            }
            messageView = tv
            syncMessage()
            container.addView(
                tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = WitDisplay.dp2px(context, 16)
                },
            )
        }
        if (!mCheckboxText.isNullOrEmpty()) {
            val checkRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
            }
            val size = WitDisplay.dp2px(context, 22)
            val box = AppCompatImageView(context)
            checkBoxView = box
            syncCheckBox()
            checkRow.addView(
                box,
                LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = WitDisplay.dp2px(context, 12)
                },
            )
            checkRow.addView(
                AppCompatTextView(context).apply {
                    text = mCheckboxText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(ContextCompat.getColor(context, ceui.pixiv.witstudio.R.color.wit_text_2))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            checkRow.setOnClickListener {
                checked = !checked
                syncCheckBox()
            }
            container.addView(checkRow)
        }
        return wrapWithScroll(container)
    }
}

object EpubRetryDialogHelper {

    suspend fun showForegroundRetryDialog(
        activity: Activity,
        failedImage: FailedImageInfo,
        attempt: Int,
    ): Pair<RetryDecision, Boolean> = suspendCancellableCoroutine { cont ->
        if (attempt == 1) {
            val builder = PromptWithCheckboxDialogBuilder(activity)
                .setTitle(activity.getString(R.string.epub_image_download_failed))
                .setMessage(activity.getString(R.string.epub_image_download_failed_desc))
                .setCheckboxText(activity.getString(R.string.epub_image_checkbox_dont_ask_again))
                .setCheckboxHint(activity.getString(R.string.epub_image_checkbox_hint_silent_retry))
                .setCancelable(true)
                .setCanceledOnTouchOutside(true)
            var actionTaken = false
            builder.addAction(activity.getString(R.string.epub_image_action_ignore)) { dialog, _ ->
                actionTaken = true
                val check = builder.isChecked
                dialog.dismiss()
                if (cont.isActive) cont.resume(Pair(RetryDecision.Ignore, check))
            }
            builder.addAction(activity.getString(R.string.epub_image_action_retry)) { dialog, _ ->
                actionTaken = true
                val check = builder.isChecked
                dialog.dismiss()
                if (cont.isActive) cont.resume(Pair(RetryDecision.Retry, check))
            }
            val dialog = builder.create()
            dialog.setOnDismissListener {
                if (!actionTaken && cont.isActive) {
                    cont.resume(Pair(RetryDecision.Ignore, builder.isChecked))
                }
            }
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        } else {
            val title = activity.getString(R.string.epub_image_retry_failed_title)
            val builder = WitDialog.MessageDialogBuilder(activity)
                .setTitle(title)
                .setCancelable(true)
                .setCanceledOnTouchOutside(true)
            var actionTaken = false
            if (attempt >= 3) {
                builder.setActionContainerOrientation(WitDialogBuilder.VERTICAL)
                builder.addAction(activity.getString(R.string.epub_image_action_retry_again)) { dialog, _ ->
                    actionTaken = true
                    dialog.dismiss()
                    if (cont.isActive) cont.resume(Pair(RetryDecision.Retry, false))
                }
                builder.addAction(activity.getString(R.string.epub_image_action_ignore)) { dialog, _ ->
                    actionTaken = true
                    dialog.dismiss()
                    if (cont.isActive) cont.resume(Pair(RetryDecision.Ignore, false))
                }
                builder.addAction(activity.getString(R.string.epub_image_action_copy_error)) { _, _ ->
                    ClipBoardUtils.putTextIntoClipboard(activity, failedImage.formatForClipboard(), false)
                    Common.showToast(activity.getString(R.string.epub_image_copied_to_clipboard))
                }
            } else {
                builder.addAction(activity.getString(R.string.epub_image_action_ignore)) { dialog, _ ->
                    actionTaken = true
                    dialog.dismiss()
                    if (cont.isActive) cont.resume(Pair(RetryDecision.Ignore, false))
                }
                builder.addAction(activity.getString(R.string.epub_image_action_retry_again)) { dialog, _ ->
                    actionTaken = true
                    dialog.dismiss()
                    if (cont.isActive) cont.resume(Pair(RetryDecision.Retry, false))
                }
            }
            val dialog = builder.create()
            dialog.setOnDismissListener {
                if (!actionTaken && cont.isActive) {
                    cont.resume(Pair(RetryDecision.Ignore, false))
                }
            }
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    suspend fun showBatchRetryDialog(
        activity: Activity,
        failedImages: List<FailedImageInfo>,
        attempt: Int,
    ): RetryDecision = suspendCancellableCoroutine { cont ->
        val title = if (attempt == 1) {
            activity.getString(R.string.epub_image_download_failed)
        } else {
            activity.getString(R.string.epub_image_retry_failed_title)
        }
        val msg = if (attempt == 1) {
            activity.getString(R.string.epub_image_download_failed_batch_desc, failedImages.size)
        } else {
            activity.getString(R.string.epub_image_retry_failed_batch_desc, failedImages.size)
        }
        val builder = WitDialog.MessageDialogBuilder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(true)
            .setCanceledOnTouchOutside(true)
        var actionTaken = false
        if (attempt >= 3) {
            builder.setActionContainerOrientation(WitDialogBuilder.VERTICAL)
            builder.addAction(activity.getString(R.string.epub_image_action_retry_all_again)) { dialog, _ ->
                actionTaken = true
                dialog.dismiss()
                if (cont.isActive) cont.resume(RetryDecision.Retry)
            }
            builder.addAction(activity.getString(R.string.epub_image_action_ignore_all)) { dialog, _ ->
                actionTaken = true
                dialog.dismiss()
                if (cont.isActive) cont.resume(RetryDecision.Ignore)
            }
            builder.addAction(activity.getString(R.string.epub_image_action_copy_error)) { _, _ ->
                val allInfo = failedImages.joinToString("\n---\n") { it.formatForClipboard() }
                ClipBoardUtils.putTextIntoClipboard(activity, allInfo, false)
                Common.showToast(activity.getString(R.string.epub_image_copied_to_clipboard))
            }
        } else {
            val ignoreText = activity.getString(R.string.epub_image_action_ignore_all)
            val retryText = if (attempt == 1) {
                activity.getString(R.string.epub_image_action_retry_all)
            } else {
                activity.getString(R.string.epub_image_action_retry_all_again)
            }
            builder.addAction(ignoreText) { dialog, _ ->
                actionTaken = true
                dialog.dismiss()
                if (cont.isActive) cont.resume(RetryDecision.Ignore)
            }
            builder.addAction(retryText) { dialog, _ ->
                actionTaken = true
                dialog.dismiss()
                if (cont.isActive) cont.resume(RetryDecision.Retry)
            }
        }
        val dialog = builder.create()
        dialog.setOnDismissListener {
            if (!actionTaken && cont.isActive) {
                cont.resume(RetryDecision.Ignore)
            }
        }
        cont.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }
}
