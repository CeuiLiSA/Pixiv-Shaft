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
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.setUpLayoutManager
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.web.WebFragmentArgs
import ceui.refactor.viewBinding
import com.scwang.smart.refresh.header.FalsifyFooter
import com.scwang.smart.refresh.header.FalsifyHeader
import com.tencent.mmkv.MMKV

class SettingsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.toolbarLayout.naviTitle.text = getString(R.string.app_settings)
        binding.refreshLayout.setRefreshHeader(FalsifyHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(FalsifyFooter(requireContext()))
        setUpToolbar(binding.toolbarLayout, binding.listView)
        binding.listView.adapter = adapter
        setUpLayoutManager(binding.listView, ListMode.VERTICAL_NO_MARGIN)
        val liveUser = ObjectPool.get<User>(SessionManager.loggedInUid)
        val cookies = prefStore.getString(SessionManager.COOKIE_KEY, "") ?: ""
        val nameCode = prefStore.getString(SessionManager.CONTENT_LANGUAGE_KEY, "cn") ?: "cn"

        liveUser.observe(viewLifecycleOwner) { user ->
            adapter.submitList(
                listOf(
                    TabCellHolder(
                        getString(R.string.sync_cookies_with_pixiv_net),
                        getString(R.string.can_use_web_features_after_sync),
                        if (cookies.isNotEmpty()) getString(R.string.cookie_sync_has_been_done) else getString(
                            R.string.cookie_was_not_synced
                        )
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_web_fragment,
                            WebFragmentArgs("https://www.pixiv.net/", saveCookies = true).toBundle()
                        )
                    },
                    TabCellHolder(
                        getString(R.string.view_and_artworks_display),
                        getString(R.string.handle_r18g_displaying)
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_web_fragment,
                            WebFragmentArgs("https://www.pixiv.net/settings/viewing").toBundle()
                        )
                    },
                    TabCellHolder(
                        getString(R.string.country_and_region),
                        getString(R.string.handle_content_language),
                        nameCode
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_select_country,
                        )
                    },
                )
            )
        }
    }
}