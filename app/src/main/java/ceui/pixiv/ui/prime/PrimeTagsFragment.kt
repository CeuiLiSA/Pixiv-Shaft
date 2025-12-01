package ceui.pixiv.ui.prime

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellItemPrimeTagBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Illust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding

class PrimeTagsFragment : PixivFragment(R.layout.fragment_pixiv_list), PrimeTagActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by viewModels<PrimeTagsViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        binding.toolbarLayout.naviTitle.text = getString(R.string.prime_tags)
    }

    override fun onClickPrimeTag(
        primeTag: PrimeTagResult,
        filePath: String
    ) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "PrimeTagDetail")
        intent.putExtra("name", primeTag.tag.translated_name)
        intent.putExtra("path", filePath)
        startActivity(intent)
    }
}


class PrimeTagItemHolder(val primeTag: PrimeTagResult, val filePath: String) : ListItemHolder() {
    val illust0: Illust?
        get() {
            return primeTag.resp.illusts.getOrNull(0)
        }
    val illust1: Illust?
        get() {
            return primeTag.resp.illusts.getOrNull(1)
        }
    val illust2: Illust?
        get() {
            return primeTag.resp.illusts.getOrNull(2)
        }

    override fun getItemId(): Long {
        return filePath.hashCode().toLong()
    }
}

@ItemHolder(PrimeTagItemHolder::class)
class PrimeTagItemViewHolder(private val bd: CellItemPrimeTagBinding) :
    ListItemViewHolder<CellItemPrimeTagBinding, PrimeTagItemHolder>(bd) {

    override fun onBindViewHolder(holder: PrimeTagItemHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.holder = holder
        binding.root.setOnClickListener {
            it.findActionReceiverOrNull<PrimeTagActionReceiver>()
                ?.onClickPrimeTag(holder.primeTag, holder.filePath)
        }
    }
}

interface PrimeTagActionReceiver {
    fun onClickPrimeTag(primeTag: PrimeTagResult, filePath: String)
}

