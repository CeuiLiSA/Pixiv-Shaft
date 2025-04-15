package ceui.pixiv.ui.discover

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellHomeSpecBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.HomeOneLine
import ceui.loxia.Illust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.settings.LogOutActionReceiver
import ceui.pixiv.utils.setOnClick
import com.google.gson.Gson
import timber.log.Timber

class DiscoverAllFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by viewModels<DiscoverAllViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
    }
}


class SpecHolder(val homeOneLine: HomeOneLine) : ListItemHolder() {
    override fun getItemId(): Long {
        return homeOneLine.hashCode().toLong()
    }
}

@ItemHolder(SpecHolder::class)
class SpecViewHolder(bd: CellHomeSpecBinding) : ListItemViewHolder<CellHomeSpecBinding, SpecHolder>(bd) {

    override fun onBindViewHolder(holder: SpecHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.logOut.text = holder.homeOneLine.kind

        binding.logOut.setOnClick {
            it.findActionReceiverOrNull<LogOutActionReceiver>()?.onClickLogOut(it)
        }
    }
}