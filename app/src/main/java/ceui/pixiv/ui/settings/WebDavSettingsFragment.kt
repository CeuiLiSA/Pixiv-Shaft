package ceui.pixiv.ui.settings

import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentWebdavSettingsBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.webdav.WebDavBackup
import ceui.pixiv.webdav.WebDavBackupWorker
import ceui.pixiv.webdav.WebDavConfig
import ceui.pixiv.webdav.WebDavException
import ceui.pixiv.webdav.WebDavLastRun
import ceui.pixiv.webdav.WebDavPrefs
import ceui.pixiv.widgets.ProgressTextButton
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.dialog.WitTipDialog
import ceui.pixiv.witstudio.theme.V3Palette
import com.hjq.toast.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * WebDAV 备份设置页（pixez-flutter#1290 的需求，在 Shaft 落地）。
 *
 * 上半张卡是连接配置（保存 / 测试），下半张卡是备份选项与操作。载荷、凭据处理与远端文件
 * 规则见 [WebDavBackup]；连接信息与密码的存储见 [WebDavPrefs]。
 *
 * 视觉与交互对齐 [AiTranslateSettingsFragment]：bg_v3 卡片 + pill 按钮、未保存改动时拦返回。
 * 按钮底色走 [V3Palette]，跟随主题色。
 */
class WebDavSettingsFragment : Fragment(R.layout.fragment_webdav_settings) {

    private val binding by viewBinding(FragmentWebdavSettingsBinding::bind)

    private var passwordVisible = false

    /** 已保存的连接配置；与表单逐字段比较判断是否有未保存改动。 */
    private var saved: WebDavConfig = WebDavConfig("", "", "", WebDavConfig.DEFAULT_FOLDER)

    /** 有网络操作在跑时禁用会互相打架的按钮。 */
    private var busy = false
        set(value) {
            field = value
            listOf(binding.webdavTestBtn, binding.webdavBackupBtn, binding.webdavRestoreBtn)
                .forEach { it.isEnabled = !value }
        }

    /** 只在有未保存改动时拦截返回，否则交给系统做预测式返回动画（同 AI 翻译设置页）。 */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = handleBackPressed()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar()
        tintButtons()
        loadSettings()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        listOf(binding.webdavUrl, binding.webdavUsername, binding.webdavPassword, binding.webdavFolder)
            .forEach { it.doAfterTextChanged { refreshBackCallback() } }

        binding.webdavPresetJianguoyun.setOnClickListener { binding.webdavUrl.setText(PRESET_JIANGUOYUN) }
        binding.webdavPresetPcloud.setOnClickListener { binding.webdavUrl.setText(PRESET_PCLOUD) }
        binding.webdavPresetPcloudEu.setOnClickListener { binding.webdavUrl.setText(PRESET_PCLOUD_EU) }
        binding.webdavPasswordToggle.setOnClickListener { togglePasswordVisibility() }
        binding.webdavSaveBtn.setOnClickListener { if (save()) Toaster.show(getString(R.string.aria2_saved)) }
        binding.webdavTestBtn.setOnClickListener { testConnection() }
        binding.webdavBackupBtn.setOnClickListener { backupNow() }
        binding.webdavRestoreBtn.setOnClickListener { pickBackupToRestore() }

