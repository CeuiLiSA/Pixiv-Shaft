package ceui.pixiv.ui.settings

import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
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
 * Shows the active per-bucket download config and teaches users how to shape
 * it. Structure:
 *
 *   1. Preset row — one-tap switch between four curated configs.
 *   2. Teaching card — intro, tappable variable chips, tappable condition
 *      chips, tappable example templates. Chips splice into whichever bucket
 *      template the user most recently focused; examples replace its text.
 *   3. Per-bucket cards — storage + policy (read-only for this pass),
 *      template editor with live preview, save / reset.
 *   4. Reset-all footer.
 *
 * No ViewModel — config reads/writes are synchronous via
 * [DownloadsRegistry.store], and the page is tiny enough to re-render the
 * whole layout on each mutation.
 */
class DownloadPathSettingsFragment : Fragment(R.layout.fragment_download_path_settings) {

    private val binding by viewBinding(FragmentDownloadPathSettingsBinding::bind)

    // The editor chip insertion targets — updated every time a template field
    // gains focus. Null means "no editor focused yet" → chip tap shows a hint.
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

        addPresetsHeader(root)
        addTeachingCard(root)
        USER_BUCKETS.forEach { (bucket, labelRes) -> addBucketSection(root, bucket, getString(labelRes)) }
        addResetAll(root)
    }

    // ---------------- Presets ----------------

    private fun addPresetsHeader(root: LinearLayout) {
        root.addView(sectionTitle(getString(R.string.download_path_presets_title)))
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        listOf(
            ConfigPresets.Id.ShaftClassic to R.string.download_path_preset_classic,
            ConfigPresets.Id.Flat         to R.string.download_path_preset_flat,
            ConfigPresets.Id.ByDate       to R.string.download_path_preset_by_date,
            ConfigPresets.Id.ByAuthor     to R.string.download_path_preset_by_author,
        ).forEach { (id, labelRes) ->
            val btn = Button(requireContext()).apply {
                text = getString(labelRes)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener { applyPreset(id) }
            }
            row.addView(btn)
        }
        root.addView(row)
    }

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

    /**
     * Catalogue of tappable tokens. Each entry: (token to splice, human label
     * shown on chip, explanation shown as secondary line in a caption).
     * Labels are intentionally punchy — the explanation below shows once.
     */
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
        Example(
            DefaultTemplates.ILLUST,
            R.string.download_path_teach_example_classic_label,
        ),
        Example(
            "Shaft/{title} {id}[?p>1: p{page}].{ext}",
            R.string.download_path_teach_example_flat_label,
        ),
        Example(
            "Shaft/{created:yyyy}/{created:yyyy-MM}/{title} {id}[?p>1: p{page}].{ext}",
            R.string.download_path_teach_example_date_label,
        ),
        Example(
            "Shaft/[?R18:R18/][?AI:AI/]{author} ({author_id})/{title} {id}[?p>1: p{page}].{ext}",
            R.string.download_path_teach_example_r18_label,
        ),
    )

    private fun addTeachingCard(root: LinearLayout) {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_cell_rounded)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }

        card.addView(sectionTitle(getString(R.string.download_path_teach_section_intro)).apply {
            setPadding(0, 0, 0, dp(4))
        })
        card.addView(body(getString(R.string.download_path_teach_intro_body)))

        card.addView(sectionTitle(getString(R.string.download_path_teach_section_vars)).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        card.addView(chipRow(VARIABLE_TOKENS, isCondition = false))
        card.addView(explainList(VARIABLE_TOKENS))

        card.addView(sectionTitle(getString(R.string.download_path_teach_section_cond)).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        card.addView(chipRow(CONDITION_TOKENS, isCondition = true))
        card.addView(explainList(CONDITION_TOKENS))

        card.addView(sectionTitle(getString(R.string.download_path_teach_section_examples)).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        card.addView(exampleRow())

        root.addView(card)
    }

    private fun chipRow(tokens: List<Token>, isCondition: Boolean): View {
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        tokens.forEach { token ->
            val chip = Button(requireContext()).apply {
                text = token.chipLabel
                textSize = 11f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                typeface = android.graphics.Typeface.MONOSPACE
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
        }
        tokens.forEach { token ->
            val line = TextView(requireContext()).apply {
                textSize = 11f
                text = "${token.chipLabel}  —  ${getString(token.explainRes)}"
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                setPadding(dp(2), dp(2), 0, 0)
            }
            col.addView(line)
        }
        return col
    }

    private fun exampleRow(): View {
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        EXAMPLES.forEach { example ->
            val btn = Button(requireContext()).apply {
                text = getString(example.labelRes)
                textSize = 11f
                isAllCaps = false
                minWidth = 0
                minHeight = 0
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

    private fun addBucketSection(root: LinearLayout, bucket: Bucket, label: String) {
        val inflater = LayoutInflater.from(requireContext())
        val cell = inflater.inflate(R.layout.cell_download_bucket, root, false) as LinearLayout
        val lp = cell.layoutParams as LinearLayout.LayoutParams
        lp.bottomMargin = dp(10)
        cell.layoutParams = lp

        val config = DownloadsRegistry.store.loadOrFallback()
        val resolved = config.resolve(bucket)

        cell.findViewById<TextView>(R.id.bucket_name).text = label
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

        cell.findViewById<Button>(R.id.bucket_save).setOnClickListener {
            saveBucketTemplate(bucket, templateEdit.text.toString())
        }
        cell.findViewById<Button>(R.id.bucket_reset).setOnClickListener {
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
        val btn = Button(requireContext()).apply {
            text = getString(R.string.download_path_btn_reset_all)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16); bottomMargin = dp(24) }
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

    private fun sectionTitle(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        setPadding(dp(2), 0, 0, dp(4))
    }

    private fun body(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 12f
        setPadding(dp(2), 0, 0, 0)
    }

    private fun storageLabel(choice: StorageChoice): String = when (choice) {
        is StorageChoice.MediaStore -> when (choice.collection) {
            StorageChoice.MediaStore.Collection.Images    -> getString(R.string.download_path_storage_mediastore_images)
            StorageChoice.MediaStore.Collection.Downloads -> getString(R.string.download_path_storage_mediastore_downloads)
        }
        is StorageChoice.Saf        -> getString(R.string.download_path_storage_saf) + "\n" + choice.treeUri
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
