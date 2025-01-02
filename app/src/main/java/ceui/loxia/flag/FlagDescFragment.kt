package ceui.loxia.flag

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.R
import ceui.lisa.databinding.FragmentFlagDescBinding
import ceui.lisa.utils.Common
import ceui.loxia.*
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.delay

class FlagDescViewModel : ViewModel() {
    val desc = MutableLiveData<String>()
}

class FlagDescFragment : PixivFragment(R.layout.fragment_flag_desc) {

    private val binding by viewBinding(FragmentFlagDescBinding::bind)
    private val viewModel by viewModels<FlagDescViewModel>()
    private val safeArgs by threadSafeArgs<FlagDescFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbar.toolbarTitle.text = getString(R.string.flag_desc)
        binding.viewModel = viewModel
        when (safeArgs.flagReasonId) {
            FlagReason.ContainsExcessiveSexualId -> {
                binding.flagType.text = getString(R.string.contains_excessive_sexual)
            }
            FlagReason.ContainsExcessiveGrotesqueId -> {
                binding.flagType.text = getString(R.string.contains_excessive_grotesque)
            }
            FlagReason.InfringesOnCopyrightsId -> {
                binding.flagType.text = getString(R.string.infringes_on_copyrights)
            }
            else -> {
                binding.flagType.text = getString(R.string.violated_other_rules)
            }
        }
        binding.submitFlag.setOnClick {
            val reasonDesc = viewModel.desc.value ?: return@setOnClick
            if (reasonDesc.isNotEmpty()) {
                val flagReasonSpec = when (safeArgs.flagReasonId) {
                    FlagReason.ContainsExcessiveSexualId -> {
                        FlagReason.ContainsExcessiveSexualContent
                    }
                    FlagReason.ContainsExcessiveGrotesqueId -> {
                        FlagReason.ContainsExcessiveGrotesqueContent
                    }
                    FlagReason.InfringesOnCopyrightsId -> {
                        FlagReason.InfringesOnCopyrights
                    }
                    else -> {
                        FlagReason.ViolatedOtherRules
                    }
                }
                launchSuspend(it) {
                    hideKeyboard()
                    val activity = requireActivity()
                    Client.appApi.postFlagIllust(safeArgs.flagObjectId, flagReasonSpec, reasonDesc)
                    FlagReasonFragment.shouldAutoFinish = true
                    delay(200L)
                    Common.showToast(getString(R.string.flag_send_successfully))
                    delay(1000L)
                    activity.finish()
                }
            }
        }
    }

    companion object {
        const val FlagReasonIdKey = "flag_reason_id"
        const val FlagObjectIdKey = "flag_object_id"
        const val FlagObjectTypeKey = "flag_object_type"

        fun newInstance(
            flagReasonId: Int,
            flagObjectId: Int,
            flagObjectType: Int
        ): FlagDescFragment {
            val args = Bundle()
            args.putInt(FlagReasonIdKey, flagReasonId)
            args.putInt(FlagObjectIdKey, flagObjectId)
            args.putInt(FlagObjectTypeKey, flagObjectType)
            val fragment = FlagDescFragment()
            fragment.arguments = args
            return fragment
        }
    }
}