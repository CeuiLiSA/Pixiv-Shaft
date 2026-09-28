package ceui.pixiv.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentViewerDismissTuningBinding
import ceui.lisa.utils.Local
import ceui.lisa.utils.Settings
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.bumptech.glide.Glide
import com.hjq.toast.Toaster
import java.util.Locale
import kotlin.math.roundToInt

/** 三个阈值的快照：退出时与当前值逐字段比较，判断有没有未保存改动。 */
private data class ViewerDismissSnapshot(
    val distance: Float,
    val velocity: Float,
    val scaleShrink: Float,
    val onlyAtMinScale: Boolean,
)

/**
 * 「大图拖动退出控制」测试页（设置 → 浏览设置）。
 *
 * 灵敏度很主观，光看数字调不出来，所以这里给一条完整的试手感闭环：点测试图进
 * [ViewerDismissTestActivity]（与真实二级大图页共用同一套手势与转场），上下拖一拖，
 * 回来再拖调节条，直到顺手为止。
 *
 * 调节条改动**即时写内存态**（[Shaft.sSettings]），所以点图进去立刻按新值生效；只有点「保存」
 * 才落盘（[Local.setSettings]）。因此有未保存改动时返回会质询，选「不保存」会把内存态回滚到
 * 进入页面时的快照——否则本次会话里真实大图页会继续用未保存的值。这套「脏值判断 + 返回拦截
 * 只在脏时 enabled」的做法照抄 [AiTranslateSettingsFragment]。
 */
class ViewerDismissTuningFragment : Fragment(R.layout.fragment_viewer_dismiss_tuning) {

    private val binding by viewBinding(FragmentViewerDismissTuningBinding::bind)

    /** 进入页面时的已保存快照。 */
    private var saved: ViewerDismissSnapshot? = null

    /** 当前测试用图在 assets 下的相对路径。 */
    private var testAssetPath: String? = null

    /**
     * 系统返回 / 手势：有未保存改动先弹确认框（保存 / 不保存 / 取消）。
     *
     * enabled 只在「有未保存改动」时为 true：常开会让系统以为 app 要自己处理返回，
     * 整页的预测式返回动画就没了。
     */
    private val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = handleBackPressed()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar()
        setUpTestImage()
        bindSliders()
        bindDismissPrecondition()
        binding.viewerDismissResetBtn.setOnClickListener { resetToDefault() }
        binding.viewerDismissSaveBtn.setOnClickListener { save() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        // 改动住在内存 Settings 里、不随 Fragment 重建消失：旋转 / 切深色后若拿当前值当快照，
        // 未保存改动会被当成已保存，返回不再质询，还会被其他设置页的 Local.setSettings 顺手落盘。
        saved = savedInstanceState?.restoreSnapshot() ?: currentSnapshot()
        refreshBackCallback()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saved?.let { snap ->
            outState.putFloatArray(
                STATE_SAVED_VALUES,
                floatArrayOf(snap.distance, snap.velocity, snap.scaleShrink),
            )
            outState.putBoolean(STATE_SAVED_ONLY_AT_MIN_SCALE, snap.onlyAtMinScale)
        }
    }

    private fun Bundle.restoreSnapshot(): ViewerDismissSnapshot? {
        val values = getFloatArray(STATE_SAVED_VALUES)?.takeIf { it.size == 3 } ?: return null
        return ViewerDismissSnapshot(
            values[0],
            values[1],
            values[2],
            getBoolean(STATE_SAVED_ONLY_AT_MIN_SCALE),
        )
    }

    private fun setUpToolbar() {
        // 全 app 统一的 toolbar_layout：品牌色 + 白字，标题 / 返回在这里接线。
        binding.toolbarLayout.toolbarTitle.setText(R.string.viewer_dismiss_tuning_title)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener { handleBackPressed() }
    }

