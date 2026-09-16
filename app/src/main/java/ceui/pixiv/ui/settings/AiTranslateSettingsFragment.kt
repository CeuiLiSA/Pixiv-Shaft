package ceui.pixiv.ui.settings

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentAiTranslateSettingsBinding
import ceui.lisa.utils.Local
import ceui.lisa.utils.Settings
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.translate.AiTranslator
import ceui.pixiv.ui.translate.statusText
import com.hjq.toast.Toaster
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** 设置页的可编辑配置快照：退出时与当前 UI 逐字段比较，判断是否有未保存改动。 */
private data class AiTranslateSnapshot(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val prompt: String,
    val thinkingMode: Int,
    val streaming: Boolean,
    val readTimeoutText: String,
)

/**
 * 自定义 AI 翻译设置页（#975）。
 *
 * 配置 OpenAI 兼容接口（base URL + API key + 模型名 + 可选自定义提示词）；启用后
 * 评论翻译与漫画翻译改走该接口（[AiTranslator]），替代内置的 Google web 端点。
 * base URL 可指向任何兼容服务：OpenAI / DeepSeek 等云端，或 Ollama、llama.cpp
 * server（Sakura 模型）等本地部署（本地服务 key 可留空）。
 *
 * 视觉风格与保存/测试交互对齐 [Aria2SettingsFragment]（bg_v3 卡片 + pill 按钮 +
 * toolbar_layout 统一 toolbar）。
 */
class AiTranslateSettingsFragment : Fragment(R.layout.fragment_ai_translate_settings) {

    private val binding by viewBinding(FragmentAiTranslateSettingsBinding::bind)

    private var apiKeyVisible = false
    private var thinkingMode = 0
        set(value) {
            field = value
            refreshBackCallback()
        }
    private val thinkingModeNames by lazy { resources.getStringArray(R.array.ai_translate_thinking_modes) }

    /** 进入页面时的已保存配置快照：退出时与当前 UI 比较判断有没有未保存改动。 */
    private var saved: AiTranslateSnapshot? = null

    /**
     * 系统返回/手势：有未保存改动先弹确认框（保存/不保存/取消）。
     *
     * enabled 只在「有未保存改动」时为 true（[refreshBackCallback] 在每个可编辑项变化时维护）：
     * 常开会让系统以为 app 要自己处理返回，整页的预测式返回动画就没了；没改动时直接放行给
     * 系统，返回手势跟别的页面一样带预览动画。
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = handleBackPressed()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpToolbar()
        loadSettings()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        watchEditsForBackCallback()

        binding.aiTranslateSaveBtn.setOnClickListener { save() }
        binding.aiTranslateTestBtn.setOnClickListener { testConfig() }
        binding.aiTranslateApiKeyToggle.setOnClickListener { toggleApiKeyVisibility() }
        binding.aiTranslateFetchModelsBtn.setOnClickListener { fetchModels() }
        binding.aiTranslateThinkingMode.setOnClickListener { showThinkingModePicker() }

        // 常用服务一键填 base URL;模型名为空时顺手填该服务的常见默认,省一次拉列表
        binding.aiTranslatePresetOpenai.setOnClickListener {
            applyPreset("https://api.openai.com/v1", "gpt-4o-mini")
        }
        binding.aiTranslatePresetDeepseek.setOnClickListener {
            applyPreset("https://api.deepseek.com/v1", "deepseek-v4-flash")
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

    /** 每个参与 [currentSnapshot] 比对的控件一有变化就重算 dirty → 返回拦截的 enabled。 */
    private fun watchEditsForBackCallback() {
        listOf(
            binding.aiTranslateBaseUrl,
            binding.aiTranslateApiKey,
            binding.aiTranslateModel,
            binding.aiTranslatePrompt,
            binding.aiTranslateReadTimeout,
        ).forEach { it.doAfterTextChanged { refreshBackCallback() } }
        binding.aiTranslateEnableSwitch.setOnCheckedChangeListener { _, _ -> refreshBackCallback() }
        binding.aiTranslateStreamingSwitch.setOnCheckedChangeListener { _, _ -> refreshBackCallback() }
        refreshBackCallback()
    }

