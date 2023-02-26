package ceui.loxia

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.CellFlagReasonBinding
import ceui.lisa.databinding.FragmentSlinkyListBinding
import ceui.refactor.ListItemHolder
import ceui.refactor.ListItemViewHolder
import ceui.refactor.viewBinding
import kotlinx.coroutines.delay

object FlagReason {

    // 过度的性描写
    const val ContainsExcessiveSexualId = 1001
    const val ContainsExcessiveSexualContent = "contains_excessive_sexual_content"

    // 过度的怪异描写
    const val ContainsExcessiveGrotesqueId = 1002
    const val ContainsExcessiveGrotesqueContent = "contains_excessive_grotesque_content"

    // 违反著作权
    const val InfringesOnCopyrightsId = 1003
    const val InfringesOnCopyrights = "infringes_on_copyrights"

    // 违反其他服务条款
    const val ViolatedOtherRulesId = 1004
    const val ViolatedOtherRules = "violated_other_rules"
}

class FlagReasonFragment : SlinkyListFragment() {

    private val binding by viewBinding(FragmentSlinkyListBinding::bind)
    private val viewModel by slinkyListVMCustom {
        FlagReasonRepository()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
        setUpSlinkyList(binding.listView, binding.refreshLayout, binding.itemLoading, viewModel)
    }
}

class FlagReasonRepository : Repository<FlagReasonFragment>() {

    override suspend fun refresh(fragment: FlagReasonFragment) {
        with(fragment) {
            delay(500L)
            val list = mutableListOf<ListItemHolder>()
            list.add(
                FlagReasonHolder(
                    FlagReason.ContainsExcessiveSexualId,
                    getString(R.string.contains_excessive_sexual),
                    FlagReason.ContainsExcessiveSexualContent
                )
            )
            list.add(
                FlagReasonHolder(
                    FlagReason.ContainsExcessiveGrotesqueId,
                    getString(R.string.contains_excessive_grotesque),
                    FlagReason.ContainsExcessiveGrotesqueContent
                )
            )
            list.add(
                FlagReasonHolder(
                    FlagReason.InfringesOnCopyrightsId,
                    getString(R.string.infringes_on_copyrights),
                    FlagReason.InfringesOnCopyrights
                )
            )
            list.add(
                FlagReasonHolder(
                    FlagReason.ViolatedOtherRulesId,
                    getString(R.string.violated_other_rules),
                    FlagReason.ViolatedOtherRules
                )
            )
            holderList.value = list
            refreshState.value = RefreshState.LOADED(hasContent = true, hasNext = false)
        }
    }

    override suspend fun loadMore(fragment: FlagReasonFragment) {
    }
}

class FlagReasonHolder(val id: Int, val content: String, val key: String) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? FlagReasonHolder)?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return id == (other as? FlagReasonHolder)?.id &&
                content == (other as? FlagReasonHolder)?.content &&
                key == (other as? FlagReasonHolder)?.key
    }
}

class FlagReasonViewHolder(binding: CellFlagReasonBinding) :
    ListItemViewHolder<CellFlagReasonBinding, FlagReasonHolder>(binding) {

    override fun onBindViewHolder(holder: FlagReasonHolder, position: Int) {
        binding.flagReasonTv.text = holder.content
    }
}