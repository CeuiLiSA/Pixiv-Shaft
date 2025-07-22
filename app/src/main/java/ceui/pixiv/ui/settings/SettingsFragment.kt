package ceui.pixiv.ui.settings

import android.os.Bundle
import android.view.View
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.User
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.loxia.requireAppBackground
import ceui.loxia.requireTaskPool
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.background.BackgroundType
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.web.WebFragmentArgs
import ceui.pixiv.widgets.alertYesOrCancel
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

class SettingsFragment : PixivFragment(R.layout.fragment_pixiv_list), LogOutActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val prefStore: MMKV by lazy {
        MMKV.mmkvWithID("shaft-session")
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = setUpCustomAdapter(binding, ListMode.VERTICAL_TABCELL)
        binding.toolbarLayout.naviTitle.text = getString(R.string.app_settings)
        val liveUser = ObjectPool.get<User>(SessionManager.loggedInUid)
        val cookies = prefStore.getString(SessionManager.COOKIE_KEY, "") ?: ""
        val nameCode = prefStore.getString(SessionManager.CONTENT_LANGUAGE_KEY, "cn") ?: "cn"
        val context = requireActivity()
        val backgroundType = requireAppBackground().config.value?.type

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
                        getString(R.string.app_background),
                        extraInfo = if (backgroundType == BackgroundType.SPECIFIC_ILLUST) {
                            getString(R.string.background_specified_illust)
                        } else if (backgroundType == BackgroundType.RANDOM_FROM_FAVORITES) {
                            getString(R.string.background_random_from_favorites)
                        } else if (backgroundType == BackgroundType.LOCAL_FILE) {
                            getString(R.string.background_chosen_from_gallary)
                        } else {
                            backgroundType?.toString()
                        }
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_background_settings,
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

                    TabCellHolder(
                        getString(R.string.language),
                        getString(R.string.handle_content_language),
                        Shaft.sSettings.appLanguage
                    ).onItemClick {
                        pushFragment(
                            R.id.navigation_select_language,
                        )
                    },

                    TabCellHolder(
                        getString(R.string.export_refresh_token),
                        extraInfo = SessionManager.loggedInAccount.value?.refresh_token,
                    ).onItemClick {
                        SessionManager.loggedInAccount.value?.refresh_token?.let { token ->
                            Common.copy(context, token)
                        }
                    },

                    TabCellHolder(
                        getString(R.string.export_logged_in_user_json),
                        extraInfo = "[JSON FORMATTED]"
                    ).onItemClick {
                        SessionManager.loggedInAccount.value?.let { account ->
                            Common.copy(context, Gson().toJson(account))
                        }
                    },

                    LogOutHolder()
                )
            )
        }
    }

    override fun onClickLogOut(sender: ProgressIndicator) {
        launchSuspend(sender) {
            val taskPool = requireTaskPool()
            val prefStore = MMKV.mmkvWithID("api-cache-${SessionManager.loggedInUid}")
            if (alertYesOrCancel("确定退出登录吗")) {
                prefStore.clearAll()
                taskPool.clearTasks()
                SessionManager.updateSession(null)
                findNavController().navigate(
                    R.id.navigation_landing,
                    null, // 如果有参数需要传递，可以用 Bundle 替代 null
                    NavOptions.Builder()
                        .setPopUpTo(R.id.mobile_navigation, true) // 清除栈中所有页面
                        .setLaunchSingleTop(true) // 防止重复创建 D
                        .build()
                )
            }
        }
    }
}