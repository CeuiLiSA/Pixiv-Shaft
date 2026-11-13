package ceui.pixiv.ui.settings

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentManageStorageSettingsBinding
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction

/**
 * 细化管理下载存储位置（分桶细化选择与自选目录设置）。
 */
class ManageStorageSettingsFragment : Fragment(R.layout.fragment_manage_storage_settings) {

    private val binding by viewBinding(FragmentManageStorageSettingsBinding::bind)

    private enum class StorageType {
        PICTURES,
        DOWNLOADS,
        SAF,
    }

    private val USER_BUCKETS = listOf(
        Bucket.Illust to R.string.download_path_bucket_illust,
        Bucket.Ugoira to R.string.download_path_bucket_ugoira,
        Bucket.Novel  to R.string.download_path_bucket_novel,
        Bucket.NovelSeries to R.string.download_path_bucket_novel_series,
        Bucket.Caption to R.string.download_path_bucket_caption,
        Bucket.Backup to R.string.download_path_bucket_backup,
        Bucket.Log    to R.string.download_path_bucket_log,
    )

    private lateinit var initialConfig: DownloadConfig
    private val bucketChoices = mutableMapOf<Bucket, StorageType>()
    private var configuredSafUri: Uri? = null

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleExitAttempt()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarLayout.toolbarTitle.setText(R.string.setting_storage_choice)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener {
            handleExitAttempt()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        initialConfig = DownloadsRegistry.store.loadOrFallback()
        configuredSafUri = findValidSafUri()

        // 初始化每个分桶的选中状态
        USER_BUCKETS.forEach { (bucket, _) ->
            val resolved = initialConfig.resolve(bucket)
            bucketChoices[bucket] = when (val storage = resolved.storage) {
                is StorageChoice.MediaStore -> when (storage.collection) {
                    StorageChoice.MediaStore.Collection.Images -> StorageType.PICTURES
                    StorageChoice.MediaStore.Collection.Downloads -> StorageType.DOWNLOADS
                }
                is StorageChoice.Saf -> StorageType.SAF
                else -> StorageType.DOWNLOADS
            }
        }

        binding.btnSetSaf.setOnClickListener {
            BaseActivity.launchSafTreePicker(requireActivity())
        }

        renderSafHeader()
        renderBucketCards()
    }

    override fun onStart() {
        super.onStart()
        BaseActivity.setSafGrantInterceptor { treeUri ->
            configuredSafUri = treeUri
            renderSafHeader()
            true
        }
    }

    override fun onStop() {
        super.onStop()
        BaseActivity.setSafGrantInterceptor(null)
    }

    private fun findValidSafUri(): Uri? {
        // 1. 检查各 bucket 已配置的 Saf
        for (bc in initialConfig.perBucket.values) {
            val s = bc.storage
            if (s is StorageChoice.Saf && isSafUriValid(s.treeUri)) {
                return s.treeUri
            }
        }
        val defaultStorage = initialConfig.defaults.storage
        if (defaultStorage is StorageChoice.Saf && isSafUriValid(defaultStorage.treeUri)) {
            return defaultStorage.treeUri
        }
        // 2. 检查 Shaft.sSettings.rootPathUri
        val rootPath = Shaft.sSettings?.rootPathUri
        if (!rootPath.isNullOrEmpty()) {
            val parsed = runCatching { Uri.parse(rootPath) }.getOrNull()
            if (parsed != null && isSafUriValid(parsed)) {
                return parsed
            }
        }
        return null
    }

    private fun isSafUriValid(uri: Uri): Boolean {
        return runCatching {
            requireContext().contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
        }.getOrDefault(false)
    }

    private fun hasCompletedSafSetup(): Boolean {
        val uri = configuredSafUri ?: return false
        return isSafUriValid(uri)
    }

    private fun renderSafHeader() {
        val uri = configuredSafUri
        val anySafSelected = bucketChoices.values.any { it == StorageType.SAF }

        if (uri == null) {
            if (!anySafSelected) {
                binding.safPathText.setText(R.string.storage_saf_hint_idle)
            } else {
                binding.safPathText.setText(R.string.storage_saf_not_set)
            }
        } else {
            val absPath = treeUriToAbsolutePath(uri)
            val pathLine = getString(R.string.storage_saf_current_path, absPath)
            if (!anySafSelected) {
                val appended = getString(R.string.storage_saf_hint_not_used)
                binding.safPathText.text = "$pathLine\n$appended"
            } else {
                binding.safPathText.text = pathLine
            }
        }

        binding.btnSetSaf.isEnabled = anySafSelected
        binding.btnSetSaf.isClickable = anySafSelected
        binding.btnSetSaf.alpha = if (anySafSelected) 1.0f else 0.4f
    }

