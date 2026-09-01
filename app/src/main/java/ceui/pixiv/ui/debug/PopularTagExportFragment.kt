package ceui.pixiv.ui.debug

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.utils.hideKeyboard
import com.blankj.utilcode.util.BarUtils

/**
 * 「标签热度导出」debug 页 —— 纯渲染 + 转发点击,逻辑/状态都在 [PopularTagExportViewModel]。
 * 见 [fragment_popular_tag_export] 布局。颜色全部取自 v3_* 资源(带 values-night),白天黑夜自动适配。
 *
 * 仅在 debug build 的侧边栏「试验性」分区可见(见 MainActivity 的 nav_tag_popular_export 门控)。
 */
class PopularTagExportFragment : Fragment(R.layout.fragment_popular_tag_export) {

    private val viewModel by viewModels<PopularTagExportViewModel>()

    private lateinit var tagInput: EditText
    private lateinit var pagesInput: EditText
    private lateinit var btnRun: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: NestedScrollView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar).apply {
            // EdgeToEdge host:状态栏 inset 走 runtime padding,不用 fitsSystemWindows。
            updatePadding(top = BarUtils.getStatusBarHeight())
            setNavigationOnClickListener { activity?.finish() }
        }

        // 固定底栏垫上导航栏 inset,避免主按钮被系统栏遮住。
        val bottomBar = view.findViewById<View>(R.id.bottom_bar)
        val basePaddingBottom = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = basePaddingBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(bottomBar)

        tagInput = view.findViewById(R.id.tag_input)
        pagesInput = view.findViewById(R.id.pages_input)
        btnRun = view.findViewById(R.id.btn_run)
        logText = view.findViewById(R.id.log_text)
        logScroll = view.findViewById(R.id.log_scroll)

        btnRun.setOnClickListener {
            hideKeyboard()
            val tag = tagInput.text?.toString()?.trim().orEmpty()
            if (tag.isEmpty()) {
                Common.showToast(getString(R.string.tag_popular_export_need_tag))
                return@setOnClickListener
            }
            val pages = pagesInput.text?.toString()?.trim()?.toIntOrNull() ?: DEFAULT_PAGES
            viewModel.export(requireContext(), tag, pages)
        }

        viewModel.running.observe(viewLifecycleOwner) { isRunning ->
            btnRun.isEnabled = !isRunning
            btnRun.alpha = if (isRunning) 0.6f else 1f
            btnRun.setText(if (isRunning) R.string.tag_popular_export_running else R.string.tag_popular_export_run)
        }
        viewModel.log.observe(viewLifecycleOwner) { text ->
            logText.text = text
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    companion object {
        private const val DEFAULT_PAGES = 10
    }
}
