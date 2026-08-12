package ceui.pixiv.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentAria2SettingsBinding
import ceui.lisa.utils.Local
import ceui.pixiv.download.aria2.Aria2Dispatcher
import ceui.pixiv.ui.common.viewBinding
import com.hjq.toast.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * aria2 远程下载设置页（#692）。
 *
 * 配置远端 aria2 的 JSON-RPC 连接信息；启用后所有图片下载任务都通过 RPC
 * 转发给远端 aria2（如跑在 NAS 上的实例），不再下载到本机。
 *
 * 视觉风格对齐 [DownloadPathSettingsFragment]（V3 设置子页：bg_v3 卡片 +
 * pill 按钮 + layout_toolbar 重着色）。
 */
class Aria2SettingsFragment : Fragment(R.layout.fragment_aria2_settings) {

    private val binding by viewBinding(FragmentAria2SettingsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpToolbar()
        loadSettings()

        binding.aria2SaveBtn.setOnClickListener { save() }
        binding.aria2TestBtn.setOnClickListener { testConnection() }
    }

    private fun setUpToolbar() {
        // 共用的 layout_toolbar 是给深色图片背景设计的（白字 + 浅色返回箭头），
        // V3 浅色背景上需要重着色 —— 与 DownloadPathSettingsFragment 同款处理。
        val toolbar = binding.toolbarLayout
        toolbar.naviTitle.apply {
            text = getString(R.string.aria2_settings_title)
            setTextColor(resources.getColor(R.color.v3_text_1, null))
            setTextAppearance(R.style.textMontserratBold)
            textSize = 18f
        }
        (toolbar.naviBack as ImageView).setColorFilter(resources.getColor(R.color.v3_text_1, null))
        toolbar.naviBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        toolbar.naviMore.visibility = View.GONE

        ViewCompat.setOnApplyWindowInsetsListener(toolbar.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(10))
            insets
        }
        ViewCompat.requestApplyInsets(toolbar.root)
    }

    private fun loadSettings() {
        val settings = Shaft.sSettings
        binding.aria2EnableSwitch.isChecked = settings.isAria2Enabled
        binding.aria2RpcUrl.setText(settings.aria2RpcUrl)
        binding.aria2RpcSecret.setText(settings.aria2RpcSecret)
        binding.aria2RemoteDir.setText(settings.aria2RemoteDir)
    }

    private fun save() {
        val enabled = binding.aria2EnableSwitch.isChecked
        val rpcUrl = binding.aria2RpcUrl.text.toString().trim()

        if (enabled && rpcUrl.isEmpty()) {
            Toaster.show(getString(R.string.aria2_url_required))
            return
        }
        if (rpcUrl.isNotEmpty() && !rpcUrl.startsWith("http://") && !rpcUrl.startsWith("https://")) {
            Toaster.show(getString(R.string.aria2_url_invalid))
            return
        }

        val settings = Shaft.sSettings
        settings.isAria2Enabled = enabled
        settings.aria2RpcUrl = rpcUrl
        settings.aria2RpcSecret = binding.aria2RpcSecret.text.toString().trim()
        settings.aria2RemoteDir = binding.aria2RemoteDir.text.toString().trim()
        Local.setSettings(settings)
        Toaster.show(getString(R.string.aria2_saved))
    }

    private fun testConnection() {
        val rpcUrl = binding.aria2RpcUrl.text.toString().trim()
        if (rpcUrl.isEmpty()) {
            Toaster.show(getString(R.string.aria2_url_required))
            return
        }
        val secret = binding.aria2RpcSecret.text.toString().trim()

        binding.aria2TestBtn.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // CancellationException 必须重新抛出（用户在测试期间退出页面 → view 销毁 →
            // scope 取消）：吞掉它会继续执行到 binding 访问，而此时 requireView() 必抛
            // IllegalStateException 直接 crash。
            try {
                val version = withContext(Dispatchers.IO) {
                    Aria2Dispatcher.testConnection(rpcUrl, secret)
                }
                binding.aria2TestBtn.isEnabled = true
                Toaster.show(getString(R.string.aria2_test_success, version))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 光拦 CancellationException 不够:取消发生时阻塞中的网络调用会继续跑完,
                // 最终抛上来的是真实 IO 异常而不是 CancellationException —— 此时 view
                // 已销毁,碰 binding 必崩(FragmentViewBindingDelegate requireView)。
                ensureActive()
                binding.aria2TestBtn.isEnabled = true
                Toaster.show(getString(R.string.aria2_test_failed, e.message ?: e.toString()))
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
