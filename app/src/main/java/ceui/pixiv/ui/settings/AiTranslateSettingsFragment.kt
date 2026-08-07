package ceui.pixiv.ui.settings

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentAiTranslateSettingsBinding
import ceui.lisa.utils.Local
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.translate.AiTranslator
import com.hjq.toast.Toaster
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 自定义 AI 翻译设置页（#975）。
 *
 * 配置 OpenAI 兼容接口（base URL + API key + 模型名 + 可选自定义提示词）；启用后
 * 评论翻译与漫画翻译改走该接口（[AiTranslator]），替代内置的 Google web 端点。
 * base URL 可指向任何兼容服务：OpenAI / DeepSeek 等云端，或 Ollama、llama.cpp
 * server（Sakura 模型）等本地部署（本地服务 key 可留空）。
 *
 * 视觉风格与保存/测试交互对齐 [Aria2SettingsFragment]（bg_v3 卡片 + pill 按钮 +
 * layout_toolbar 重着色）。
 */
class AiTranslateSettingsFragment : Fragment(R.layout.fragment_ai_translate_settings) {

    private val binding by viewBinding(FragmentAiTranslateSettingsBinding::bind)

    private var apiKeyVisible = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpToolbar()
        loadSettings()

        binding.aiTranslateSaveBtn.setOnClickListener { save() }
        binding.aiTranslateTestBtn.setOnClickListener { testConfig() }
        binding.aiTranslateApiKeyToggle.setOnClickListener { toggleApiKeyVisibility() }
        binding.aiTranslateFetchModelsBtn.setOnClickListener { fetchModels() }

        // 常用服务一键填 base URL;模型名为空时顺手填该服务的常见默认,省一次拉列表
        binding.aiTranslatePresetOpenai.setOnClickListener {
            applyPreset("https://api.openai.com/v1", "gpt-4o-mini")
        }
        binding.aiTranslatePresetDeepseek.setOnClickListener {
            applyPreset("https://api.deepseek.com/v1", "deepseek-chat")
        }
        binding.aiTranslatePresetSiliconflow.setOnClickListener {
            applyPreset("https://api.siliconflow.cn/v1", "")
        }
    }

    private fun applyPreset(baseUrl: String, defaultModel: String) {
        binding.aiTranslateBaseUrl.setText(baseUrl)
        if (defaultModel.isNotEmpty() && binding.aiTranslateModel.text.isNullOrBlank()) {
            binding.aiTranslateModel.setText(defaultModel)
        }
    }

    private fun toggleApiKeyVisibility() {
        apiKeyVisible = !apiKeyVisible
        val editText = binding.aiTranslateApiKey
        val selection = editText.selectionEnd
        editText.transformationMethod = if (apiKeyVisible) {
            HideReturnsTransformationMethod.getInstance()
        } else {
            PasswordTransformationMethod.getInstance()
        }
        editText.setSelection(selection.coerceAtMost(editText.text?.length ?: 0))
        binding.aiTranslateApiKeyToggle.setImageResource(
            if (apiKeyVisible) R.drawable.ic_baseline_remove_red_eye_24
            else R.drawable.ic_visibility_off_black_24dp
        )
    }

    private fun setUpToolbar() {
        // 共用的 layout_toolbar 是给深色图片背景设计的（白字 + 浅色返回箭头），
        // V3 浅色背景上需要重着色 —— 与 Aria2SettingsFragment 同款处理。
        val toolbar = binding.toolbarLayout
        toolbar.naviTitle.apply {
            text = getString(R.string.ai_translate_settings_title)
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
        binding.aiTranslateEnableSwitch.isChecked = settings.isAiTranslateEnabled
        binding.aiTranslateBaseUrl.setText(settings.aiTranslateBaseUrl)
        binding.aiTranslateApiKey.setText(settings.aiTranslateApiKey)
        binding.aiTranslateModel.setText(settings.aiTranslateModel)
        binding.aiTranslatePrompt.setText(settings.aiTranslatePrompt)
    }

    private fun save() {
        val enabled = binding.aiTranslateEnableSwitch.isChecked
        val baseUrl = binding.aiTranslateBaseUrl.text.toString().trim()
        val model = binding.aiTranslateModel.text.toString().trim()

        if (enabled && (baseUrl.isEmpty() || model.isEmpty())) {
            Toaster.show(getString(R.string.ai_translate_config_required))
            return
        }
        if (baseUrl.isNotEmpty() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            Toaster.show(getString(R.string.ai_translate_url_invalid))
            return
        }

        val settings = Shaft.sSettings
        settings.isAiTranslateEnabled = enabled
        settings.aiTranslateBaseUrl = baseUrl
        settings.aiTranslateApiKey = binding.aiTranslateApiKey.text.toString().trim()
        settings.aiTranslateModel = model
        settings.aiTranslatePrompt = binding.aiTranslatePrompt.text.toString().trim()
        Local.setSettings(settings)
        Toaster.show(getString(R.string.aria2_saved))
    }

    private fun testConfig() {
        val baseUrl = binding.aiTranslateBaseUrl.text.toString().trim()
        val model = binding.aiTranslateModel.text.toString().trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            Toaster.show(getString(R.string.ai_translate_config_required))
            return
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            Toaster.show(getString(R.string.ai_translate_url_invalid))
            return
        }
        val apiKey = binding.aiTranslateApiKey.text.toString().trim()
        val prompt = binding.aiTranslatePrompt.text.toString().trim()

        binding.aiTranslateTestBtn.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // CancellationException 必须重新抛出（测试期间退出页面 → scope 取消）：
            // 吞掉会继续访问已销毁的 binding 直接 crash —— 与 Aria2SettingsFragment 同款守卫。
            try {
                val translated = AiTranslator.testConfig(baseUrl, apiKey, model, prompt)
                binding.aiTranslateTestBtn.isEnabled = true
                Toaster.show(getString(R.string.ai_translate_test_success, translated))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.aiTranslateTestBtn.isEnabled = true
                Toaster.show(getString(R.string.ai_translate_test_failed, e.message ?: e.toString()))
            }
        }
    }

    private fun fetchModels() {
        val baseUrl = binding.aiTranslateBaseUrl.text.toString().trim()
        if (baseUrl.isEmpty()) {
            Toaster.show(getString(R.string.ai_translate_config_required))
            return
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            Toaster.show(getString(R.string.ai_translate_url_invalid))
            return
        }
        val apiKey = binding.aiTranslateApiKey.text.toString().trim()

        binding.aiTranslateFetchModelsBtn.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // CancellationException 重新抛出,守卫理由同 testConfig
            try {
                val models = AiTranslator.fetchModels(baseUrl, apiKey)
                binding.aiTranslateFetchModelsBtn.isEnabled = true
                if (models.isEmpty()) {
                    Toaster.show(getString(R.string.ai_translate_fetch_models_empty))
                    return@launch
                }
                showModelPicker(models)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.aiTranslateFetchModelsBtn.isEnabled = true
                Toaster.show(getString(R.string.ai_translate_fetch_models_failed, e.message ?: e.toString()))
            }
        }
    }

    private fun showModelPicker(models: List<String>) {
        val ctx = requireContext()
        QMUIDialog.MenuDialogBuilder(ctx)
            .addItems(models.toTypedArray()) { dialog, which ->
                binding.aiTranslateModel.setText(models[which])
                dialog.dismiss()
            }
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
