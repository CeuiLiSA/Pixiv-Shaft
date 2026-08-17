package ceui.pixiv.ui.bookmark

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.SheetSelectTagBinding
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import ceui.pixiv.utils.screenHeight
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * 「按标签收藏」的 MD3-Expressive bottom sheet 宿主。
 *
 * 取代原先 `startActivity(TemplateActivity + EXTRA_FRAGMENT="按标签收藏")` 那条整页路由——
 * 从瀑布流卡 / 详情页长按爱心进来，弹一张 activity 从右侧 push 进来在交互上太重（收藏是个
 * 轻量、就地完成的动作，不该离开当前页）。
 *
 * 职责切分：本类只管壳（把手 / 标题 / 添加标签 / 私密开关 / 提交），标签列表和全部业务逻辑
 * 仍在 [SelectTagFeedFragment]（同义词自动勾选 issue #904、addTag、submitStar 一行没动），
 * 以 child fragment 塞进 `tag_list_container`。
 *
 * 主题上刻意不套 `Theme.Material3.*`：本 App 主题继承 QMUI，套上去 `colorPrimary` 会退回
 * MD3 基线紫、跟用户在设置里选的强调色打架。强调色统一走 [V3Palette] 在这里动态 tint。
 * dialog 的 theme overlay 必须是 `ThemeOverlay.App.BottomSheetDialog.*`（走老 design 库的
 * `Widget.Design.BottomSheet.Modal`）——换成 Material3 的 bottomSheetStyle 会因本 App 主题
 * 缺 MD3 motion 属性在 inflate 时直接崩，详见 styles.xml 里那段注释。
 */
class SelectTagBottomSheet : BottomSheetDialogFragment() {

    // edgeToEdge：让 window 画到导航栏底下，root 的圆角背景才能延伸进底部 safe area。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetSelectTagBinding? = null
    private val binding get() = _binding!!

    private val palette by lazy { V3Palette.from(requireContext()) }

    private val illustID: Int by lazy { arguments?.getInt(Params.ILLUST_ID) ?: 0 }
    private val type: String by lazy { arguments?.getString(Params.DATA_TYPE) ?: Params.TYPE_ILLUST }
    private val tagNamesArg: Array<String>? by lazy { arguments?.getStringArray(Params.TAG_NAMES) }

    /** 列表侧的业务门面。child fragment 由本类装配，取不到说明还没 commit 完，调用方自行短路。 */
    private val listFragment: SelectTagFeedFragment?
        get() = childFragmentManager.findFragmentById(R.id.tag_list_container) as? SelectTagFeedFragment

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetSelectTagBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyAccent()

        // 私密开关初值取设置（对齐 legacy FragmentSB / 原 SelectTagFeedFragment）。
        binding.isPrivate.isChecked = Shaft.sSettings.isPrivateStar
        binding.btnAddTag.setOnClickListener { listFragment?.showAddTagDialog() }
        binding.submitArea.setOnClickListener {
            // isAdded 守卫：submitStar 的 Rx 链不随生命周期 dispose（沿用 legacy），请求在途时
            // activity 重建（旋转/深色切换/分屏）会让本实例先 detach，此时 dismiss 走到
            // getParentFragmentManager() 直接抛 IllegalStateException。手动划掉 sheet 的场景
            // 不受影响（mDismissed 已置位，dismiss 自己会短路）。
            listFragment?.submitStar(binding.isPrivate.isChecked) {
                if (isAdded) dismissAllowingStateLoss()
            }
        }

