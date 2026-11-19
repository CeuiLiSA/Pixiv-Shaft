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
import androidx.documentfile.provider.DocumentFile
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

    private val initialBucketChoices = mutableMapOf<Bucket, StorageType>()
    private val bucketChoices = mutableMapOf<Bucket, StorageType>()
    private var initialSafUri: Uri? = null
    private var configuredSafUri: Uri? = null
    private var sessionGrantedSafUri: Uri? = null
    private var boundHostActivity: BaseActivity<*>? = null
    private var unsetSafDialog: WitDialog? = null
    private var isExiting: Boolean = false

    private val safGrantInterceptor = BaseActivity.SafGrantInterceptor { treeUri ->
        configuredSafUri = treeUri
        sessionGrantedSafUri = treeUri
        if (view != null) {
            renderSafHeader()
            refreshBackCallback()
        }
        true
    }

    /**
     * 仅在存在待保存改动或未设定自选目录需要质询时启用拦截，
     * 避免常开 callback 掐死系统的预测式返回动画。
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            handleExitAttempt()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_CURRENT_CHOICES)) {
            restoreState(savedInstanceState)
        } else {
            initFreshState()
        }
        // 在 onCreate/onDestroy（而非 onStart/onStop）绑定宿主实例级拦截器：
        // 系统文件选择器 (DocumentsUI) 弹出时会触发 onStop，返回时 onActivityResult 先于 onStart
        // 投递；若 Activity 期间被回收重建，onCreate 也先于 onActivityResult 执行。
        boundHostActivity = (activity as? BaseActivity<*>)?.also {
            it.setSafGrantInterceptor(safGrantInterceptor)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(
            STATE_INITIAL_CHOICES,
            USER_BUCKETS.map { (bucket, _) ->
                (initialBucketChoices[bucket] ?: StorageType.DOWNLOADS).name
            }.toTypedArray(),
        )
        outState.putStringArray(
            STATE_CURRENT_CHOICES,
            USER_BUCKETS.map { (bucket, _) ->
                (bucketChoices[bucket] ?: StorageType.DOWNLOADS).name
            }.toTypedArray(),
        )
        outState.putString(STATE_INITIAL_SAF_URI, initialSafUri?.toString())
        outState.putString(STATE_CONFIGURED_SAF_URI, configuredSafUri?.toString())
        outState.putString(STATE_SESSION_GRANTED_SAF_URI, sessionGrantedSafUri?.toString())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarLayout.toolbarTitle.setText(R.string.setting_storage_choice)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener {
            handleExitAttempt()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        binding.btnSetSaf.setOnClickListener {
            BaseActivity.launchSafTreePicker(requireActivity())
        }

        renderSafHeader()
        renderBucketCards()
        refreshBackCallback()
    }

    override fun onResume() {
        super.onResume()
        refreshBackCallback()
    }

    override fun onDestroyView() {
        unsetSafDialog?.dismiss()
        unsetSafDialog = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        clearSafInterceptor()
        super.onDestroy()
    }

    override fun onDetach() {
        clearSafInterceptor()
        super.onDetach()
    }

    private fun clearSafInterceptor() {
        boundHostActivity?.clearSafGrantInterceptor(safGrantInterceptor)
        boundHostActivity = null
    }

    private fun initFreshState() {
        val initialConfig = DownloadsRegistry.store.loadOrFallback()
        initialSafUri = findValidSafUri(initialConfig)
        configuredSafUri = initialSafUri
        sessionGrantedSafUri = null

        initialBucketChoices.clear()
        bucketChoices.clear()
        USER_BUCKETS.forEach { (bucket, _) ->
            val resolved = initialConfig.resolve(bucket)
            val choice = when (val storage = resolved.storage) {
                is StorageChoice.MediaStore -> when (storage.collection) {
                    StorageChoice.MediaStore.Collection.Images -> StorageType.PICTURES
                    StorageChoice.MediaStore.Collection.Downloads -> StorageType.DOWNLOADS
                }
                is StorageChoice.Saf -> StorageType.SAF
                else -> StorageType.DOWNLOADS
            }
            initialBucketChoices[bucket] = choice
            bucketChoices[bucket] = choice
        }
    }

    private fun restoreState(savedInstanceState: Bundle) {
        val initialNames = savedInstanceState.getStringArray(STATE_INITIAL_CHOICES)
        val currentNames = savedInstanceState.getStringArray(STATE_CURRENT_CHOICES)
        if (initialNames == null || currentNames == null
            || initialNames.size != USER_BUCKETS.size
            || currentNames.size != USER_BUCKETS.size
        ) {
            initFreshState()
            return
        }
        initialBucketChoices.clear()
        bucketChoices.clear()
        USER_BUCKETS.forEachIndexed { index, (bucket, _) ->
            initialBucketChoices[bucket] = parseStorageType(initialNames[index])
            bucketChoices[bucket] = parseStorageType(currentNames[index])
        }
        initialSafUri = savedInstanceState.getString(STATE_INITIAL_SAF_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        configuredSafUri = savedInstanceState.getString(STATE_CONFIGURED_SAF_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        sessionGrantedSafUri = savedInstanceState.getString(STATE_SESSION_GRANTED_SAF_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    private fun parseStorageType(name: String?): StorageType =
        runCatching { if (name != null) StorageType.valueOf(name) else StorageType.DOWNLOADS }
            .getOrDefault(StorageType.DOWNLOADS)

    private fun findValidSafUri(config: DownloadConfig): Uri? {
        // 1. 检查各 bucket 已配置的 Saf
        for (bc in config.perBucket.values) {
            val s = bc.storage
            if (s is StorageChoice.Saf && isSafUriValid(s.treeUri)) {
                return s.treeUri
            }
        }
        val defaultStorage = config.defaults.storage
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
        return uri == sessionGrantedSafUri || isSafUriValid(uri)
    }

    private fun needsSafWarning(): Boolean =
        bucketChoices.values.any { it == StorageType.SAF } && !hasCompletedSafSetup()

    private fun hasPendingChanges(): Boolean {
        if (bucketChoices != initialBucketChoices) return true
        val anySafSelected = bucketChoices.values.any { it == StorageType.SAF }
        return anySafSelected && configuredSafUri != initialSafUri
    }

    private fun refreshBackCallback() {
        if (view == null) return
        backCallback.isEnabled = !isExiting && (needsSafWarning() || hasPendingChanges())
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
        binding.btnSetSaf.isFocusable = anySafSelected
        binding.btnSetSaf.alpha = if (anySafSelected) 1.0f else 0.4f
    }

    private fun treeUriToAbsolutePath(uri: Uri): String {
        val ctx = requireContext()
        return formatSafTreePath(
            uri = uri,
            externalStorageRoot = Environment.getExternalStorageDirectory().absolutePath,
            documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
            fallbackDisplayName = {
                runCatching { DocumentFile.fromTreeUri(ctx, uri)?.name }.getOrNull()
            },
        )
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
                    pill.isFocusable = false
                    pill.alpha = 0.6f
                    pill.setBackgroundResource(R.drawable.bg_v3_pill)
                    pill.setTextColor(ContextCompat.getColor(requireContext(), R.color.v3_text_2))
                } else if (selected) {
                    pill.isEnabled = true
                    pill.isClickable = true
                    pill.isFocusable = true
                    pill.alpha = 1.0f
                    pill.setBackgroundResource(R.drawable.bg_v3_pill_primary)
                    pill.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                } else {
                    pill.isEnabled = true
                    pill.isClickable = true
                    pill.isFocusable = true
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
                    refreshBackCallback()
                }
            } else {
                picturesHint.visibility = View.VISIBLE
            }

            pillDownloads.setOnClickListener {
                bucketChoices[bucket] = StorageType.DOWNLOADS
                refreshPills()
                renderSafHeader()
                refreshBackCallback()
            }

            pillSaf.setOnClickListener {
                bucketChoices[bucket] = StorageType.SAF
                refreshPills()
                renderSafHeader()
                refreshBackCallback()
            }

            refreshPills()
            container.addView(cell)
        }
    }

    private fun handleExitAttempt() {
        if (isExiting || unsetSafDialog?.isShowing == true) return
        when {
            needsSafWarning() -> {
                unsetSafDialog = WitDialog.MessageDialogBuilder(requireContext())
                    .setTitle(R.string.storage_saf_unset_title)
                    .setMessage(R.string.storage_saf_unset_message)
                    .addAction(getString(R.string.storage_saf_unset_cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .addAction(0, R.string.storage_saf_unset_exit, WitDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                        dialog.dismiss()
                        exitPage()
                    }
                    .show()
                    .apply {
                        setOnDismissListener {
                            if (unsetSafDialog === this) {
                                unsetSafDialog = null
                            }
                        }
                    }
            }
            hasPendingChanges() -> saveConfigAndExit()
            else -> exitPage()
        }
    }

    private fun saveConfigAndExit() {
        val safUri = configuredSafUri
        val updates = LinkedHashMap<Bucket, StorageChoice>(USER_BUCKETS.size)
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
        exitPage()
    }

    private fun exitPage() {
        if (isExiting) return
        isExiting = true
        backCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val DOWNLOADS_DOCUMENTS_AUTHORITY = "com.android.providers.downloads.documents"

        private const val STATE_INITIAL_CHOICES = "manage_storage_initial_choices"
        private const val STATE_CURRENT_CHOICES = "manage_storage_current_choices"
        private const val STATE_INITIAL_SAF_URI = "manage_storage_initial_saf_uri"
        private const val STATE_CONFIGURED_SAF_URI = "manage_storage_configured_saf_uri"
        private const val STATE_SESSION_GRANTED_SAF_URI = "manage_storage_session_granted_saf_uri"

        private val USER_BUCKETS = listOf(
            Bucket.Illust to R.string.download_path_bucket_illust,
            Bucket.Ugoira to R.string.download_path_bucket_ugoira,
            Bucket.Novel to R.string.download_path_bucket_novel,
            Bucket.NovelSeries to R.string.download_path_bucket_novel_series,
            Bucket.Caption to R.string.download_path_bucket_caption,
            Bucket.Backup to R.string.download_path_bucket_backup,
            Bucket.Log to R.string.download_path_bucket_log,
        )

        /**
         * 把 SAF tree URI 转为可读路径：仅对 [EXTERNAL_STORAGE_AUTHORITY] 翻译
         * `volume:relative`（含 `primary:`、`home:` 与外置存储 UUID），对
         * [DOWNLOADS_DOCUMENTS_AUTHORITY] 的 `raw:/...` 提取真实路径；其余不透明
         * provider（如云盘）退回目录显示名或 URI，绝不拼造不存在的 `/storage/...`。
         */
        internal fun formatSafTreePath(
            uri: Uri,
            externalStorageRoot: String,
            documentsDir: String,
            fallbackDisplayName: () -> String?,
        ): String {
            val docId = runCatching {
                if (DocumentsContract.isTreeUri(uri)) {
                    DocumentsContract.getTreeDocumentId(uri)
                } else {
                    DocumentsContract.getDocumentId(uri)
                }
            }.getOrNull()

            when (uri.authority) {
                EXTERNAL_STORAGE_AUTHORITY -> {
                    if (!docId.isNullOrEmpty() && docId.contains(":")) {
                        val parts = docId.split(":", limit = 2)
                        val volume = parts[0]
                        val relative = parts.getOrNull(1).orEmpty().trim('/')
                        val root = when {
                            volume.equals("primary", ignoreCase = true) -> externalStorageRoot.trimEnd('/')
                            volume.equals("home", ignoreCase = true) -> documentsDir.trimEnd('/')
                            else -> "/storage/$volume"
                        }
                        return if (relative.isEmpty()) root else "$root/$relative"
                    }
                }
                DOWNLOADS_DOCUMENTS_AUTHORITY -> {
                    if (!docId.isNullOrEmpty() && docId.startsWith("raw:/")) {
                        return docId.removePrefix("raw:")
                    }
                }
            }

            val displayName = fallbackDisplayName()?.takeIf { it.isNotBlank() }
            if (displayName != null) {
                return displayName
            }
            val decoded = runCatching { Uri.decode(uri.toString()) }.getOrDefault(uri.toString())
            return uri.path?.takeIf { it.isNotBlank() } ?: decoded
        }
    }
}
