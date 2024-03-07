package ceui.loxia.flag

import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellFlagReasonBinding
import ceui.loxia.RefreshState
import ceui.loxia.Repository
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.findFragmentOrNull
import ceui.loxia.novel.NovelTextHolder
import ceui.refactor.ListItemHolder
import ceui.refactor.ListItemViewHolder
import ceui.refactor.setOnClick

class FlagReasonRepository : Repository<FlagReasonFragment>() {

    override suspend fun refresh(fragment: FlagReasonFragment) {
        with(fragment) {
            holderList.value = listOf(
                FlagReasonHolder(
                    FlagReason.ContainsExcessiveSexualId,
                    getString(R.string.contains_excessive_sexual),
                    FlagReason.ContainsExcessiveSexualContent
                ), FlagReasonHolder(
                    FlagReason.ContainsExcessiveGrotesqueId,
                    getString(R.string.contains_excessive_grotesque),
                    FlagReason.ContainsExcessiveGrotesqueContent
                ), FlagReasonHolder(
                    FlagReason.InfringesOnCopyrightsId,
                    getString(R.string.infringes_on_copyrights),
                    FlagReason.InfringesOnCopyrights
                ), FlagReasonHolder(
                    FlagReason.ViolatedOtherRulesId,
                    getString(R.string.violated_other_rules),
                    FlagReason.ViolatedOtherRules
                )
            )
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
        return id == (other as? FlagReasonHolder)?.id && content == (other as? FlagReasonHolder)?.content && key == (other as? FlagReasonHolder)?.key
    }
}

@ItemHolder(FlagReasonHolder::class)
class FlagReasonViewHolder(binding: CellFlagReasonBinding) :
    ListItemViewHolder<CellFlagReasonBinding, FlagReasonHolder>(binding) {

    override fun onBindViewHolder(holder: FlagReasonHolder, position: Int) {
        binding.flagReasonTv.text = holder.content
        binding.root.setOnClick {
            it.findActionReceiverOrNull<FlagActionReceiver>()?.onClickFlag(holder)
        }
    }
}

interface FlagActionReceiver {
    fun onClickFlag(holder: FlagReasonHolder)
}