        // 只在首次创建时装配；重建（旋转 / 进程恢复）时 FragmentManager 自己会带回来。
        if (savedInstanceState == null && listFragment == null) {
            childFragmentManager.commit {
                replace(
                    R.id.tag_list_container,
                    SelectTagFeedFragment.newInstance(illustID, type, tagNamesArg),
                )
            }
        }
    }

    /**
     * 把用户选的强调色刷到两颗胶囊按钮和私密开关上：
     * - 提交 = 实心强调色 + 白字（主操作）；
     * - 添加标签 = 同色 10% 淡底 + 强调色文字（次操作，Expressive 的 tonal 层级）；
     * - 开关选中态走强调色。
     *
     * 用代码 tint 而不是主题属性，理由见类注释（App 主题非 Material3，且强调色用户可换）。
     */
    private fun applyAccent() {
        // 主操作：filled 按钮实心强调色 + 白字。
        binding.submitArea.backgroundTintList = ColorStateList.valueOf(palette.primary)
        binding.submitArea.setTextColor(Color.WHITE)

        // 次操作：tonal 按钮用同色淡底 + 强调色文字（MD3 的 secondary container 层级）。
        binding.btnAddTag.backgroundTintList = ColorStateList.valueOf(palette.alpha15)
        binding.btnAddTag.setTextColor(palette.textAccent)

        // MaterialSwitch 的选中态在 thumb/track 上。未选中态留给 MD3 默认（跟随日夜），
        // 只把选中态染成用户强调色。
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        )
        binding.isPrivate.thumbTintList = ColorStateList(
            states, intArrayOf(Color.WHITE, palette.textSecondary),
        )
        binding.isPrivate.trackTintList = ColorStateList(
            states, intArrayOf(palette.primary, palette.alpha15),
        )
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.apply {
            skipCollapsed = true
            // 标签列表通常十几到几十行，给足高度；仍留一线底图做「这是一张浮层」的暗示。
            maxHeight = (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        // sheet 容器透明 + 内容背景铺进底部 safe area：圆角与配色全由 root 的
        // bg_m3e_sheet_top 负责，否则主题默认 sheet 底会盖在圆角外露白。
        makeSheetTransparentAndFillNavBar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SelectTagBottomSheet"

        private const val MAX_HEIGHT_FRACTION = 0.88F

        /**
         * fragment 侧入口。`tagNames` 是本作品自带的标签，用于「默认全选」和同义词匹配（issue #904）。
         *
         * 走宿主 **Activity** 的 FragmentManager 而不是 childFragmentManager：sheet 是模态浮层，
         * 生命周期挂在页面上即可；更要紧的是 [showFrom] 只解得出 activity，两条路径必须落在**同一个**
         * FragmentManager 上，否则下面那道 TAG 去重闸互相看不见——同一页里两条入口都可达时
         * （如小说阅读器长按走 showFrom、同页其它入口走 fragment 路径）会叠出两张 sheet。
         */
        @JvmStatic
        fun show(host: Fragment, illustId: Int, type: String, tagNames: Array<String>?) {
            val activity = host.activity ?: return
            show(activity.supportFragmentManager, illustId, type, tagNames)
        }

        /**
         * 只拿得到 [Context] / [View] 的调用点用（legacy Java adapter、feeds 渲染器里的
         * `sender.context`）。
         */
        @JvmStatic
        fun showFrom(context: Context, illustId: Int, type: String, tagNames: Array<String>?) {
            val activity = context.findFragmentActivity()
            if (activity == null) {
                // 例如用 application context inflate 出来的 View：原先 startActivity 至少会崩或弹出来，
                // 现在会表现为「长按毫无反应」。不记一笔就无从排查。
                Timber.w("[SelectTagBottomSheet] no FragmentActivity in context chain, skip. ctx=$context")
                return
            }
            show(activity.supportFragmentManager, illustId, type, tagNames)
        }

        /**
         * 真正的展示逻辑。重复 show 会被 [FragmentManager.findFragmentByTag] 挡掉——长按爱心
         * 容易连点，没这道闸会叠出好几张 sheet。
         */
        private fun show(
            fm: FragmentManager,
            illustId: Int,
            type: String,
            tagNames: Array<String>?,
        ) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            SelectTagBottomSheet().apply {
                arguments = Bundle().apply {
                    putInt(Params.ILLUST_ID, illustId)
                    putString(Params.DATA_TYPE, type)
                    putStringArray(Params.TAG_NAMES, tagNames)
                }
            }.show(fm, TAG)
        }

        private fun Context.findFragmentActivity(): FragmentActivity? {
            var ctx: Context? = this
            while (ctx != null) {
                if (ctx is FragmentActivity) return ctx
                ctx = (ctx as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}