    /**
     * 测试用图直接复用内置榜打包进 APK 的方形插画（`assets/prime_square/`，1200×1200 WebP）。
     * 方形图适应屏幕后整张都在视口内，上下都没有可滚动余量，一上手就能拖到退出，
     * 正好拿来量「拖多远算退出」。
     */
    private fun setUpTestImage() {
        val names =
            runCatching { requireContext().assets.list(PRIME_SQUARE_DIR) }
                .getOrNull()
                ?.filter { it.endsWith(".webp") }
                .orEmpty()
                .sorted()
        val pick = names.firstOrNull()
        if (pick == null) {
            binding.viewerDismissTestImage.visibility = View.GONE
            return
        }
        testAssetPath = "$PRIME_SQUARE_DIR/$pick"
        Glide.with(this).load("file:///android_asset/$testAssetPath").into(binding.viewerDismissTestImage)
        binding.viewerDismissTestImage.setOnClickListener { openTestViewer() }
    }

    private fun openTestViewer() {
        val path = testAssetPath ?: return
        val image = binding.viewerDismissTestImage
        val loc = IntArray(2)
        image.getLocationOnScreen(loc)
        val enterBounds =
            intArrayOf(loc[0], loc[1], loc[0] + image.width, loc[1] + image.height)
        val intent =
            Intent(requireContext(), ViewerDismissTestActivity::class.java).apply {
                putExtra(ViewerDismissTestActivity.EXTRA_ASSET_PATH, path)
                putExtra(ViewerDismissTestActivity.EXTRA_ENTER_BOUNDS, enterBounds)
            }
        startActivity(intent)
    }

    private fun bindSliders() {
        // 拖动距离：屏高的 5% ~ 50%，每档 1%。越小越灵敏。
        bindSlider(
            binding.viewerDismissDistanceSeek,
            binding.viewerDismissDistanceValue,
            min = 5f,
            max = 50f,
            steps = 45,
            initial = Shaft.sSettings.viewerDismissDistance * 100f,
            render = { "${it.roundToInt()}%" },
        ) { percent -> Shaft.sSettings.viewerDismissDistance = percent / 100f }

        // 外甩速度：300 ~ 4000 dp/s，每档 100。越小越灵敏。
        bindSlider(
            binding.viewerDismissVelocitySeek,
            binding.viewerDismissVelocityValue,
            min = 300f,
            max = 4000f,
            steps = 37,
            initial = Shaft.sSettings.viewerDismissVelocity,
            render = { it.roundToInt().toString() },
        ) { velocity -> Shaft.sSettings.viewerDismissVelocity = velocity }

        // 缩放反馈：拖满时缩到 1 - v，0 ~ 0.6，每档 0.05。越大反馈越强。
        bindSlider(
            binding.viewerDismissScaleSeek,
            binding.viewerDismissScaleValue,
            min = 0f,
            max = 0.6f,
            steps = 12,
            initial = Shaft.sSettings.viewerDismissScaleShrink,
            render = { String.format(Locale.US, "%.2f", it) },
        ) { shrink -> Shaft.sSettings.viewerDismissScaleShrink = shrink }
    }

