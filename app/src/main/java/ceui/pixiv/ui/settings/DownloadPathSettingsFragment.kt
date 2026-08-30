package ceui.pixiv.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentDownloadPathSettingsBinding
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.ConfigPresets
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.template.DefaultTemplates
import ceui.pixiv.download.template.TemplateSamples
import ceui.pixiv.download.template.TemplateValidator
import ceui.pixiv.ui.common.viewBinding
import com.hjq.toast.Toaster

/**
 * Download path / filename settings, styled to the V3 design language used by
 * [ceui.pixiv.ui.detail.ArtworkV3Fragment] and [ceui.pixiv.ui.user.MineProfileFragment]:
 * soft rounded 28dp cards on an off-white background, hairline borders, pill
 * chips and pills for interactive controls, Montserrat bold titles.
 *
 * Layout roles:
 *   - Preset section (4 cards, each shows a sample rendered path + "套用" pill)
 *   - Teaching card (intro + tappable variable / condition chips + ready-made examples)
 *   - Per-bucket card (storage + policy chips, template editor, live preview,
 *     save / reset pills)
 *   - Reset-all pill at the bottom
 */
class DownloadPathSettingsFragment : Fragment(R.layout.fragment_download_path_settings) {

    private val binding by viewBinding(FragmentDownloadPathSettingsBinding::bind)

    /** Currently focused template EditText — target for variable / condition chips. */
    private var focusedEditor: EditText? = null

    /** 「把已下载文件重命名成当前格式」流程（issue #567）。 */
    private val renameFlow by lazy(LazyThreadSafetyMode.NONE) { RenameDownloadedFilesFlow(this) }

    /**
     * One entry per bucket card, re-running that card's live preview. Lets the
     * page-padding switch refresh every preview in place instead of calling
     * [render], which would rebuild the editors and discard unsaved edits.
     */
    private val previewRefreshers = mutableListOf<() -> Unit>()

    private val USER_BUCKETS = listOf(
        Bucket.Illust to R.string.download_path_bucket_illust,
        Bucket.Ugoira to R.string.download_path_bucket_ugoira,
        Bucket.Novel  to R.string.download_path_bucket_novel,
        // 合并下载的合集单独一张卡：它的目录 / 文件名和单篇小说互不牵连（issue #964）。
        Bucket.NovelSeries to R.string.download_path_bucket_novel_series,
        // 插画/漫画简介 txt 单独一张卡：默认目录不与图片混放。
        Bucket.Caption to R.string.download_path_bucket_caption,
        Bucket.Backup to R.string.download_path_bucket_backup,
        Bucket.Log    to R.string.download_path_bucket_log,
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 全 app 统一的 toolbar_layout(同设置页 / 榜单页):品牌色 + 白字,标题 / 返回在这里接线。
        binding.toolbarLayout.toolbarTitle.setText(R.string.download_path_title)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        render()
    }

    override fun onDestroyView() {
        // Refreshers close over the bucket cards; without this they would keep
        // a dead view hierarchy alive until the next render().
        previewRefreshers.clear()
        focusedEditor = null
        super.onDestroyView()
    }

    private fun render() {
        val root = binding.contentRoot
        root.removeAllViews()
        focusedEditor = null
        previewRefreshers.clear()

        addSectionTitle(root, getString(R.string.download_path_presets_title), topMarginDp = 2)
        addPresetCards(root)

        addSectionTitle(root, getString(R.string.download_path_teach_section_intro), topMarginDp = 18)
        addTeachingCard(root)

        addSectionTitle(root, getString(R.string.download_path_pad_page_title), topMarginDp = 18)
        addPadPageCard(root)

        addSectionTitle(root, getString(R.string.rename_dl_title), topMarginDp = 18)
        addRenameCard(root)

        USER_BUCKETS.forEach { (bucket, labelRes) ->
            addSectionTitle(root, getString(labelRes), topMarginDp = 18)
            addBucketSection(root, bucket)
        }

        addResetAll(root)
    }

    // ---------------- Presets ----------------

