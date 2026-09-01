package ceui.pixiv.ui.flag

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.databinding.FragmentFlagDescBinding
import ceui.lisa.utils.Common
import ceui.pixiv.api.Client
import ceui.pixiv.ui.common.launchSuspend
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.hideKeyboard
import ceui.pixiv.utils.setOnClick
import com.blankj.utilcode.util.BarUtils
import kotlinx.coroutines.delay

/**
 * 举报第二步：违规描述 + 提交（MD3-E 纯 XML）。提交走服务端 topic_id（[FlagReasonFragment]
 * 动态拉的 /v1/illust/report/topic-list 里选的那条），不再是本地固定的 type_of_problem
 * 字符串枚举。不需要 PixivFragment；也不需要一个只装 `desc: LiveData<String>` 的 ViewModel
 * 做双向数据绑定——描述直接从 EditText 读，旋转丢失草稿是可接受的成本（同类表单页
 * EmailBackupV3Fragment 也是这么处理的）。
 */
class FlagDescFragment : Fragment(R.layout.fragment_flag_desc) {

    private val binding by viewBinding(FragmentFlagDescBinding::bind)

    private val topicId: Int by lazy { requireArguments().getInt(FlagTopicIdKey) }
    private val topicTitle: String by lazy { requireArguments().getString(FlagTopicTitleKey).orEmpty() }

    // 见 FlagReasonFragment 里的注释：必须是 Long，历史上的 Int/Long Bundle 类型不匹配
    // 导致这里读出来恒为 0，提交举报从未真正生效过。
    private val flagObjectId: Long by lazy { requireArguments().getLong(FlagObjectIdKey) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbarTitle.text = getString(R.string.flag_desc)

        binding.flagType.text = topicTitle

        binding.submitFlag.setOnClick {
            val reasonDesc = binding.inputBox.text?.toString().orEmpty()
            if (reasonDesc.isEmpty()) return@setOnClick
            launchSuspend(it) {
                hideKeyboard()
                val activity = requireActivity()
                Client.appApi.postIllustReport(flagObjectId, topicId, reasonDesc)
                // 回传 RESULT_OK,FlagReasonFragment 的 ActivityResultLauncher 回调据此级联
                // finish 自己——不再依赖跨屏的静态可变标志。
                activity.setResult(Activity.RESULT_OK)
                delay(200L)
                Common.showToast(getString(R.string.flag_send_successfully))
                delay(1000L)
                activity.finish()
            }
        }
    }

    companion object {
        const val FlagTopicIdKey = "flag_topic_id"
        const val FlagTopicTitleKey = "flag_topic_title"
        const val FlagObjectIdKey = "flag_object_id"
        const val FlagObjectTypeKey = "flag_object_type"

        fun newInstance(
            topicId: Int,
            // 可空:Java 调用方(TemplateActivity)的 intent.getStringExtra 本身就可能返回 null。
            topicTitle: String?,
            flagObjectId: Long,
            flagObjectType: Int,
        ): FlagDescFragment {
            val fragment = FlagDescFragment()
            fragment.arguments = Bundle().apply {
                putInt(FlagTopicIdKey, topicId)
                putString(FlagTopicTitleKey, topicTitle.orEmpty())
                putLong(FlagObjectIdKey, flagObjectId)
                putInt(FlagObjectTypeKey, flagObjectType)
            }
            return fragment
        }
    }
}