    private fun treeUriToAbsolutePath(uri: Uri): String {
        val docId = runCatching {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        }.getOrNull()

        if (!docId.isNullOrEmpty() && docId.contains(":")) {
            val parts = docId.split(":", limit = 2)
            val volume = parts[0]
            val relative = parts.getOrNull(1).orEmpty().trim('/')
            val root = if (volume.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/$volume"
            }
            return if (relative.isEmpty()) root else "$root/$relative"
        }

        val decoded = runCatching { Uri.decode(uri.toString()) }.getOrDefault(uri.toString())
        val primaryIdx = decoded.indexOf("primary:")
        if (primaryIdx != -1) {
            val relative = decoded.substring(primaryIdx + "primary:".length).trim('/')
            val root = Environment.getExternalStorageDirectory().absolutePath
            return if (relative.isEmpty()) root else "$root/$relative"
        }

        val pattern = Regex("([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}):(.*)")
        val match = pattern.find(decoded)
        if (match != null) {
            val volume = match.groupValues[1]
            val relative = match.groupValues[2].trim('/')
            val root = "/storage/$volume"
            return if (relative.isEmpty()) root else "$root/$relative"
        }

        return uri.path ?: decoded
    }

    private fun renderBucketCards() {
        val container = binding.bucketContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        USER_BUCKETS.forEach { (bucket, labelRes) ->
            val cell = inflater.inflate(R.layout.cell_manage_storage_bucket, container, false)
            (cell.layoutParams as LinearLayout.LayoutParams).apply {
                bottomMargin = dp(12)
            }.also { cell.layoutParams = it }

            cell.findViewById<TextView>(R.id.bucket_name).setText(labelRes)

            val pillPictures = cell.findViewById<TextView>(R.id.pill_pictures)
            val pillDownloads = cell.findViewById<TextView>(R.id.pill_downloads)
            val pillSaf = cell.findViewById<TextView>(R.id.pill_saf)
            val picturesHint = cell.findViewById<View>(R.id.pictures_unsupported_hint)

            val isImageBucket = bucket == Bucket.Illust || bucket == Bucket.Ugoira

            fun stylePill(pill: TextView, selected: Boolean, available: Boolean) {
                if (!available) {
                    pill.isEnabled = false
                    pill.isClickable = false
                    pill.alpha = 0.35f
                    pill.setBackgroundResource(R.drawable.bg_v3_pill)
                    pill.setTextColor(ContextCompat.getColor(requireContext(), R.color.v3_text_3))
                } else if (selected) {
                    pill.isEnabled = true
                    pill.isClickable = true
                    pill.alpha = 1.0f
                    pill.setBackgroundResource(R.drawable.bg_v3_pill_primary)
                    pill.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                } else {
                    pill.isEnabled = true
                    pill.isClickable = true
                    pill.alpha = 1.0f
                    pill.setBackgroundResource(R.drawable.bg_v3_pill_secondary)
                    pill.setTextColor(0xFF6C5CE7.toInt())
                }
            }

            fun refreshPills() {
                val current = bucketChoices[bucket] ?: StorageType.DOWNLOADS
                stylePill(pillPictures, current == StorageType.PICTURES, isImageBucket)
                stylePill(pillDownloads, current == StorageType.DOWNLOADS, true)
                stylePill(pillSaf, current == StorageType.SAF, true)
            }

            if (isImageBucket) {
                picturesHint.visibility = View.GONE
                pillPictures.setOnClickListener {
                    bucketChoices[bucket] = StorageType.PICTURES
                    refreshPills()
                    renderSafHeader()
                }
            } else {
                picturesHint.visibility = View.VISIBLE
            }

            pillDownloads.setOnClickListener {
                bucketChoices[bucket] = StorageType.DOWNLOADS
                refreshPills()
                renderSafHeader()
            }

            pillSaf.setOnClickListener {
                bucketChoices[bucket] = StorageType.SAF
                refreshPills()
                renderSafHeader()
            }

            refreshPills()
            container.addView(cell)
        }
    }

    private fun handleExitAttempt() {
        val anySafSelected = bucketChoices.values.any { it == StorageType.SAF }
        if (anySafSelected && !hasCompletedSafSetup()) {
            WitDialog.MessageDialogBuilder(requireContext())
                .setTitle(R.string.storage_saf_unset_title)
                .setMessage(R.string.storage_saf_unset_message)
                .addAction(getString(R.string.storage_saf_unset_cancel)) { dialog, _ ->
                    dialog.dismiss()
                }
                .addAction(0, R.string.storage_saf_unset_exit, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                    dialog.dismiss()
                    rollbackAndExit()
                }
                .show()
        } else {
            saveConfigAndExit()
        }
    }

    private fun rollbackAndExit() {
        DownloadsRegistry.store.update { initialConfig }
        DownloadsRegistry.invalidateBackends()
        backCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun saveConfigAndExit() {
        val safUri = configuredSafUri
        val updates = mutableMapOf<Bucket, StorageChoice>()
        USER_BUCKETS.forEach { (bucket, _) ->
            val choiceType = bucketChoices[bucket] ?: StorageType.DOWNLOADS
            val storageChoice: StorageChoice = when (choiceType) {
                StorageType.PICTURES -> StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
                StorageType.DOWNLOADS -> StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
                StorageType.SAF -> {
                    if (safUri != null) {
                        StorageChoice.Saf(safUri)
                    } else {
                        StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
                    }
                }
            }
            updates[bucket] = storageChoice
        }
        DownloadsRegistry.updateBucketStorages(updates)
        backCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}