    private data class PresetInfo(
        val id: ConfigPresets.Id,
        val labelRes: Int,
        /** 预览行展示哪个 bucket 的模板；默认插画（对大多数预设最有信息量）。 */
        val previewBucket: Bucket = Bucket.Illust,
    )

    private val PRESETS = listOf(
        PresetInfo(ConfigPresets.Id.ShaftClassic,    R.string.download_path_preset_classic),
        PresetInfo(ConfigPresets.Id.Modern,          R.string.download_path_preset_modern),
        PresetInfo(ConfigPresets.Id.Flat,            R.string.download_path_preset_flat),
        PresetInfo(ConfigPresets.Id.ByDate,          R.string.download_path_preset_by_date),
        PresetInfo(ConfigPresets.Id.ByAuthor,        R.string.download_path_preset_by_author),
        PresetInfo(ConfigPresets.Id.ByAuthorAndDate, R.string.download_path_preset_by_author_and_date),
        PresetInfo(ConfigPresets.Id.ByAuthorMultiPageGroup, R.string.download_path_preset_by_author_multi_page_group),
        // 卖点在小说 bucket，预览也展示小说路径——展示插画路径的话和 ByAuthor 看不出区别。
        PresetInfo(ConfigPresets.Id.BySeries,        R.string.download_path_preset_by_series, previewBucket = Bucket.Novel),
        PresetInfo(ConfigPresets.Id.Minimal,         R.string.download_path_preset_minimal),
        PresetInfo(ConfigPresets.Id.RFilter,         R.string.download_path_preset_r_filter),
        PresetInfo(ConfigPresets.Id.Detailed,        R.string.download_path_preset_detailed),
    )

    private fun addPresetCards(root: LinearLayout) {
        val inflater = LayoutInflater.from(requireContext())
        PRESETS.forEach { preset ->
            val cell = inflater.inflate(R.layout.cell_download_preset, root, false)
            (cell.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = dp(6); bottomMargin = dp(6)
            }.also { cell.layoutParams = it }

            cell.findViewById<TextView>(R.id.preset_name).text = getString(preset.labelRes)
            val previewView = cell.findViewById<TextView>(R.id.preset_preview)
            previewView.text = previewFor(preset)

            val onApply = View.OnClickListener { applyPreset(preset.id) }
            cell.findViewById<TextView>(R.id.preset_apply).setOnClickListener(onApply)
            cell.setOnClickListener(onApply)

            root.addView(cell)
        }
    }

    /**
     * Produces a 1-line illustration of what the preset would produce for its
     * [PresetInfo.previewBucket] — Illust for most presets, Novel for the
     * novel-focused ones.
     */
    private fun previewFor(preset: PresetInfo): CharSequence {
        val config = ConfigPresets.of(preset.id, placeholderImages(), placeholderDownloads())
        val template = config.resolve(preset.previewBucket).template
        return getString(R.string.download_path_preset_preview_prefix) + " " +
            when (val r = TemplateSamples.preview(template, preset.previewBucket)) {
                is TemplateSamples.Preview.Ok      -> r.cleaned.joinTo()
                is TemplateSamples.Preview.Failure -> "⚠"
            }
    }