    private fun refreshBackCallback() {
        if (view == null) return
        backCallback.isEnabled = isDirty()
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
        // 全 app 统一的 toolbar_layout(同设置页 / 榜单页):品牌色 + 白字,标题 / 返回在这里接线。
        binding.toolbarLayout.toolbarTitle.setText(R.string.ai_translate_settings_title)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener {
            handleBackPressed()
        }
    }

    /** 已保存配置 → 快照；读超时存原文，与编辑框逐字比较（保持旧 isDirty 语义）。 */
    private fun Settings.toSnapshot(): AiTranslateSnapshot = AiTranslateSnapshot(
        enabled = isAiTranslateEnabled,
        baseUrl = aiTranslateBaseUrl,
        apiKey = aiTranslateApiKey,
        model = aiTranslateModel,
        prompt = aiTranslatePrompt,
        thinkingMode = aiTranslateThinkingMode.coerceIn(0, thinkingModeNames.lastIndex),
        streaming = isAiTranslateStreaming,
        readTimeoutText = aiTranslateReadTimeoutSeconds.toString(),
    )

    /** 当前编辑框/开关的完整状态，与 [AiTranslateSnapshot] 逐字段比对即知是否有改动。 */
    private fun currentSnapshot(): AiTranslateSnapshot = AiTranslateSnapshot(
        enabled = binding.aiTranslateEnableSwitch.isChecked,
        baseUrl = binding.aiTranslateBaseUrl.text.toString().trim(),
        apiKey = binding.aiTranslateApiKey.text.toString().trim(),
        model = binding.aiTranslateModel.text.toString().trim(),
        prompt = binding.aiTranslatePrompt.text.toString().trim(),
        thinkingMode = thinkingMode,
        streaming = binding.aiTranslateStreamingSwitch.isChecked,
        readTimeoutText = binding.aiTranslateReadTimeout.text.toString().trim(),
    )

    private fun loadSettings() {
        val settings = Shaft.sSettings
        saved = settings.toSnapshot()
        binding.aiTranslateEnableSwitch.isChecked = settings.isAiTranslateEnabled
        binding.aiTranslateBaseUrl.setText(settings.aiTranslateBaseUrl)
        binding.aiTranslateApiKey.setText(settings.aiTranslateApiKey)
        binding.aiTranslateModel.setText(settings.aiTranslateModel)
        binding.aiTranslatePrompt.setText(settings.aiTranslatePrompt)
        thinkingMode = settings.aiTranslateThinkingMode.coerceIn(0, thinkingModeNames.lastIndex)
        binding.aiTranslateThinkingMode.setText(thinkingModeNames[thinkingMode])
        binding.aiTranslateStreamingSwitch.isChecked = settings.isAiTranslateStreaming
        binding.aiTranslateReadTimeout.setText(settings.aiTranslateReadTimeoutSeconds.toString())
    }

    private fun save(): Boolean {
        val enabled = binding.aiTranslateEnableSwitch.isChecked
        val baseUrl = binding.aiTranslateBaseUrl.text.toString().trim()
        val model = binding.aiTranslateModel.text.toString().trim()

        if (enabled && (baseUrl.isEmpty() || model.isEmpty())) {
            Toaster.show(getString(R.string.ai_translate_config_required))
            return false
        }
        if (baseUrl.isNotEmpty() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            Toaster.show(getString(R.string.ai_translate_url_invalid))
            return false
        }
        val readTimeout = binding.aiTranslateReadTimeout.text.toString().toIntOrNull() ?: 120
        if (readTimeout !in 30..600) {
            Toaster.show(getString(R.string.ai_translate_read_timeout_invalid))
            return false
        }

        val settings = Shaft.sSettings
        settings.isAiTranslateEnabled = enabled
        settings.aiTranslateBaseUrl = baseUrl
        settings.aiTranslateApiKey = binding.aiTranslateApiKey.text.toString().trim()
        settings.aiTranslateModel = model
        settings.aiTranslatePrompt = binding.aiTranslatePrompt.text.toString().trim()
        settings.aiTranslateThinkingMode = thinkingMode
        settings.isAiTranslateStreaming = binding.aiTranslateStreamingSwitch.isChecked
        settings.aiTranslateReadTimeoutSeconds = readTimeout
        Local.setSettings(settings)
        saved = settings.toSnapshot()
        refreshBackCallback()
        Toaster.show(getString(R.string.aria2_saved))
        return true
    }

