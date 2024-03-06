package ceui.loxia.flag

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentSlinkyListBinding
import ceui.loxia.*
import ceui.refactor.viewBinding

class FlagReasonFragment : SlinkyListFragment(), FlagActionReceiver {

    private val binding by viewBinding(FragmentSlinkyListBinding::bind)
    private val safeArgs by threadSafeArgs<FlagReasonFragmentArgs>()
    private val viewModel by slinkyListVMCustom {
        FlagReasonRepository()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity()
        binding.toolbar.toolbar.setNavigationOnClickListener { activity.finish() }
        binding.toolbar.toolbarTitle.text = getString(R.string.violated_rule)
        binding.listView.layoutManager = LinearLayoutManager(activity)
        setUpSlinkyList(binding.listView, binding.refreshLayout, binding.itemLoading, viewModel)
    }

    companion object {
        fun newInstance(flagObjectId: Int, flagObjectType: Int): FlagReasonFragment {
            val fragment = FlagReasonFragment()
            fragment.arguments = Bundle().apply {
                putInt(FlagDescFragment.FlagObjectIdKey, flagObjectId)
                putInt(FlagDescFragment.FlagObjectTypeKey, flagObjectType)
            }
            return fragment
        }

        var shouldAutoFinish = false
    }

    override fun onResume() {
        super.onResume()
        if (shouldAutoFinish) {
            shouldAutoFinish = false
            requireActivity().finish()
        }
    }

    override fun onClickFlag(holder: FlagReasonHolder) {
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "填写举报详细信息")
            putExtra(FlagDescFragment.FlagReasonIdKey, holder.id)
            putExtra(FlagDescFragment.FlagObjectIdKey, safeArgs.flagObjectId)
            putExtra(FlagDescFragment.FlagObjectTypeKey, safeArgs.flagObjectType)
        })
    }
}
