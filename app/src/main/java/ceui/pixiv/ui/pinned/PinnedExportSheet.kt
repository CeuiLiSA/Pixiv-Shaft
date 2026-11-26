package ceui.pixiv.ui.pinned

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.ui.library.LibraryFilterViews
import ceui.pixiv.witstudio.dialog.WitBottomSheet
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.pillButton
import ceui.pixiv.witstudio.theme.setTextWithIcon
import com.hjq.toast.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「我置顶的内容」列表导出面板（issue #1399），按 `docs/v3-design-philosophy.md` Sheet 配方构建：
 * 标题 → 说明 → 导出范围（多选胶囊，默认选中当前 Tab）→ 可选的「全部 / 当前搜索」范围切换 →
 * 格式（纯文本 / XML 连通按钮组）→ 实色主操作「保存文件」+ 浅色次操作「复制全部 / 分享」。
 */
internal object PinnedExportSheet {

    fun show(
        fragment: Fragment,
        viewModel: PinnedContentViewModel,
        currentTab: Int,
    ) {
        val context = fragment.requireContext()
        val allSnapshot = viewModel.exportSnapshot(filteredOnly = false)
        val filteredSnapshot = viewModel.exportSnapshot(filteredOnly = true)
        val hasActiveFilter = viewModel.isFiltering()

        if (allSnapshot.totalOf(PinnedExportCategory.entries.toSet()) == 0) {
            Common.showToast(R.string.pinned_export_empty)
            return
        }

        val initialCategory = when (currentTab) {
            PinnedContentViewModel.TAB_TAGS -> PinnedExportCategory.TAGS
            PinnedContentViewModel.TAB_USERS -> PinnedExportCategory.USERS
            PinnedContentViewModel.TAB_HISTORY -> PinnedExportCategory.HISTORY
            else -> PinnedExportCategory.TAGS
        }
        val hasFilteredMatches = hasActiveFilter &&
            filteredSnapshot.totalOf(PinnedExportCategory.entries.toSet()) > 0
        var filteredOnly = hasFilteredMatches
        val selectedCategories = LinkedHashSet<PinnedExportCategory>().apply {
            val base = if (filteredOnly) filteredSnapshot else allSnapshot
            if (base.countOf(initialCategory) > 0) {
                add(initialCategory)
            } else {
                PinnedExportCategory.entries.filter { base.countOf(it) > 0 }.forEach(::add)
                if (isEmpty()) add(initialCategory)
            }
        }
        var format = PinnedExportFormat.TEXT

        val parts = LibraryFilterViews(context)
        val sheet = WitBottomSheet(context)
        val owner = fragment.viewLifecycleOwner
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                sheet.dismiss()
            }
        }
        owner.lifecycle.addObserver(observer)
        sheet.setOnDismissListener {
            owner.lifecycle.removeObserver(observer)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(4), context.dp(20), context.dp(20))
        }

        content.addView(
            context.label(context.getString(R.string.pinned_export_title), 20f, 700).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            },
        )
        content.addView(
            context.label(
                context.getString(R.string.pinned_export_desc),
                13f,
                400,
                context.color(R.color.v3_text_2),
            ).apply {
                lineHeightRatio(1.5f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) },
        )

        // 1) 内容选择（多选胶囊：标签 / 作者 / 搜索历史）
        content.addView(
            context.label(context.getString(R.string.pinned_export_section_content), 14f, 600),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(18) },
        )
        val categoryFlow = parts.newFlow()
        val categoryChips = LinkedHashMap<PinnedExportCategory, TextView>()
        content.addView(
            categoryFlow,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) },
        )

        // 2) 若当前处于搜索筛选态，允许在「当前搜索结果 / 全部内容」间切换
        lateinit var renderState: () -> Unit
        if (hasActiveFilter) {
            content.addView(
                context.label(context.getString(R.string.pinned_export_section_scope), 14f, 600),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(16) },
            )
            lateinit var scopeGroup: LibraryFilterViews.ChoiceGroup
            scopeGroup = parts.connectedGroup(
                listOf(
                    context.getString(R.string.pinned_export_scope_filtered),
                    context.getString(R.string.pinned_export_scope_all),
                ),
            ) { index ->
                filteredOnly = index == 0
                scopeGroup.select(index)
                renderState()
            }
            scopeGroup.select(if (filteredOnly) 0 else 1)
            content.addView(
                scopeGroup.view,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) },
            )
        }

        // 3) 格式选择（纯文本 .txt / XML .xml 连通组）
        content.addView(
            context.label(context.getString(R.string.pinned_export_section_format), 14f, 600),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(16) },
        )
        lateinit var formatGroup: LibraryFilterViews.ChoiceGroup
        formatGroup = parts.connectedGroup(
            listOf(
                context.getString(R.string.pinned_export_format_txt),
                context.getString(R.string.pinned_export_format_xml),
            ),
        ) { index ->
            format = if (index == 0) PinnedExportFormat.TEXT else PinnedExportFormat.XML
            formatGroup.select(index)
        }
        formatGroup.select(0)
        content.addView(
            formatGroup.view,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) },
        )

        // 4) 主操作 + 两个次操作
        val saveButton = TextView(context).apply {
            gravity = Gravity.CENTER
        }
        parts.stylePrimaryAction(saveButton)
        content.addView(
            saveButton,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(20) },
        )

        val secondaryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        lateinit var copyButton: TextView
        lateinit var shareButton: TextView
        var busy = false

        fun activeSnapshot(): PinnedContentViewModel.ExportSnapshot =
            if (filteredOnly) filteredSnapshot else allSnapshot

        renderState = {
            val snap = activeSnapshot()
            PinnedExportCategory.entries.forEach { category ->
                val chip = categoryChips[category] ?: return@forEach
                val labelRes = categoryTitleRes(category)
                chip.text = parts.chipLabel(context.getString(labelRes), count = snap.countOf(category))
                parts.renderChip(chip, selected = category in selectedCategories)
            }
            val selectedCount = snap.totalOf(selectedCategories)
            val saveLabel = if (selectedCount > 0) {
                context.getString(R.string.pinned_export_save_count, selectedCount)
            } else {
                context.getString(R.string.pinned_export_save)
            }
            saveButton.setTextWithIcon(saveLabel, R.drawable.ic_v3_export_24)
            saveButton.isEnabled = !busy && selectedCount > 0
            saveButton.alpha = if (saveButton.isEnabled) 1f else 0.45f
            copyButton.isEnabled = !busy && selectedCount > 0
            copyButton.alpha = if (copyButton.isEnabled) 1f else 0.45f
            shareButton.isEnabled = !busy && selectedCount > 0
            shareButton.alpha = if (shareButton.isEnabled) 1f else 0.45f
        }

        PinnedExportCategory.entries.forEach { category ->
            val chip = parts.filterChip(
                text = context.getString(categoryTitleRes(category)),
                count = activeSnapshot().countOf(category),
                pillShape = true,
            ) {
                if (category in selectedCategories) {
                    if (selectedCategories.size == 1) {
                        Common.showToast(R.string.pinned_export_one_required)
                    } else {
                        selectedCategories.remove(category)
                        renderState()
                    }
                } else {
                    selectedCategories.add(category)
                    renderState()
                }
            }
            categoryChips[category] = chip
            categoryFlow.addView(chip)
        }

        fun buildExportLabels(): PinnedExportLabels = PinnedExportLabels(
            tagsHeading = context.getString(R.string.pinned_tags),
            usersHeading = context.getString(R.string.pinned_users),
            historyHeading = context.getString(R.string.string_87),
            typeLabel = { type -> context.getString(searchTypeLabelRes(type)) },
        )

        fun buildPayloadOrToast(): Pair<String, Int>? {
            val snap = activeSnapshot()
            val count = snap.totalOf(selectedCategories)
            if (count == 0) {
                Common.showToast(R.string.pinned_export_empty)
                return null
            }
            val text = PinnedExportFormatter.format(
                format = format,
                categories = selectedCategories.toSet(),
                tags = snap.tags,
                users = snap.users,
                history = snap.history,
                labels = buildExportLabels(),
            )
            return text to count
        }

        copyButton = context.pillButton(
            text = context.getString(R.string.pinned_export_copy),
            primary = false,
        ) {
            val (text, count) = buildPayloadOrToast() ?: return@pillButton
            if (ClipBoardUtils.setPrimaryClip(context, ClipData.newPlainText("pinned-export", text))) {
                sheet.dismiss()
                Toaster.showShort(context.getString(R.string.pinned_export_copied, count))
            } else {
                Toaster.showShort(R.string.msg_copy_failed)
            }
        }.apply {
            maxLines = 2
            setTextWithIcon(context.getString(R.string.pinned_export_copy), R.drawable.baseline_content_copy_24, sizeDp = 16, gapDp = 6)
        }

        shareButton = context.pillButton(
            text = context.getString(R.string.pinned_export_share),
            primary = false,
        ) {
            val (text, _) = buildPayloadOrToast() ?: return@pillButton
            val activeFormat = format
            busy = true
            renderState()
            sheet.lifecycleScope.launch {
                try {
                    val uri = withContext(Dispatchers.IO) {
                        writeShareTempFile(context.applicationContext, text, activeFormat)
                    }
                    sheet.dismiss()
                    shareExportFile(fragment, uri, activeFormat)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "分享导出文件失败")
                    Toaster.showLong(
                        context.getString(
                            R.string.pinned_export_save_failed,
                            t.message ?: t.javaClass.simpleName,
                        ),
                    )
                } finally {
                    busy = false
                    renderState()
                }
            }
        }.apply {
            maxLines = 2
            setTextWithIcon(context.getString(R.string.pinned_export_share), R.drawable.ic_share_black_24dp, sizeDp = 16, gapDp = 6)
        }

        saveButton.setOnClickListener {
            val (text, count) = buildPayloadOrToast() ?: return@setOnClickListener
            val activeFormat = format
            busy = true
            renderState()
            sheet.lifecycleScope.launch {
                try {
                    val saved = withContext(Dispatchers.IO) {
                        saveExportFile(text, activeFormat)
                    }
                    sheet.dismiss()
                    Toaster.showLong(
                        context.getString(R.string.pinned_export_saved, count, saved.path),
                    )
                    tryOpenSavedFile(fragment, saved.uri, activeFormat.mimeType)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "保存导出文件失败")
                    Toaster.showLong(
                        context.getString(
                            R.string.pinned_export_save_failed,
                            t.message ?: t.javaClass.simpleName,
                        ),
                    )
                } finally {
                    busy = false
                    renderState()
                }
            }
        }

        secondaryRow.addView(copyButton, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = context.dp(10)
        })
        secondaryRow.addView(shareButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(
            secondaryRow,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) },
        )

        renderState()

        sheet.setSheetContent(
            NestedScrollView(context).apply {
                isFillViewport = true
                addView(content)
            },
        )
        sheet.show()
    }

    private fun categoryTitleRes(category: PinnedExportCategory): Int = when (category) {
        PinnedExportCategory.TAGS -> R.string.v3_label_tags
        PinnedExportCategory.USERS -> R.string.string_432
        PinnedExportCategory.HISTORY -> R.string.string_87
    }

    private data class SaveResult(val uri: Uri, val path: String)

    private fun saveExportFile(text: String, format: PinnedExportFormat): SaveResult {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val rawPath = RelativePath.parse("ShaftFiles/pixiv-pinned-$ts.${format.extension}")
        val handle = DownloadsRegistry.downloads.openRaw(
            Bucket.Log,
            rawPath,
            format.mimeType,
        ) ?: error("openRaw returned null")
        try {
            handle.stream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(text) }
            }
            handle.onFinish()
        } catch (e: Exception) {
            runCatching { handle.onAbort() }
            throw e
        }
        return SaveResult(handle.uri, rawPath.joinTo())
    }

    private fun writeShareTempFile(
        appContext: Context,
        text: String,
        format: PinnedExportFormat,
    ): Uri {
        val dir = File(appContext.cacheDir, "pinned-export").apply {
            if (!exists()) mkdirs()
            // 清理上一次临时导出文件，避免缓存目录持续增长。
            listFiles()?.forEach { runCatching { it.delete() } }
        }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "pixiv-pinned-$ts.${format.extension}")
        OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8).use { it.write(text) }
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.provider", out)
    }

    private fun shareExportFile(fragment: Fragment, uri: Uri, format: PinnedExportFormat) {
        val ctx = fragment.context ?: return
        val title = ctx.getString(R.string.pinned_export_title)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            fragment.startActivity(Intent.createChooser(send, title))
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "share export file failed")
        }
    }

    private fun tryOpenSavedFile(fragment: Fragment, uri: Uri, mimeType: String) {
        if (uri.scheme != "content") return
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            fragment.startActivity(Intent.createChooser(view, null))
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "open saved export failed (uri=%s)", uri)
        }
    }

    private const val TAG = "PinnedExport"
}