    /** 任何可编辑项与已保存快照不一致即视为有未保存改动。 */
    private fun isDirty(): Boolean = saved != currentSnapshot()

    private fun showUnsavedDialog() {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(getString(R.string.ai_translate_unsaved_title))
            .setMessage(getString(R.string.ai_translate_unsaved_message))
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

    /**
     * 统一的返回处理入口：有未保存改动 → 弹窗；否则退出。
     * 顶部返回按钮和系统返回（dispatcher 回调）都走这里。
     */
    private fun handleBackPressed() {
        Timber.d("AiTranslateSettings: back pressed, dirty=%s", isDirty())
        if (isDirty()) {
            showUnsavedDialog()
        } else {
            exitPage()
        }
    }

    /** 先关掉自己的拦截再走系统返回（放弃改动时 dirty 仍为 true），避免二次进入确认逻辑。 */
    private fun exitPage() {
        backCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun showThinkingModePicker() {
        val ctx = requireContext()
        WitDialog.MenuDialogBuilder(ctx)
            .addItems(thinkingModeNames) { dialog, which ->
                thinkingMode = which
                binding.aiTranslateThinkingMode.setText(thinkingModeNames[which])
                dialog.dismiss()
            }
            .show()
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
        val streaming = binding.aiTranslateStreamingSwitch.isChecked

        val status = binding.aiTranslateTestStatus
        status.visibility = View.VISIBLE
        status.text = getString(R.string.string_translating)
        binding.aiTranslateTestBtn.showProgress()
        binding.aiTranslateTestBtn.isEnabled = false
        val scope = viewLifecycleOwner.lifecycleScope
        scope.launch {
            var testing = true
            // CancellationException 必须重新抛出（测试期间退出页面 → scope 取消）：
            // 吞掉会继续访问已销毁的 binding 直接 crash —— 与 Aria2SettingsFragment 同款守卫。
            try {
                val translated = AiTranslator.testConfig(
                    baseUrl, apiKey, model, prompt,
                    forceStreaming = streaming,
                    onPhase = { phase ->
                        scope.launch {
                            if (testing) status.text = phase.statusText(status.context)
                        }
                    },
                )
                testing = false
                binding.aiTranslateTestBtn.hideProgress()
                binding.aiTranslateTestBtn.isEnabled = true
                status.text = getString(R.string.ai_translate_test_success, translated)
                Toaster.show(getString(R.string.ai_translate_test_success, translated))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 光拦 CancellationException 不够:取消发生时阻塞中的 OkHttp 调用会继续
                // 跑完,最终抛上来的是真实 IO 异常而不是 CancellationException —— 此时
                // view 已销毁,碰 binding 必崩(FragmentViewBindingDelegate requireView)。
                ensureActive()
                testing = false
                binding.aiTranslateTestBtn.hideProgress()
                binding.aiTranslateTestBtn.isEnabled = true
                status.text = getString(R.string.ai_translate_test_failed, e.message ?: e.toString())
                Toaster.show(getString(R.string.ai_translate_test_failed, e.message ?: e.toString()))
            } finally {
                testing = false
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
                ensureActive() // 理由同 testConfig:取消后可能带着真实 IO 异常回来
                binding.aiTranslateFetchModelsBtn.isEnabled = true
                Toaster.show(getString(R.string.ai_translate_fetch_models_failed, e.message ?: e.toString()))
            }
        }
    }

    private fun showModelPicker(models: List<String>) {
        val ctx = requireContext()
        WitDialog.MenuDialogBuilder(ctx)
            .addItems(models.toTypedArray()) { dialog, which ->
                binding.aiTranslateModel.setText(models[which])
                dialog.dismiss()
            }
            .show()
    }

}
