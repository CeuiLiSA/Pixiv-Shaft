package ceui.pixiv.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import ceui.lisa.databinding.SheetCustomThemeColorBinding
import ceui.pixiv.ui.search.v3.V3BottomSheetBase
import ceui.pixiv.utils.setOnClick

/**
 * 自定义主题色 picker（issue #1014）。HSV 方块 + 色相条 + HEX 输入框三者互为镜像：
 * 动任意一个，另外两个和预览块立刻跟上。
 *
 * 结果通过 [KEY_HEX] 回传给 [ThemeColorFeedFragment]，由它写盘 + 重启进程 —— 这里不碰
 * Settings，picker 只负责「选出一个色」。
 *
 * HSV 是这里唯一的状态源（[hue]/[saturation]/[value]），不存 int 色值：纯黑/纯白往回解 HSV
 * 时色相会塌成 0，用户拖到底再拖回来色相就丢了。
 */
class CustomThemeColorSheet : V3BottomSheetBase() {

    private var _binding: SheetCustomThemeColorBinding? = null
    private val binding get() = _binding!!

    private var hue = 0f
    private var saturation = 1f
    private var value = 1f

    /** 输入框自己触发的回写不该再去改输入框，否则光标乱跳。 */
    private var updatingHexInput = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCustomThemeColorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCancel.setTextColor(palette.textAccent)
        binding.btnConfirm.setTextColor(palette.textAccent)

        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor(), hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]

        binding.svSquare.hue = hue
        binding.svSquare.setSaturationValue(saturation, value)
        binding.hueSlider.setHueSilently(hue)

        binding.hueSlider.onHueChanged = { newHue ->
            hue = newHue
            binding.svSquare.hue = newHue
            syncPreviewAndInput()
        }
        binding.svSquare.onSaturationValueChanged = { s, v ->
            saturation = s
            value = v
            syncPreviewAndInput()
        }
        binding.hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingHexInput) return
                // 敲到一半的半截色值（"#6"、"#68b"…）不该把方块拽走，只有完整合法的才认
                val color = CustomThemeColor.normalize(s?.toString())
                    ?.let(Color::parseColor) ?: return
                val hsv3 = FloatArray(3)
                Color.colorToHSV(color, hsv3)
                // 灰阶色（S=0）解出的 hue 恒为 0，会把用户刚调好的色相清掉，这里留着不动
                if (hsv3[1] > 0f) hue = hsv3[0]
                saturation = hsv3[1]
                value = hsv3[2]
                binding.svSquare.hue = hue
                binding.svSquare.setSaturationValue(saturation, value)
                binding.hueSlider.setHueSilently(hue)
                renderPreview()
            }
        })

        binding.btnCancel.setOnClick { dismissAllowingStateLoss() }
        binding.btnConfirm.setOnClick { commit() }

        syncPreviewAndInput()
    }

    /**
     * 打开时的初始色：调用方显式给了 [ARG_INITIAL_HEX] 就用它（标签译文颜色模式传的是当前译文色，
     * 不能拿主题的自定义色顶上）；否则优先用户已存的自定义主题色，其次当前正在用的主题色 ——
     * 从当前主题色起步，用户微调一下就能得到「和现在差不多但更喜欢一点」的色，比每次都从纯红开始有用。
     */
    private fun initialColor(): Int =
        CustomThemeColor.normalize(arguments?.getString(ARG_INITIAL_HEX))?.let(Color::parseColor)
            ?: CustomThemeColor.savedColor()
            ?: Color.parseColor(ThemeColorCatalog.hexOf(ceui.lisa.activities.Shaft.sSettings.themeIndex))

    private fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, saturation, value))

    private fun syncPreviewAndInput() {
        renderPreview()
        updatingHexInput = true
        val hex = CustomThemeColor.toHex(currentColor())
        binding.hexInput.setText(hex)
        // setText 会把光标打回 0；输入框正被 focus 时（用户敲完又去拖滑条）光标要留在末尾
        binding.hexInput.setSelection(hex.length)
        updatingHexInput = false
    }

    private fun renderPreview() {
        val color = currentColor()
        binding.preview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f * resources.displayMetrics.density
            setColor(color)
        }
        binding.previewHex.text = CustomThemeColor.toHex(color)
        binding.previewHex.setTextColor(contrastingTextColor(color))
    }

    private fun commit() {
        val hex = CustomThemeColor.toHex(currentColor())
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(KEY_HEX to hex))
        dismissAllowingStateLoss()
    }

    companion object {
        const val REQUEST_KEY = "custom_theme_color"
        const val KEY_HEX = "hex"
        private const val ARG_INITIAL_HEX = "initial_hex"

        /** 指定打开时的初始色（`#RRGGBB`）；null / 非法时回落 [initialColor] 的默认规则。 */
        fun newInstance(initialHex: String?): CustomThemeColorSheet =
            CustomThemeColorSheet().apply {
                arguments = bundleOf(ARG_INITIAL_HEX to initialHex)
            }
    }
}