    private fun placeholderImages(): StorageChoice =
        StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)

    private fun placeholderDownloads(): StorageChoice =
        StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)

    private fun applyPreset(id: ConfigPresets.Id) {
        val current = DownloadsRegistry.store.loadOrFallback()
        val images = current.defaults.storage
        val downloads = current.perBucket[Bucket.Novel]?.storage ?: images
        // Presets define paths only — carry the user's numbering preferences over.
        val next = ConfigPresets.of(id, images, downloads).copy(
            pageIndexFrom1 = current.pageIndexFrom1,
            padPageNumber = current.padPageNumber,
        )
        DownloadsRegistry.store.save(next)
        DownloadsRegistry.invalidateBackends()
        Toaster.show(getString(R.string.download_path_preset_applied))
        render()
        if (renameableTemplatesChanged(current, next)) renameFlow.promptAfterTemplateChange()
    }

    /**
     * 插画 / 动图 bucket 的模板是否变了 —— 只有这两个 bucket 的文件在下载记录表里,
     * 批量重命名（issue #567）只对它们有意义;小说 / 备份 / 日志的模板变动不触发追问。
     */
    private fun renameableTemplatesChanged(before: DownloadConfig, after: DownloadConfig): Boolean {
        return listOf(Bucket.Illust, Bucket.Ugoira).any { bucket ->
            before.resolve(bucket).template != after.resolve(bucket).template
        }
    }

    // ---------------- Teaching card ----------------

    private data class Token(val insert: String, val chipLabel: String, val explainRes: Int)

    private val VARIABLE_TOKENS = listOf(
        Token("{id}", "{id}", R.string.download_path_teach_var_id),
        Token("{title}", "{title}", R.string.download_path_teach_var_title),
        Token("{page}", "{page}", R.string.download_path_teach_var_page),
        Token("{pages}", "{pages}", R.string.download_path_teach_var_pages),
        Token("{ext}", "{ext}", R.string.download_path_teach_var_ext),
        Token("{author}", "{author}", R.string.download_path_teach_var_author),
        Token("{author_id}", "{author_id}", R.string.download_path_teach_var_author_id),
        Token("{w}", "{w}", R.string.download_path_teach_var_w),
        Token("{h}", "{h}", R.string.download_path_teach_var_h),
        Token("{created:yyyyMMdd_HHmmss}", "{created:…}", R.string.download_path_teach_var_created),
        Token("{series}", "{series}", R.string.download_path_teach_var_series),
        Token("{series_order}", "{series_order}", R.string.download_path_teach_var_series_order),
        Token("{chapters}", "{chapters}", R.string.download_path_teach_var_chapters),
    )

    private val CONDITION_TOKENS = listOf(
        Token("[?R18:R18/]", "[?R18:R18/]", R.string.download_path_teach_cond_r18),
        Token("[?AI:AI/]", "[?AI:AI/]", R.string.download_path_teach_cond_ai),
        Token("[?p>1: p{page}]", "[?p>1:…]", R.string.download_path_teach_cond_multipage),
        Token("[?!p>1:]", "[?!p>1:…]", R.string.download_path_teach_cond_singlepage),
        Token("[?!R18:safe/]", "[?!R18:safe/]", R.string.download_path_teach_cond_not_r18),
        Token("[?series:{series}/]", "[?series:…]", R.string.download_path_teach_cond_series),
    )

    private data class Example(val template: String, val labelRes: Int)

    private val EXAMPLES = listOf(
        Example(DefaultTemplates.ILLUST, R.string.download_path_teach_example_classic_label),
        Example("Shaft/{title} {id}[?p>1: p{page}].{ext}", R.string.download_path_teach_example_flat_label),
        Example("Shaft/{author} ({author_id})/{title} {id}[?p>1: p{page}].{ext}", R.string.download_path_teach_example_by_author_label),
        Example("Shaft/{author} ({author_id})/[?p>1:{title} {id}/]{title} {id}[?p>1: p{page}].{ext}", R.string.download_path_teach_example_multi_page_group_label),
        Example("Shaft/{created:yyyy}/{created:yyyy-MM}/{title} {id}[?p>1: p{page}].{ext}", R.string.download_path_teach_example_date_label),
        Example("Shaft/[?R18:R18/][?AI:AI/]{author} ({author_id})/{title} {id}[?p>1: p{page}].{ext}", R.string.download_path_teach_example_r18_label),
        Example("Shaft/Novels/{author}/[?series:{series}/{series_order} ]{title} {id}.txt", R.string.download_path_teach_example_novel_series_label),
        Example("Shaft/Novels/[?series:{series}/{series_order} ]{title} {id}.txt", R.string.download_path_teach_example_novel_series_flat_label),
        // 合并下载专用：合集不进系列子目录，直接落作者目录（issue #964 的诉求）。
        Example("Shaft/Novels/{author} ({author_id})/{series} 合集 1~{chapters} {id}.{ext}", R.string.download_path_teach_example_novel_merge_label),
    )

    private fun addTeachingCard(root: LinearLayout) {
        val card = cardContainer()
        card.addView(bodyText(getString(R.string.download_path_teach_intro_body)))

        card.addView(subHeader(getString(R.string.download_path_teach_section_vars), topDp = 16))
        card.addView(chipScrollRow(VARIABLE_TOKENS))
        card.addView(explainList(VARIABLE_TOKENS))

        card.addView(subHeader(getString(R.string.download_path_teach_section_cond), topDp = 14))
        card.addView(chipScrollRow(CONDITION_TOKENS))
        card.addView(explainList(CONDITION_TOKENS))

        card.addView(subHeader(getString(R.string.download_path_teach_section_examples), topDp = 14))
        card.addView(exampleScrollRow())

        root.addView(card)
    }

    private fun chipScrollRow(tokens: List<Token>): View {
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        tokens.forEach { token ->
            val chip = TextView(requireContext()).apply {
                text = token.chipLabel
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(resources.getColor(R.color.v3_text_1, null))
                setBackgroundResource(R.drawable.bg_v3_chip)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, dp(6), 0) }
                setOnClickListener { insertIntoFocused(token.insert) }
            }
            row.addView(chip)
        }
        scroll.addView(row)
        return scroll
    }

    private fun explainList(tokens: List<Token>): View {
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        tokens.forEach { token ->
            val line = TextView(requireContext()).apply {
                textSize = 12f
                text = "${token.chipLabel}  —  ${getString(token.explainRes)}"
                setTextColor(resources.getColor(R.color.v3_text_3, null))
                setPadding(dp(2), dp(2), 0, dp(2))
            }
            col.addView(line)
        }
        return col
    }

    private fun exampleScrollRow(): View {
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        EXAMPLES.forEach { example ->
            val btn = TextView(requireContext()).apply {
                text = getString(example.labelRes)
                textSize = 13f
                setTextColor(0xFF6C5CE7.toInt())
                setBackgroundResource(R.drawable.bg_v3_pill_secondary)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, dp(6), 0) }
                setOnClickListener { replaceFocused(example.template) }
            }
            row.addView(btn)
        }
        scroll.addView(row)
        return scroll
    }

    private fun insertIntoFocused(snippet: String) {
        val editor = focusedEditor
        if (editor == null) {
            Toaster.show(getString(R.string.download_path_teach_tip_focus))
            return
        }
        val editable: Editable = editor.editableText
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(start)
        editable.replace(start, end, snippet)
        editor.setSelection(start + snippet.length)
    }

    private fun replaceFocused(template: String) {
        val editor = focusedEditor
        if (editor == null) {
            Toaster.show(getString(R.string.download_path_teach_tip_focus))
            return
        }
        editor.setText(template)
        editor.setSelection(template.length)
    }

    // ---------------- Page-number padding switch (#721) ----------------

    /**
     * Sample filenames for the on / off comparison. Deliberately literal rather
     * than rendered from the user's template: the point being made is about
     * *sort order*, which only shows up across several files at once, and these
     * are listed in the order a gallery app would actually display them.
     */
    private val PAD_OFF_SAMPLE = listOf("夏日祭_123456789_p1.jpg", "夏日祭_123456789_p10.jpg", "夏日祭_123456789_p2.jpg")
    private val PAD_ON_SAMPLE = listOf("夏日祭_123456789_p01.jpg", "夏日祭_123456789_p02.jpg", "夏日祭_123456789_p10.jpg")

    private fun addPadPageCard(root: LinearLayout) {
        val card = cardContainer()

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(requireContext()).apply {
                text = getString(R.string.download_path_pad_page_switch_label)
                textSize = 14f
                setTextColor(resources.getColor(R.color.v3_text_1, null))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val toggle = TextView(requireContext()).apply {
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(20), dp(9), dp(20), dp(9))
            isClickable = true
            isFocusable = true
        }
        header.addView(toggle)
        card.addView(header)

        fun paint(on: Boolean) {
            toggle.text = getString(
                if (on) R.string.download_path_pad_page_on else R.string.download_path_pad_page_off,
            )
            toggle.setBackgroundResource(
                if (on) R.drawable.bg_v3_pill_primary else R.drawable.bg_v3_pill_secondary,
            )
            toggle.setTextColor(if (on) resources.getColor(R.color.white, null) else 0xFF6C5CE7.toInt())
        }
        paint(DownloadsRegistry.store.loadOrFallback().padPageNumber)

        toggle.setOnClickListener {
            val next = DownloadsRegistry.store
                .update { it.copy(padPageNumber = !it.padPageNumber) }
                .padPageNumber
            paint(next)
            // Previews below render through the store, so they only pick this up
            // once explicitly refreshed.
            previewRefreshers.forEach { refresh -> refresh() }
            Toaster.show(
                getString(
                    if (next) R.string.download_path_pad_page_toast_on
                    else R.string.download_path_pad_page_toast_off,
                ),
            )
        }

        card.addView(
            bodyText(getString(R.string.download_path_pad_page_body)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) }
            },
        )

        card.addView(subHeader(getString(R.string.download_path_pad_page_example_off), topDp = 14))
        card.addView(sampleBlock(PAD_OFF_SAMPLE))
        card.addView(subHeader(getString(R.string.download_path_pad_page_example_on), topDp = 12))
        card.addView(sampleBlock(PAD_ON_SAMPLE))

        card.addView(
            TextView(requireContext()).apply {
                text = getString(R.string.download_path_pad_page_hint)
                textSize = 12f
                setTextColor(resources.getColor(R.color.v3_text_3, null))
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(14) }
            },
        )

        root.addView(card)
    }

    private fun sampleBlock(lines: List<String>): View = TextView(requireContext()).apply {
        text = lines.joinToString("\n")
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextColor(resources.getColor(R.color.v3_text_2, null))
        setBackgroundResource(R.drawable.bg_v3_chip)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setLineSpacing(0f, 1.25f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    // ---------------- Rename existing downloads (#567) ----------------

    private fun addRenameCard(root: LinearLayout) {
        val card = cardContainer()
        card.addView(bodyText(getString(R.string.rename_dl_card_body)))
        val btn = TextView(requireContext()).apply {
            text = getString(R.string.rename_dl_entry)
            setTextColor(0xFF6C5CE7.toInt())
            setBackgroundResource(R.drawable.bg_v3_pill_secondary)
            gravity = android.view.Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(24), dp(11), dp(24), dp(11))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
            setOnClickListener { renameFlow.start() }
        }
        card.addView(btn)
        root.addView(card)
    }

    // ---------------- Per-bucket card ----------------

    /**
     * 「点一下直接套用」的现成模板，挂在具体某张 bucket 卡片上。目前只有合并下载
     * 这张需要：它的模板最不好手写（`{chapters}` / `{series}` 都是合集专属），而
     * 教学卡里的范例按钮要先点中输入框才认，用户嫌绕（issue #964）。
     */
    private data class QuickTemplate(val labelRes: Int, val template: String)

    private val QUICK_TEMPLATES: Map<Bucket, List<QuickTemplate>> = mapOf(
        Bucket.NovelSeries to listOf(
            QuickTemplate(
                R.string.download_path_quick_merge_default,
                DefaultTemplates.NOVEL_SERIES,
            ),
            QuickTemplate(
                R.string.download_path_quick_merge_by_author,
                "Shaft/Novels/{author} ({author_id})/{series} 合集 1~{chapters} {id}.{ext}",
            ),
            QuickTemplate(
                R.string.download_path_quick_merge_by_series,
                "Shaft/Novels/{series}/{series} 合集 1~{chapters} {id}.{ext}",
            ),
            QuickTemplate(
                R.string.download_path_quick_merge_author_date,
                "Shaft/Novels/{author} ({author_id})/{created:yyyy-MM}/{series} 合集 1~{chapters} {id}.{ext}",
            ),
        ),
    )

    /**
     * 标题 + 一排 chip。点 chip = 填进 [editor] 并立即保存（和顶部预设卡「套用」
     * 一样是一步到位），预览由 editor 的 TextWatcher 自己刷新。
     */
    private fun quickTemplateRow(
        quick: List<QuickTemplate>,
        editor: EditText,
        bucket: Bucket,
    ): View {
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }
        col.addView(
            TextView(requireContext()).apply {
                text = getString(R.string.download_path_quick_template_label)
                textSize = 13f
                setTextColor(resources.getColor(R.color.v3_text_3, null))
            },
        )
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        quick.forEach { item ->
            row.addView(
                TextView(requireContext()).apply {
                    text = getString(item.labelRes)
                    textSize = 13f
                    setTextColor(0xFF6C5CE7.toInt())
                    setBackgroundResource(R.drawable.bg_v3_pill_secondary)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(0, 0, dp(6), 0) }
                    setOnClickListener {
                        editor.setText(item.template)
                        editor.setSelection(item.template.length)
                        saveBucketTemplate(bucket, item.template)
                    }
                },
            )
        }
        scroll.addView(row)
        col.addView(scroll)
        return col
    }

    private fun addBucketSection(root: LinearLayout, bucket: Bucket) {
        val inflater = LayoutInflater.from(requireContext())
        val cell = inflater.inflate(R.layout.cell_download_bucket, root, false) as LinearLayout
        (cell.layoutParams as LinearLayout.LayoutParams).apply {
            bottomMargin = dp(4)
        }.also { cell.layoutParams = it }

        val config = DownloadsRegistry.store.loadOrFallback()
        val resolved = config.resolve(bucket)
        val bucketLabel = USER_BUCKETS.first { it.first == bucket }.second

        cell.findViewById<TextView>(R.id.bucket_name).text = getString(bucketLabel)

        cell.findViewById<TextView>(R.id.bucket_storage).text = storageLabel(resolved.storage)

        val policyView = cell.findViewById<TextView>(R.id.bucket_policy)
        // 备份桶固定使用「已存在则重命名」（复用现有文案），与 DownloadConfig.resolve 的强制策略保持一致。
        policyView.text = if (bucket == Bucket.Backup) {
            getString(R.string.download_path_policy_rename)
        } else {
            policyLabel(resolved.overwrite)
        }

        val templateEdit = cell.findViewById<EditText>(R.id.bucket_template)
        val previewView = cell.findViewById<TextView>(R.id.bucket_preview)

        templateEdit.setText(resolved.template)
        refreshPreview(templateEdit.text.toString(), bucket, previewView)
        templateEdit.addTextChangedListener {
            refreshPreview(it?.toString().orEmpty(), bucket, previewView)
        }
        previewRefreshers += { refreshPreview(templateEdit.text.toString(), bucket, previewView) }
        templateEdit.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) focusedEditor = v as EditText
        }

        // 快捷模板排在这张卡自己的输入框正下方（不是页面顶部那堆预设 / 教学 chip
        // 里），紧挨着它要改的那个框，也不跟别的桶抢位置。
        QUICK_TEMPLATES[bucket]?.let { quick ->
            cell.addView(
                quickTemplateRow(quick, templateEdit, bucket),
                cell.indexOfChild(templateEdit) + 1,
            )
        }

        cell.findViewById<TextView>(R.id.bucket_save).setOnClickListener {
            saveBucketTemplate(bucket, templateEdit.text.toString())
        }
        cell.findViewById<TextView>(R.id.bucket_reset).setOnClickListener {
            val defaultSrc = DefaultTemplates.SOURCES.getValue(bucket)
            templateEdit.setText(defaultSrc)
            saveBucketTemplate(bucket, defaultSrc)
        }
        root.addView(cell)
    }

    private fun saveBucketTemplate(bucket: Bucket, source: String) {
        val result = TemplateValidator.validate(source, bucket)
        if (!result.ok) {
            Toaster.show(
                getString(R.string.download_path_invalid_template) +
                    "\n" + result.errors.joinToString("\n") { it.message },
            )
            return
        }
        val before = DownloadsRegistry.store.loadOrFallback()
        val after = DownloadsRegistry.store.update { cfg ->
            val existing = cfg.perBucket[bucket] ?: BucketConfig()
            cfg.withBucket(bucket, existing.copy(template = source))
        }
        Toaster.show(getString(R.string.download_path_saved))
        if (renameableTemplatesChanged(before, after)) renameFlow.promptAfterTemplateChange()
    }

    private fun refreshPreview(source: String, bucket: Bucket, out: TextView) {
        out.text = when (val r = TemplateSamples.preview(source, bucket)) {
            is TemplateSamples.Preview.Ok      -> r.cleaned.joinTo()
            is TemplateSamples.Preview.Failure -> "⚠ ${r.message}"
        }
    }

    // ---------------- Reset all ----------------

    private fun addResetAll(root: LinearLayout) {
        val btn = TextView(requireContext()).apply {
            text = getString(R.string.download_path_btn_reset_all)
            setTextColor(0xFF6C5CE7.toInt())
            setBackgroundResource(R.drawable.bg_v3_pill_secondary)
            gravity = android.view.Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(28), dp(14), dp(28), dp(14))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(22); gravity = android.view.Gravity.CENTER_HORIZONTAL }
            setOnClickListener {
                val current = DownloadsRegistry.store.loadOrFallback()
                // Reset clears *templates*; the numbering preferences are a
                // separate user choice and survive it.
                val cleared = DownloadConfig(
                    defaults = current.defaults,
                    perBucket = emptyMap(),
                    wifiOnly = current.wifiOnly,
                    pageIndexFrom1 = current.pageIndexFrom1,
                    padPageNumber = current.padPageNumber,
                )
                DownloadsRegistry.store.save(cleared)
                DownloadsRegistry.invalidateBackends()
                Toaster.show(getString(R.string.download_path_reset_done))
                render()
                if (renameableTemplatesChanged(current, cleared)) renameFlow.promptAfterTemplateChange()
            }
        }
        root.addView(btn)
    }

    // ---------------- Helpers ----------------

    private fun addSectionTitle(root: LinearLayout, text: String, topMarginDp: Int) {
        val title = TextView(requireContext()).apply {
            this.text = text
            setTextAppearance(R.style.textMontserratBold)
            textSize = 15f
            setTextColor(resources.getColor(R.color.v3_text_1, null))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topMarginDp); bottomMargin = dp(10); leftMargin = dp(4) }
            letterSpacing = 0.02f
            isAllCaps = false
        }
        root.addView(title)
    }

    private fun cardContainer(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_v3_card)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
    }

    private fun bodyText(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(resources.getColor(R.color.v3_text_2, null))
        setLineSpacing(0f, 1.35f)
    }

    private fun subHeader(text: String, topDp: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(resources.getColor(R.color.v3_text_1, null))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(topDp), 0, dp(6))
    }

    private fun storageLabel(choice: StorageChoice): String = when (choice) {
        is StorageChoice.MediaStore -> when (choice.collection) {
            StorageChoice.MediaStore.Collection.Images    -> getString(R.string.download_path_storage_mediastore_images)
            StorageChoice.MediaStore.Collection.Downloads -> getString(R.string.download_path_storage_mediastore_downloads)
        }
        is StorageChoice.Saf        -> getString(R.string.download_path_storage_saf)
        StorageChoice.AppCache      -> getString(R.string.download_path_storage_app_cache)
    }

    private fun policyLabel(p: OverwritePolicy): String = when (p) {
        OverwritePolicy.Skip    -> getString(R.string.download_path_policy_skip)
        OverwritePolicy.Replace -> getString(R.string.download_path_policy_replace)
        OverwritePolicy.Rename  -> getString(R.string.download_path_policy_rename)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
