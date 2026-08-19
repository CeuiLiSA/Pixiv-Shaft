package ceui.pixiv.ui.usage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.DialogNana7miClaimBinding
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Nana7miClaimResult
import ceui.loxia.claimAfdianOrder
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.search.v3.V3BottomSheetBase
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.launch

/**
 * 恢复购买 —— 拿爱发电订单号认领一笔**没走 app 链接**、直接在爱发电站内下的单。
 *
 * 从 app 里点「选择」买的单，链接里已经带着签过名的身份，付完自动到账，用不着这里。
 * 这个入口只为另一种人存在：先在爱发电看到方案、直接在那边付了款的。那种单回到服务端时
 * 身上没有身份，只能停在 `unclaimed` 等人；服务端会私信他们一句「到 app 里填订单号」，
 * 这页就是那句话指向的地方。
 *
 * 只做一件事：把号交给服务端、把服务端的裁决翻译成一句话。所有判断（这单存不存在、
 * 付没付、用没用过、该不该自动发）都在服务端，客户端不复述、不预判。
 */
class Nana7miClaimSheet : V3BottomSheetBase() {

    private var _binding: DialogNana7miClaimBinding? = null
    private val binding get() = _binding!!

    /** 一次只许一笔在飞：连点两下等于同一个单号打两次，服务端第二次会说「已经用过了」。 */
    private var inFlight = false

    override val maxHeightFraction: Float = 0.6F

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogNana7miClaimBinding.inflate(inflater, container, false)
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
        binding.btnCancel.setOnClick { dismissAllowingStateLoss() }
        binding.btnConfirm.setOnClick { submit() }
    }

    private fun submit() {
        if (inFlight) return
        val no = binding.orderInput.text.toString().trim()
        // 和服务端同一条闸（`^[0-9]{6,40}$`）：明显不是单号的先在本地拦下，别白打一个 400。
        if (!Regex("^[0-9]{6,40}$").matches(no)) {
            Common.showToast(getString(R.string.nana7mi_usage_claim_bad))
            return
        }
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        inFlight = true
        binding.btnConfirm.isEnabled = false
        binding.btnConfirm.alpha = 0.4f
        viewLifecycleOwner.lifecycleScope.launch {
            val result = Client.pixshaft.claimAfdianOrder(uid, no)
            inFlight = false
            _binding?.btnConfirm?.let {
                it.isEnabled = true
                it.alpha = 1f
            }
            when (result) {
                is Nana7miClaimResult.Success -> {
                    Common.showToast(getString(R.string.nana7mi_usage_claim_ok))
                    // 让用量页立刻重拉：档位和两只桶的 max 这一刻都变了。
                    parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(KEY_CLAIMED to true))
                    dismissAllowingStateLoss()
                }
                Nana7miClaimResult.NotFound -> Common.showToast(getString(R.string.nana7mi_usage_claim_not_found))
                Nana7miClaimResult.Taken -> Common.showToast(getString(R.string.nana7mi_usage_claim_taken))
                Nana7miClaimResult.Refused -> Common.showToast(getString(R.string.nana7mi_usage_claim_refused))
                Nana7miClaimResult.BadNumber -> Common.showToast(getString(R.string.nana7mi_usage_claim_bad))
                is Nana7miClaimResult.Retry -> Common.showToast(getString(R.string.nana7mi_usage_claim_retry))
            }
        }
    }

    companion object {
        const val REQUEST_KEY = "nana7mi_claim"
        const val KEY_CLAIMED = "claimed"
    }
}
