package ceui.pixiv.ui.discovery

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Params
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.v3Font
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/** 新增官网发现入口；与本地候选池 DiscoveryFeedFragment 完全独立。 */
class WebDiscoveryFragment : Fragment(R.layout.fragment_web_discovery) {
    private var mediator: TabLayoutMediator? = null
    private var pager: ViewPager2? = null
    private val webLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        childFragmentManager.fragments.filterIsInstance<WebDiscoveryFeedFragment>()
            .forEach { it.onWebLoginReturned() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        view.findViewById<TextView>(R.id.toolbar_title).apply {
            setText(R.string.web_discovery_title)
            typeface = context.v3Font(600)
        }
        // 即使旧 cookie 尚在但已失效，也能在错误页直接重新登录。
        toolbar.menu.add(R.string.web_discovery_web_login).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener { openWebLogin(); true }
        }
        val tabs = view.findViewById<TabLayout>(R.id.discovery_modes)
        val palette = V3Palette.from(context)
        tabs.setSelectedTabIndicator(GradientDrawable().apply {
            cornerRadius = context.dp(11).toFloat()
            setColor(palette.alpha20)
        })
        tabs.setSelectedTabIndicatorColor(palette.alpha20)
        tabs.setTabTextColors(context.getColor(R.color.v3_text_2), palette.textAccent)
        val pager = view.findViewById<ViewPager2>(R.id.discovery_pager).also { this.pager = it }
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = WebDiscoveryMode.entries.size
            override fun createFragment(position: Int): Fragment =
                WebDiscoveryFeedFragment.newInstance(WebDiscoveryMode.entries[position])
        }
        pager.setCurrentItem(
            savedInstanceState?.getInt(STATE_MODE)
                ?: WebDiscoveryMode.initial(Shaft.sSettings.isR18FilterTempEnable).ordinal,
            false,
        )
        val labels = listOf(R.string.string_390, R.string.string_440, R.string.string_441)
        mediator = TabLayoutMediator(tabs, pager) { tab, position ->
            tab.setText(labels[position])
        }.also { it.attach() }
    }

    fun openWebLogin() {
        webLogin.launch(Intent(requireContext(), TemplateActivity::class.java).apply {
            if (SessionManager.hasWebCookie) {
                // Web 首页看到存量 cookie + CSRF 时会直接展示内容；重登必须真正打开登录页。
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_PAGE.key)
                putExtra(Params.URL, "https://accounts.pixiv.net/login")
                putExtra("saveCookies", true)
            } else {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_HOME.key)
                putExtra(Params.AUTO_WEB_LOGIN, true)
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pager?.let { outState.putInt(STATE_MODE, it.currentItem) }
    }

    override fun onDestroyView() {
        mediator?.detach()
        mediator = null
        pager?.adapter = null
        pager = null
        super.onDestroyView()
    }

    companion object {
        private const val STATE_MODE = "discovery_selected_mode"
    }
}
