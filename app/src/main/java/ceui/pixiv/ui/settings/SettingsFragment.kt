package ceui.pixiv.ui.settings

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.pushFragment
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.web.WebFragmentArgs
import ceui.refactor.viewBinding
import com.scwang.smart.refresh.header.FalsifyFooter
import com.scwang.smart.refresh.header.FalsifyHeader

class SettingsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.toolbarLayout.naviTitle.text = getString(R.string.app_settings)
        binding.refreshLayout.setRefreshHeader(FalsifyHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(FalsifyFooter(requireContext()))
        setUpToolbar(binding.toolbarLayout, binding.listView)
        binding.listView.adapter = adapter
        val ctx = requireContext()
        binding.listView.layoutManager = LinearLayoutManager(ctx)
        val liveUser = ObjectPool.get<User>(SessionManager.loggedInUid)
        liveUser.observe(viewLifecycleOwner) { user ->
            adapter.submitList(
                listOf(
                    TabCellHolder(
                        "Pixiv 主站同步",
                        "同步主站 Cookies 可以使用 web 端功能"
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_web_fragment,
                            WebFragmentArgs("https://www.pixiv.net/", saveCookies = true).toBundle()
                        )
                    },
                    TabCellHolder(
                        "浏览与显示",
                        "设置是否显示 R-18(G) 内容"
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_web_fragment,
                            WebFragmentArgs("https://www.pixiv.net/settings/viewing").toBundle()
                        )
                    },
                )
            )
        }
    }
}