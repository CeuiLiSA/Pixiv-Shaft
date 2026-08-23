package ceui.pixiv.ui.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import ceui.lisa.R
import ceui.lisa.databinding.SheetAccountActionsBinding
import ceui.loxia.User
import ceui.lisa.utils.GlideUtil
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 账号管理页的「账号操作」MD3-Expressive bottom sheet：切到这个账号 / 导出登录信息 /
 * 复制账号名。由 [AccountSwitchV3Fragment] 的行尾 ⋮ 打开。
 *
 * 取代 legacy 那里的 QMUI [ceui.pixiv.witstudio.dialog.WitDialog.MenuDialogBuilder] ——
 * 方角灰底的 AppCompat 弹窗压在一页 MD3-E 卡片上非常突兀，且它只能给纯文字条目，
 * 表达不了「切号是主操作，另外两条是次操作」。
 *
 * 本 sheet **不碰任何账号数据**：只回传用户选了哪一项（[setFragmentResult]），
 * 真正的切号 / 导出 / 复制都还在 [AccountSwitchV3Fragment] 里执行。sheet 与 fragment
 * 生命周期不同（切号会重启整个 app），把写操作留在宿主页面一处收口更好排查。
 */
class AccountActionsSheet : BottomSheetDialogFragment() {

    // edgeToEdge：让 window 画到导航栏底下，root 的圆角背景才能延伸进底部 safe area。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetAccountActionsBinding? = null
    private val binding get() = _binding!!

    private val user: User? by lazy {
        @Suppress("DEPRECATION")
        arguments?.getSerializable(KEY_USER) as? User
    }

    private val isCurrent: Boolean get() = arguments?.getBoolean(KEY_IS_CURRENT) == true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetAccountActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val user = user ?: run {
            dismissAllowingStateLoss()
            return
        }

        binding.sheetName.text = user.name
        binding.sheetHandle.text = listOfNotNull(
            user.account?.takeIf { it.isNotBlank() }?.let { "@$it" },
            user.mail_address?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifEmpty { getString(R.string.no_mail_address) }
        Glide.with(this)
            .load(GlideUtil.getHead(user))
            .error(R.drawable.no_profile)
            .into(binding.sheetAvatar)

        // 当前账号没什么可"切"的，整行隐掉而不是灰掉——一个点了没反应的条目不如不摆。
        binding.actionSwitch.isVisible = !isCurrent
        // 切号是这张 sheet 的主操作，用主题强调色把它和另外两条次操作分开
        // （root 挂的 Material3 主题会把 colorPrimary 顶成 MD3 基线紫，只能运行时 tint）。
        val palette = V3Palette.from(requireContext())
        binding.actionSwitchIcon.setColorFilter(palette.textAccent)
        binding.actionSwitchLabel.setTextColor(palette.textAccent)

        binding.actionSwitch.setOnClickListener { finishWith(ACTION_SWITCH) }
        binding.actionExport.setOnClickListener { finishWith(ACTION_EXPORT) }
        binding.actionCopy.setOnClickListener { finishWith(ACTION_COPY) }
    }

    private fun finishWith(action: String) {
        setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_ACTION, action)
                putLong(RESULT_USER_ID, user?.id ?: 0L)
            }
        )
        dismissAllowingStateLoss()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        // sheet 容器透明 + 内容背景铺进底部 safe area：圆角与配色全由 root 的
        // bg_m3e_sheet_top 负责，否则主题默认 sheet 底会盖在圆角外、暗色模式露白。
        makeSheetTransparentAndFillNavBar()
    }

    companion object {
        const val TAG = "AccountActionsSheet"

        const val REQUEST_KEY = "account_actions"
        const val RESULT_ACTION = "action"
        const val RESULT_USER_ID = "user_id"

        const val ACTION_SWITCH = "switch"
        const val ACTION_EXPORT = "export"
        const val ACTION_COPY = "copy"

        private const val KEY_USER = "user"
        private const val KEY_IS_CURRENT = "is_current"

        /** 唯一入口。重复 show 由 TAG 挡掉（⋮ 容易连点）。 */
        fun show(fm: FragmentManager, user: User, isCurrent: Boolean) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            AccountActionsSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(KEY_USER, user)
                    putBoolean(KEY_IS_CURRENT, isCurrent)
                }
            }.show(fm, TAG)
        }
    }
}