    /**
     * 把一条 SeekBar 绑成「档位滑条」：内部 progress 是 0..[steps] 的档位，对外换算成
     * [min]..[max] 的实际值。[render] 负责显示文案，[onChange] 只在用户拖动时回调。
     */
    private fun bindSlider(
        seek: SeekBar,
        valueText: TextView,
        min: Float,
        max: Float,
        steps: Int,
        initial: Float,
        render: (Float) -> String,
        onChange: (Float) -> Unit,
    ) {
        seek.max = steps
        val normalized = ((initial - min) / (max - min)).coerceIn(0f, 1f)
        // roundToInt 而不是 toInt：单精度近似值截断会掉一档（同 ReaderSettingsPanel #1038）。
        seek.progress = (normalized * steps).roundToInt()
        fun currentValue(): Float = min + (seek.progress.toFloat() / steps) * (max - min)
        fun refresh() {
            valueText.text = render(currentValue())
        }
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    refresh()
                    if (fromUser) {
                        onChange(currentValue())
                        refreshBackCallback()
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar) = Unit

                override fun onStopTrackingTouch(s: SeekBar) = Unit
            },
        )
        refresh()
    }

    /** 「放大大图后禁用拖动退出」：开启后只有处在打开时的初始缩放才允许起手竖向拖拽退出。 */
    private fun bindDismissPrecondition() {
        binding.viewerDismissOnlyAtMinScale.isChecked = Shaft.sSettings.isViewerDismissOnlyAtMinScale
        binding.viewerDismissOnlyAtMinScale.setOnCheckedChangeListener { _, isChecked ->
            Shaft.sSettings.isViewerDismissOnlyAtMinScale = isChecked
            refreshBackCallback()
        }
    }

    /** 恢复默认：三个值回到 DragDismissLayout 出厂手感，滑条同步回对应档位，开关一并关闭。 */
    private fun resetToDefault() {
        Shaft.sSettings.viewerDismissDistance = Settings.VIEWER_DISMISS_DISTANCE_DEFAULT
        Shaft.sSettings.viewerDismissVelocity = Settings.VIEWER_DISMISS_VELOCITY_DEFAULT
        Shaft.sSettings.viewerDismissScaleShrink = Settings.VIEWER_DISMISS_SCALE_SHRINK_DEFAULT
        Shaft.sSettings.isViewerDismissOnlyAtMinScale = false
        binding.viewerDismissOnlyAtMinScale.isChecked = false
        // 重新绑定会按当前（已回默认的）值摆好档位；setProgress 触发的是 fromUser=false，
        // 不会反向写回 Settings。
        bindSliders()
        refreshBackCallback()
    }

    private fun currentSnapshot(): ViewerDismissSnapshot =
        ViewerDismissSnapshot(
            Shaft.sSettings.viewerDismissDistance,
            Shaft.sSettings.viewerDismissVelocity,
            Shaft.sSettings.viewerDismissScaleShrink,
            Shaft.sSettings.isViewerDismissOnlyAtMinScale,
        )

    private fun isDirty(): Boolean = saved?.let { it != currentSnapshot() } ?: false

    private fun refreshBackCallback() {
        if (view == null) return
        backCallback.isEnabled = isDirty()
    }

    private fun save(): Boolean {
        Local.setSettings(Shaft.sSettings)
        saved = currentSnapshot()
        refreshBackCallback()
        Toaster.show(getString(R.string.viewer_dismiss_saved))
        return true
    }

    /** 统一的返回处理入口：有未保存改动 → 弹窗；否则退出。 */
    private fun handleBackPressed() {
        if (isDirty()) {
            showUnsavedDialog()
        } else {
            exitPage()
        }
    }

    private fun showUnsavedDialog() {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(getString(R.string.viewer_dismiss_unsaved_title))
            .setMessage(getString(R.string.viewer_dismiss_unsaved_message))
            .addAction(android.R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(getString(R.string.viewer_dismiss_unsaved_discard)) { d, _ ->
                d.dismiss()
                discardAndExit()
            }
            .addAction(
                0,
                getString(R.string.viewer_dismiss_unsaved_save),
                WitDialogAction.ACTION_PROP_NEGATIVE,
            ) { d, _ ->
                d.dismiss()
                if (save()) exitPage()
            }
            .show()
    }

    /**
     * 放弃改动：调节条是即时写内存态的，所以这里必须把内存 Settings 回滚到进入页面时的快照，
     * 否则本次会话里真实大图页会继续用未保存的值。
     */
    private fun discardAndExit() {
        saved?.let { snap ->
            Shaft.sSettings.viewerDismissDistance = snap.distance
            Shaft.sSettings.viewerDismissVelocity = snap.velocity
            Shaft.sSettings.viewerDismissScaleShrink = snap.scaleShrink
            Shaft.sSettings.isViewerDismissOnlyAtMinScale = snap.onlyAtMinScale
        }
        exitPage()
    }

    /** 先关掉自己的拦截再走系统返回（放弃改动时 dirty 仍为 true），避免二次进入确认逻辑。 */
    private fun exitPage() {
        backCallback.isEnabled = false
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private companion object {
        const val PRIME_SQUARE_DIR = "prime_square"
        const val STATE_SAVED_VALUES = "viewer_dismiss_saved_values"
        const val STATE_SAVED_ONLY_AT_MIN_SCALE = "viewer_dismiss_saved_only_at_min_scale"
    }
}
