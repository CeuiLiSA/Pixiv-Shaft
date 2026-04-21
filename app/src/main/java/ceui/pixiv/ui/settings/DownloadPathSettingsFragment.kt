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
import com.hjq.toast.ToastUtils

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

    private val USER_BUCKETS = listOf(
        Bucket.Illust to R.string.download_path_bucket_illust,
        Bucket.Ugoira to R.string.download_path_bucket_ugoira,
        Bucket.Novel  to R.string.download_path_bucket_novel,
        Bucket.Backup to R.string.download_path_bucket_backup,
        Bucket.Log    to R.string.download_path_bucket_log,
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviTitle.text = getString(R.string.download_path_title)
        binding.toolbarLayout.naviBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        render()
    }

    private fun render() {
        val root = binding.contentRoot
        root.removeAllViews()
        focusedEditor = null

        addSectionTitle(root, getString(R.string.download_path_presets_title), topMarginDp = 2)
        addPresetCards(root)

        addSectionTitle(root, getString(R.string.download_path_teach_section_intro), topMarginDp = 18)
        addTeachingCard(root)

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
    )

    private val PRESETS = listOf(
        PresetInfo(ConfigPresets.Id.ShaftClassic, R.string.download_path_preset_classic),
        PresetInfo(ConfigPresets.Id.Flat,         R.string.download_path_preset_flat),
        PresetInfo(ConfigPresets.Id.ByDate,       R.string.download_path_preset_by_date),
        PresetInfo(ConfigPresets.Id.ByAuthor,     R.string.download_path_preset_by_author),
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
     * Produces a 1-line illustration of what the preset would produce for the
     * Illust bucket — the most visually informative sample for users scanning
     * presets.
     */
    private fun previewFor(preset: PresetInfo): CharSequence {
        val config = ConfigPresets.of(preset.id, placeholderImages(), placeholderDownloads())
        val template = config.resolve(Bucket.Illust).template
        return getString(R.string.download_path_preset_preview_prefix) + " " +
            when (val r = TemplateSamples.preview(template, Bucket.Illust)) {
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
        val next = ConfigPresets.of(id, images, downloads)
        DownloadsRegistry.store.save(next)
        DownloadsRegistry.invalidateBackends()
        ToastUtils.show(getString(R.string.download_path_preset_applied))
        render()
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
    )

    private val CONDITION_TOKENS = listOf(
        Token("[?R18:R18/]", "[?R18:R18/]", R.string.download_path_teach_cond_r18),
        Token("[?AI:AI/]", "[?AI:AI/]", R.string.download_path_teach_cond_ai),
        Token("[?p>1: p{page}]", "[?p>1:p{page}]", R.string.download_path_teach_cond_multipage),
        Token("[?!R18:safe/]", "[?!R18:safe/]", R.string.download_path_teach_cond_not_r18),
    )

    private data class Example(val template: String, val labelRes: Int)

    private val EXAMPLES = listOf(
        Example(DefaultTemplates.ILLUST, R.string.download_path_teach_example_classic_label),
        Example("Shaft/{title} {id}[?p>1: p{page}].{ext}", R.string.download_path_teach_example_flat_label),
        Example("Shaft/{created:yyyy}/{created:yyyy-MM}/{title} {id}[?p>1: p{page}].{ext}", R.string.download_path_teach_example_date_label),
        Example("Shaft/[?R18:R18/][?AI:AI/]{author} ({author_id})/{title} {id}[?p>1: p{page}].{ext}", R.string.download_path_teach_example_r18_label),
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
            ).apply { topMargin = dp(4) }
        }
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        tokens.forEach { token ->
            val chip = TextView(requireContext()).apply {
                text = token.chipLabel
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(resources.getColor(R.color.v3_text_1, null))
                setBackgroundResource(R.drawable.bg_v3_chip)
                setPadding(dp(10), dp(6), dp(10), dp(6))
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
            ).apply { topMargin = dp(8) }
        }
        tokens.forEach { token ->
            val line = TextView(requireContext()).apply {
                textSize = 11f
                text = "${token.chipLabel}  —  ${getString(token.explainRes)}"
                setTextColor(resources.getColor(R.color.v3_text_3, null))
                setPadding(dp(2), dp(1), 0, dp(1))
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
            ).apply { topMargin = dp(4) }
        }
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        EXAMPLES.forEach { example ->
            val btn = TextView(requireContext()).apply {
                text = getString(example.labelRes)
                textSize = 11f
                setTextColor(0xFF6C5CE7.toInt())
                setBackgroundResource(R.drawable.bg_v3_pill_secondary)
                setPadding(dp(14), dp(6), dp(14), dp(6))
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
            ToastUtils.show(getString(R.string.download_path_teach_tip_focus))
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
            ToastUtils.show(getString(R.string.download_path_teach_tip_focus))
            return
        }
        editor.setText(template)
        editor.setSelection(template.length)
    }

    // ---------------- Per-bucket card ----------------

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
        cell.findViewById<TextView>(R.id.bucket_policy).text = policyLabel(resolved.overwrite)

        val templateEdit = cell.findViewById<EditText>(R.id.bucket_template)
        val previewView = cell.findViewById<TextView>(R.id.bucket_preview)

        templateEdit.setText(resolved.template)
        refreshPreview(templateEdit.text.toString(), bucket, previewView)
        templateEdit.addTextChangedListener {
            refreshPreview(it?.toString().orEmpty(), bucket, previewView)
        }
        templateEdit.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) focusedEditor = v as EditText
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
            ToastUtils.show(
                getString(R.string.download_path_invalid_template) +
                    "\n" + result.errors.joinToString("\n") { it.message },
            )
            return
        }
        DownloadsRegistry.store.update { cfg ->
            val existing = cfg.perBucket[bucket] ?: BucketConfig()
            cfg.withBucket(bucket, existing.copy(template = source))
        }
        ToastUtils.show(getString(R.string.download_path_saved))
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
            setPadding(dp(24), dp(12), dp(24), dp(12))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(22); gravity = android.view.Gravity.CENTER_HORIZONTAL }
            setOnClickListener {
                val current = DownloadsRegistry.store.loadOrFallback()
                val cleared = DownloadConfig(
                    defaults = current.defaults,
                    perBucket = emptyMap(),
                    wifiOnly = current.wifiOnly,
                )
                DownloadsRegistry.store.save(cleared)
                DownloadsRegistry.invalidateBackends()
                ToastUtils.show(getString(R.string.download_path_reset_done))
                render()
            }
        }
        root.addView(btn)
    }

    // ---------------- Helpers ----------------

    private fun addSectionTitle(root: LinearLayout, text: String, topMarginDp: Int) {
        val title = TextView(requireContext()).apply {
            this.text = text
            setTextAppearance(R.style.textMontserratBold)
            textSize = 13f
            setTextColor(resources.getColor(R.color.v3_text_3, null))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topMarginDp); bottomMargin = dp(8); leftMargin = dp(6) }
            letterSpacing = 0.05f
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
        textSize = 12f
        setTextColor(resources.getColor(R.color.v3_text_2, null))
        setLineSpacing(0f, 1.3f)
    }

    private fun subHeader(text: String, topDp: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(resources.getColor(R.color.v3_text_3, null))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(topDp), 0, dp(4))
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
