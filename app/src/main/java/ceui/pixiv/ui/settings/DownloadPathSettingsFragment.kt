package ceui.pixiv.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
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
 * Shows the active per-bucket download config: storage backend, overwrite
 * policy, filename template, plus a live preview rendered from a sample work.
 *
 * Users can edit any bucket's template inline and save. A row of preset chips
 * at the top applies a full configuration in one tap. A variable reference
 * footer documents the template DSL.
 *
 * UI is intentionally layout-only (no ViewModel) — config reads and writes are
 * synchronous through [DownloadsRegistry.store], same thread as the click.
 */
class DownloadPathSettingsFragment : Fragment(R.layout.fragment_download_path_settings) {

    private val binding by viewBinding(FragmentDownloadPathSettingsBinding::bind)

    // Buckets shown to the user. TempCache is intentionally hidden — it is a
    // facade-internal concern and not user-configurable.
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

        addPresetsHeader(root)
        USER_BUCKETS.forEach { (bucket, labelRes) -> addBucketSection(root, bucket, getString(labelRes)) }
        addHelpSection(root)
        addResetAll(root)
    }

    private fun addPresetsHeader(root: LinearLayout) {
        val header = TextView(requireContext()).apply {
            text = getString(R.string.download_path_presets_title)
            textSize = 14f
            setPadding(dp(4), dp(4), 0, dp(6))
        }
        root.addView(header)

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

    private fun addHelpSection(root: LinearLayout) {
        val title = TextView(requireContext()).apply {
            text = getString(R.string.download_path_help_title)
            textSize = 14f
            setPadding(dp(4), dp(8), 0, dp(4))
        }
        val body = TextView(requireContext()).apply {
            text = getString(R.string.download_path_help_body)
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_cell_rounded)
        }
        root.addView(title)
        root.addView(body)
    }

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
