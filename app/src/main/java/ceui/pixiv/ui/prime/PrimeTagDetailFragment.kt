package ceui.pixiv.ui.prime

import android.content.Intent
import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.launchSpinner
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import java.util.UUID

class PrimeTagDetailFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<PrimeTagDetailFragmentArgs>()
    private val viewModel by constructVM({ safeArgs.path }) { path ->
        PrimeTagDetailViewModel(path)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        binding.toolbarLayout.naviTitle.text = safeArgs.name
    }

    override fun onClickIllust(illustId: Long) {
        launchSpinner {
            val illust = Client.appApi.getIllust(illustId).illust
            val gson = Shaft.sGson
            val uuid = UUID.randomUUID().toString()
            val illustBean = gson.fromJson(gson.toJson(illust), IllustsBean::class.java)
            val pageData = PageData(uuid, null, listOf(illustBean))
            Container.get().addPageToMap(pageData)

            val intent = Intent(context, VActivity::class.java)
            intent.putExtra(Params.POSITION, 0)
            intent.putExtra(Params.PAGE_UUID, uuid)
            startActivity(intent)
        }
    }

    companion object {
        fun newInstance(name: String, path: String): PrimeTagDetailFragment {
            val fragment = PrimeTagDetailFragment()
            fragment.arguments = PrimeTagDetailFragmentArgs(name, path).toBundle()
            return fragment
        }
    }
}