        binding.webdavIncludeHistorySwitch.setOnCheckedChangeListener { _, checked ->
            WebDavPrefs.includeHistory = checked
        }
        binding.webdavAutoBackupSwitch.setOnCheckedChangeListener { switch, checked ->
            if (checked && !WebDavPrefs.load().isComplete) {
                switch.isChecked = false
                Toaster.show(getString(R.string.webdav_config_required))
                return@setOnCheckedChangeListener
            }
            WebDavPrefs.autoBackup = checked
            WebDavBackupWorker.sync(requireContext())
        }
    }

    override fun onResume() {
        super.onResume()
        // 后台自动备份可能在页面不可见时跑完。
        renderLastRun()
    }

    /**
     * 必须走 [setupV3Toolbar]：Fragment 里 include 的 toolbar_layout 不在 BaseActivity 打 inset
     * 补丁的范围内，默认 fitsSystemWindows 会把导航栏的底部 inset 也垫进顶栏，顶栏凭空高一截。
     * 返回键经 dispatcher，未保存改动照样被 [backCallback] 拦住；底部（导航栏 + 键盘）交给滚动区。
     */
    private fun setUpToolbar() {
        setupV3Toolbar(binding.root, getString(R.string.webdav_settings_title), content = binding.scrollView)
    }

    private fun tintButtons() {
        val palette = V3Palette.from(requireContext())
        listOf<TextView>(binding.webdavSaveBtn, binding.webdavBackupBtn).forEach {
            it.background = palette.pillPrimary()
            it.setTextColor(palette.onPrimary)
        }
        listOf<TextView>(binding.webdavTestBtn, binding.webdavRestoreBtn).forEach {
            it.background = palette.pillSecondary()
            it.setTextColor(palette.textAccent)
        }
    }

    private fun loadSettings() {
        val config = WebDavPrefs.load()
        saved = config
        binding.webdavUrl.setText(config.baseUrl)
        binding.webdavUsername.setText(config.username)
        binding.webdavPassword.setText(config.password)
        binding.webdavFolder.setText(config.folder)
        // 先摆状态再挂监听：初始化不该触发保存 / 排期。
        binding.webdavIncludeHistorySwitch.isChecked = WebDavPrefs.includeHistory
        binding.webdavAutoBackupSwitch.isChecked = WebDavPrefs.autoBackup
        renderLastRun()
    }

    private fun formConfig(): WebDavConfig = WebDavConfig(
        baseUrl = WebDavConfig.normalizeBaseUrl(binding.webdavUrl.text.toString()),
        username = binding.webdavUsername.text.toString().trim(),
        // 密码不 trim：首尾空格可能就是密码的一部分。
        password = binding.webdavPassword.text.toString(),
        folder = WebDavConfig.normalizeFolder(binding.webdavFolder.text.toString())
            .ifEmpty { WebDavConfig.DEFAULT_FOLDER },
    )

    /** 校验通过返回表单配置，否则提示并返回 null。 */
    private fun validForm(): WebDavConfig? {
        val config = formConfig()
        return when {
            !config.isComplete -> {
                Toaster.show(getString(R.string.webdav_config_required)); null
            }
            !config.baseUrl.startsWith("https://") && !config.baseUrl.startsWith("http://") -> {
                Toaster.show(getString(R.string.webdav_url_invalid)); null
            }
            else -> config
        }
    }

    /** 校验并保存连接配置；规范化后的地址 / 目录回填到输入框。 */
    private fun save(): Boolean {
        val config = validForm() ?: return false
        if (config != saved) {
            WebDavPrefs.save(config)
            saved = config
        }
        if (binding.webdavUrl.text.toString() != config.baseUrl) binding.webdavUrl.setText(config.baseUrl)
        if (binding.webdavFolder.text.toString() != config.folder) binding.webdavFolder.setText(config.folder)
        refreshBackCallback()
        return true
    }

    private fun isDirty(): Boolean = formConfig() != saved

    private fun refreshBackCallback() {
        if (view == null) return
        backCallback.isEnabled = isDirty()
    }

    private fun handleBackPressed() {
        if (!isDirty()) {
            exitPage()
            return
        }
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(getString(R.string.ai_translate_unsaved_title))
            .setMessage(getString(R.string.webdav_unsaved_message))
            .addAction(android.R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(getString(R.string.ai_translate_unsaved_discard)) { d, _ ->
                d.dismiss()
                exitPage()
            }
            .addAction(0, getString(R.string.ai_translate_unsaved_save), WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                if (save()) exitPage()
            }
            .show()
    }

    private fun exitPage() {
        backCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun togglePasswordVisibility() {
        passwordVisible = !passwordVisible
        val editText = binding.webdavPassword
        val selection = editText.selectionEnd
        editText.transformationMethod = if (passwordVisible) {
            HideReturnsTransformationMethod.getInstance()
        } else {
            PasswordTransformationMethod.getInstance()
        }
        editText.setSelection(selection.coerceAtMost(editText.text?.length ?: 0))
        binding.webdavPasswordToggle.setImageResource(
            if (passwordVisible) R.drawable.ic_baseline_remove_red_eye_24
            else R.drawable.ic_visibility_off_black_24dp
        )
    }

    private fun renderLastRun() {
        val run = WebDavPrefs.lastRun()
        binding.webdavLastRun.text = when {
            run == null -> getString(R.string.webdav_last_run_never)
            run.success -> getString(R.string.webdav_last_run_success, formatTime(run.timeMs))
            else -> getString(R.string.webdav_last_run_failed, formatTime(run.timeMs), run.message)
        }
    }

    private fun formatTime(timeMs: Long): String =
        DateUtils.formatDateTime(requireContext(), timeMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)

    private fun testConnection() {
        val config = validForm() ?: return
        runWithProgress(binding.webdavTestBtn) {
            WebDavBackup.testConnection(config)
            Toaster.show(getString(R.string.webdav_test_success))
        }
    }

    private fun backupNow() {
        if (!save()) return
        val config = saved
        runWithProgress(binding.webdavBackupBtn) {
            val result = WebDavBackup.backup(
                requireContext(), config,
                includeHistory = WebDavPrefs.includeHistory,
                skipIfUnchanged = false,
            )
            // 手动备份不跳过，结果必然是 Uploaded。
            if (result is WebDavBackup.BackupResult.Uploaded) {
                WebDavPrefs.recordRun(WebDavLastRun(System.currentTimeMillis(), true, result.name))
                renderLastRun()
                Toaster.show(getString(R.string.webdav_backup_success, Formatter.formatShortFileSize(requireContext(), result.size)))
            }
        }
    }

    private fun pickBackupToRestore() {
        if (!save()) return
        val config = saved
        runWithProgress(binding.webdavRestoreBtn) {
            val backups = WebDavBackup.list(config)
            if (backups.isEmpty()) {
                Toaster.show(getString(R.string.webdav_no_backups))
                return@runWithProgress
            }
            val ctx = requireContext()
            val labels = backups.map {
                getString(
                    R.string.webdav_backup_item,
                    DateUtils.formatDateTime(ctx, it.timeMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR),
                    it.device,
                    Formatter.formatShortFileSize(ctx, it.size),
                )
            }.toTypedArray()
            WitDialog.MenuDialogBuilder(ctx)
                .setTitle(R.string.webdav_pick_backup_title)
                .addItems(labels) { dialog, which ->
                    dialog.dismiss()
                    confirmRestore(config, backups[which])
                }
                .show()
        }
    }

    private fun confirmRestore(config: WebDavConfig, backup: WebDavBackup.RemoteBackup) {
        if (view == null) return
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.webdav_restore_confirm_title)
            .setMessage(R.string.webdav_restore_confirm_message)
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.webdav_btn_restore, WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                d.dismiss()
                restore(config, backup)
            }
            .show()
    }

    private fun restore(config: WebDavConfig, backup: WebDavBackup.RemoteBackup) {
        if (view == null) return
        // 还原会改写设置与数据库，进行中不允许离开页面：不可取消的进度框挡住返回。
        val progress = WitTipDialog.Builder(requireActivity())
            .setTipWord(getString(R.string.webdav_restoring))
            .create()
        progress.setCancelable(false)
        progress.show()
        busy = true
        // TemplateActivity 不处理 configChanges：还原途中旋屏 / 系统切深浅色会重建 Activity，协程被取消，
        // 但 IO 里的还原照常跑完才把取消抛回来，这时进度框的窗口已随旧 Activity 销毁，裸 dismiss 会抛
        // IllegalArgumentException 直接崩。收尾一律走 isShowing + runCatching（同 ImportLocalDownloadsFlow）。
        fun dismissProgress() {
            if (progress.isShowing) runCatching { progress.dismiss() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                WebDavBackup.restore(requireContext(), config, backup)
                dismissProgress()
                busy = false
                showRestartDialog()
            } catch (e: CancellationException) {
                dismissProgress()
                throw e
            } catch (e: Exception) {
                ensureActive()
                dismissProgress()
                busy = false
                Toaster.show(getString(R.string.webdav_restore_failed, describe(e)))
            }
        }
    }

    private fun showRestartDialog() {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(R.string.restore_success)
            .setMessage(R.string.webdav_restore_success_message)
            .addAction(R.string.webdav_restart_later) { d, _ -> d.dismiss() }
            .addAction(0, R.string.webdav_restart_now, WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                d.dismiss()
                Common.restart()
            }
            .show()
    }

    /**
     * 跑一个网络操作：按钮转圈、其余操作按钮禁用，失败统一提示。
     *
     * CancellationException 必须重抛；取消时阻塞中的 OkHttp 调用会跑完再抛真实 IO 异常，
     * 所以 catch 里先 [ensureActive] 再碰 binding（同 Aria2 / AI 翻译设置页的守卫）。
     */
    private fun runWithProgress(button: ProgressTextButton, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        button.showProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                Toaster.show(getString(R.string.webdav_operation_failed, describe(e)))
            } finally {
                if (view != null) {
                    button.hideProgress()
                    busy = false
                }
            }
        }
    }

    private fun describe(e: Exception): String = when ((e as? WebDavException)?.kind) {
        WebDavException.Kind.AUTH -> getString(R.string.webdav_error_auth)
        WebDavException.Kind.NOT_FOUND -> getString(R.string.webdav_error_not_found)
        WebDavException.Kind.BAD_URL -> getString(R.string.webdav_url_invalid)
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val PRESET_JIANGUOYUN = "https://dav.jianguoyun.com/dav/"
        const val PRESET_PCLOUD = "https://webdav.pcloud.com/"
        const val PRESET_PCLOUD_EU = "https://ewebdav.pcloud.com/"
    }
}
