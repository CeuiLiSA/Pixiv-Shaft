package ceui.pixiv.ui.spark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import ceui.pixiv.witstudio.theme.V3Palette
import kotlinx.coroutines.launch

/** Spark 工作台：V3 中性表面 + 主题胶囊，保留聊天/翻译的 HTTP SSE 能力。 */
class SparkAiFragment : Fragment() {
    private val viewModel: SparkAiViewModel by lazy { ViewModelProvider(this)[SparkAiViewModel::class.java] }
    private lateinit var root: View
    private lateinit var transcript: LinearLayout
    private lateinit var input: EditText
    private lateinit var prompt: EditText
    private lateinit var source: EditText
    private lateinit var target: EditText
    private lateinit var chatComposer: View
    private lateinit var emptyState: View

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        root = inflater.inflate(ceui.lisa.R.layout.fragment_spark_ai, container, false)
        val palette = V3Palette.from(requireContext())
        root.setBackgroundColor(requireContext().getColor(ceui.lisa.R.color.v3_bg))
        root.findViewById<TextView>(ceui.lisa.R.id.spark_eyebrow).setTextColor(palette.textAccent)
        root.findViewById<TextView>(ceui.lisa.R.id.spark_title).setTextColor(requireContext().getColor(ceui.lisa.R.color.v3_text_1))
        root.findViewById<TextView>(ceui.lisa.R.id.spark_subtitle).setTextColor(palette.textSecondary)
        transcript = root.findViewById(ceui.lisa.R.id.spark_transcript)
        input = root.findViewById(ceui.lisa.R.id.spark_input)
        source = root.findViewById(ceui.lisa.R.id.spark_source)
        target = root.findViewById(ceui.lisa.R.id.spark_target)
        prompt = root.findViewById(ceui.lisa.R.id.spark_prompt)
        chatComposer = root.findViewById(ceui.lisa.R.id.spark_composer)
        emptyState = root.findViewById(ceui.lisa.R.id.spark_empty_state)
        installSafeAreaInsets()
        root.findViewById<View>(ceui.lisa.R.id.spark_tab_chat).setOnClickListener { showChat() }
        root.findViewById<View>(ceui.lisa.R.id.spark_tab_translate).setOnClickListener { showTranslate() }
        root.findViewById<View>(ceui.lisa.R.id.spark_chat_send).setOnClickListener { if (viewModel.state.value.generating) stopGeneration() else sendChat() }
        root.findViewById<View>(ceui.lisa.R.id.spark_translate_send).setOnClickListener { sendTranslation() }
        root.findViewById<View>(ceui.lisa.R.id.spark_clear).setOnClickListener { clearConversation() }
        root.findViewById<View>(ceui.lisa.R.id.spark_translate_clear).setOnClickListener { clearTranslation() }
        root.findViewById<EditText>(ceui.lisa.R.id.spark_input).setOnFocusChangeListener { _, hasFocus -> if (hasFocus) root.findViewById<ScrollView>(ceui.lisa.R.id.spark_scroll).post { root.findViewById<ScrollView>(ceui.lisa.R.id.spark_scroll).fullScroll(View.FOCUS_DOWN) } }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect { renderState(it) } }
        }
        showChat()
        return root
    }

    /**
     * MainActivity is edge-to-edge and its content host forwards the bottom-bar
     * height as a navigation inset. Keep the lab's own rhythm and add only the
     * system-owned safe areas on top of it. The IME wins while the composer is
     * focused, so the send action stays above the keyboard as well.
     */
    private fun installSafeAreaInsets() {
        val scroll = root.findViewById<ScrollView>(ceui.lisa.R.id.spark_scroll)
        val baseScrollTop = scroll.paddingTop
        val baseScrollBottom = scroll.paddingBottom
        val baseComposerBottom = chatComposer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottomSafe = maxOf(bars.bottom, ime)
            scroll.updatePadding(top = baseScrollTop + bars.top, bottom = baseScrollBottom + bottomSafe)
            chatComposer.updatePadding(bottom = baseComposerBottom + bottomSafe)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyMode(chat: Boolean) {
        val p = V3Palette.from(requireContext())
        val chatButton = root.findViewById<TextView>(ceui.lisa.R.id.spark_tab_chat)
        val translateButton = root.findViewById<TextView>(ceui.lisa.R.id.spark_tab_translate)
        chatButton.background = if (chat) p.pillPrimary(999f) else p.pillSecondary(999f, 1)
        translateButton.background = if (!chat) p.pillPrimary(999f) else p.pillSecondary(999f, 1)
        chatButton.setTextColor(if (chat) p.onPrimary else p.textSecondary)
        translateButton.setTextColor(if (!chat) p.onPrimary else p.textSecondary)
        val translateSend = root.findViewById<Button>(ceui.lisa.R.id.spark_translate_send)
        translateSend.background = p.pillPrimary(999f)
        translateSend.setTextColor(p.onPrimary)
        translateSend.isAllCaps = false
        root.findViewById<ImageButton>(ceui.lisa.R.id.spark_chat_send).background = p.pillPrimary(999f)
        root.findViewById<Button>(ceui.lisa.R.id.spark_clear).apply {
            background = p.pillSecondary(999f, 1)
            setTextColor(p.textAccent)
        }
        chatComposer.visibility = if (chat) View.VISIBLE else View.GONE
        root.findViewById<Button>(ceui.lisa.R.id.spark_translate_clear).apply {
            background = p.pillSecondary(999f, 1)
            setTextColor(p.textAccent)
        }
    }

    private fun showChat() {
        root.findViewById<View>(ceui.lisa.R.id.spark_chat_panel).visibility = View.VISIBLE
        root.findViewById<View>(ceui.lisa.R.id.spark_translate_panel).visibility = View.GONE
        applyMode(true)
        renderConversation()
    }

    private fun showTranslate() {
        root.findViewById<View>(ceui.lisa.R.id.spark_chat_panel).visibility = View.GONE
        root.findViewById<View>(ceui.lisa.R.id.spark_translate_panel).visibility = View.VISIBLE
        applyMode(false)
    }

    private fun renderConversation() {
        transcript.removeAllViews()
        viewModel.state.value.messages.forEach { message -> addBubble(message.user, message.text) }
        val hasMessages = viewModel.state.value.messages.isNotEmpty()
        emptyState.visibility = if (hasMessages) View.GONE else View.VISIBLE
                transcript.visibility = if (hasMessages) View.VISIBLE else View.GONE
    }

    private fun addBubble(user: Boolean, value: String): TextView {
        val textView = TextView(requireContext()).apply {
            text = value; textSize = 15f; setTextColor(requireContext().getColor(ceui.lisa.R.color.v3_text_1))
            setPadding(16, 14, 16, 14)
            background = GradientDrawable().apply { setColor(if (user) V3Palette.from(requireContext()).alpha20 else requireContext().getColor(ceui.lisa.R.color.v3_surface_2)); cornerRadius = 22f }
            contentDescription = if (user) getString(ceui.lisa.R.string.spark_lab_you) else getString(ceui.lisa.R.string.spark_lab_assistant)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        }
        textView.setOnLongClickListener {
            if (!user && textView.text.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(ceui.lisa.R.string.spark_lab_assistant), textView.text))
                toast(getString(ceui.lisa.R.string.spark_lab_copied)); true
            } else false
        }
        transcript.addView(textView)
        return textView
    }

    private fun clearConversation() { viewModel.clearChat() }

    private fun clearTranslation() { viewModel.clearTranslation() }

    private fun sendChat() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        viewModel.sendChat(text); input.setText("")
    }

    private fun stopGeneration() { viewModel.stopChat() }

    private fun sendTranslation() {
        val text = source.text.toString().trim()
        if (text.isEmpty()) { toast(getString(ceui.lisa.R.string.spark_lab_required)); return }
        viewModel.sendTranslation(text, target.text.toString(), prompt.text.toString())
    }

    private fun renderState(state: SparkUiState) {
        if (!::transcript.isInitialized) return
        if (state.messages.isEmpty()) { renderConversation(); } else {
            if (transcript.childCount != state.messages.size) {
                transcript.removeAllViews(); state.messages.forEach { addBubble(it.user, it.text) }
            } else {
                (transcript.getChildAt(transcript.childCount - 1) as? TextView)?.text = state.messages.last().text
            }
            transcript.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
        root.findViewById<TextView>(ceui.lisa.R.id.spark_translation_text).text = state.translation.ifBlank { getString(ceui.lisa.R.string.spark_lab_result_empty) }
        root.findViewById<Button>(ceui.lisa.R.id.spark_translate_send).apply {
            isEnabled = !state.translating
            text = getString(if (state.translating) ceui.lisa.R.string.spark_lab_translating else ceui.lisa.R.string.spark_lab_translate_send)
        }
        root.findViewById<ImageButton>(ceui.lisa.R.id.spark_chat_send).apply {
            setImageResource(if (state.generating) ceui.lisa.R.drawable.spark_ic_stop else ceui.lisa.R.drawable.chat_ic_send)
            contentDescription = getString(if (state.generating) ceui.lisa.R.string.spark_lab_stop else ceui.lisa.R.string.spark_lab_send)
        }
        state.error?.let { toast(getString(if (it == "missing_key") ceui.lisa.R.string.spark_lab_missing_key else ceui.lisa.R.string.spark_lab_error)); viewModel.consumeError() }
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    override fun onDestroyView() { super.onDestroyView() }
}
