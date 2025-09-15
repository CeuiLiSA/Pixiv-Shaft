package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSearchAllBinding
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.loxia.showKeyboard
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.ArtworksMap
import ceui.pixiv.ui.web.LinkHandler
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.alertYesOrCancel
import kotlinx.coroutines.delay

class SearchAllFragment : PixivFragment(R.layout.fragment_search_all) {

    private val binding by viewBinding(FragmentSearchAllBinding::bind)
    private val searchViewModel by constructVM({ "" }) { word ->
        SearchViewModel(word)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding.toolbarLayout, binding.contentGroup)
        binding.viewModel = searchViewModel
        binding.toolbarLayout.naviTitle.text = getString(R.string.search)
        binding.clearSearch.setOnClick {
            searchViewModel.inputDraft.value = ""
        }
        binding.idSearchIllust.setOnClick { sender ->
            checkAndNext(sender) { word ->
                val id = word.toLong()
                val illust = Client.appApi.getIllust(id).illust
                if (illust != null) {
                    ArtworksMap.store[fragmentViewModel.fragmentUniqueId] = listOf(id)
                    ObjectPool.update(illust)
                    onClickIllust(illust.id)
                }
            }
        }
        binding.idSearchUser.setOnClick { sender ->
            checkAndNext(sender) { word ->
                val id = word.toLong()
                val userResp = Client.appApi.getUserProfile(id)
                userResp.user?.let { user ->
                    ObjectPool.update(user)
                }
                onClickUser(id)
            }
        }
        binding.idSearchNovel.setOnClick { sender ->
            checkAndNext(sender) { word ->
                val id = word.toLong()
                val novel = Client.appApi.getNovel(id).novel
                if (novel != null) {
                    ArtworksMap.store[fragmentViewModel.fragmentUniqueId] = listOf(id)
                    ObjectPool.update(novel)
                    onClickNovel(novel.id)
                }
            }
        }
        binding.keywordSearch.setOnClick { sender ->
            checkAndNext(sender) { word ->
                pushFragment(
                    R.id.navigation_search_viewpager, SearchViewPagerFragmentArgs(
                        keyword = word,
                    ).toBundle()
                )
            }
        }
        val linkHandler = LinkHandler(findNavController(), this)

        binding.idParseLink.setOnClick { sender ->
            checkAndNext(sender) { word ->
                if (!linkHandler.processLink(word)) {
                    alertYesOrCancel("不认识的链接")
                }
            }
        }
    }

    private fun checkAndNext(sender: ProgressIndicator, block: suspend (String) -> Unit) {
        val inputBox = binding.inputBox
        launchSuspend(sender) {
            val word = searchViewModel.inputDraft.value ?: ""
            if (word.trim().isNotEmpty()) {
                block(word)
            } else {
                if (alertYesOrCancel(getString(R.string.search_by_id_or_word))) {
                    delay(100L)
                    inputBox.requestFocusFromTouch()
                    showKeyboard(inputBox)
                }
            }
        }
    }
}
