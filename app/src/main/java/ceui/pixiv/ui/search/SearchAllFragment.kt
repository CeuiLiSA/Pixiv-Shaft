package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.navigation.fragment.findNavController
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentSearchAllBinding
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.Tag
import ceui.loxia.combineLatest
import ceui.loxia.hideKeyboard
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.loxia.requireEntityWrapper
import ceui.loxia.showKeyboard
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpLayoutManager
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.ArtworksMap
import ceui.pixiv.ui.web.LinkHandler
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.alertYesOrCancel
import kotlinx.coroutines.delay

class SearchAllFragment : PixivFragment(R.layout.fragment_search_all), SearchItemActionReceiver {

    private val binding by viewBinding(FragmentSearchAllBinding::bind)
    private val searchViewModel by constructVM({
        "" to AppDatabase.getAppDatabase(
            requireContext()
        )
    }) { (word, database) ->
        SearchViewModel(true, word, database)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding.toolbarLayout, binding.contentGroup)
        binding.viewModel = searchViewModel
        binding.toolbarLayout.naviTitle.text = getString(R.string.search)
        binding.clearSearch.setOnClick {
            searchViewModel.inputDraft.value = ""
        }

        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.suggestionList.adapter = adapter
        setUpLayoutManager(binding.suggestionList, ListMode.VERTICAL_SEARCH_SUGGESTION)
        combineLatest(
            searchViewModel.searchSuggestion,
            searchViewModel.inputDraft,
            searchViewModel.historyLiveData
        ).observe(
            viewLifecycleOwner
        ) { (list, draft, history) ->
            if (draft?.isNotEmpty() == true && list != null) {
                adapter.submitList(list.tags.map { SearchItemHolder(it) })
            } else {
                val tagList =
                    history.orEmpty().map { Shaft.sGson.fromJson(it.json, Tag::class.java) }
                adapter.submitList(tagList.map { SearchItemHolder(it) })
            }
        }


        // 设置根布局的点击监听
        binding.touchOutside.setOnTouchListener { _, _ ->
            // 隐藏键盘
            hideKeyboard()
            false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime()) // 获取输入法的 insets
            val systemBarsInsets =
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()) // 获取系统栏的 insets

            // 更新 Toolbar 的顶部 padding
            binding.toolbarLayout.root.updatePaddingRelative(top = systemBarsInsets.top)
            binding.searchRoot.updatePadding(0, 0, 0, systemBarsInsets.bottom + 20.ppppx)

            // 确定底部 inset
            binding.touchOutside.isVisible = imeInsets.bottom > 0

            WindowInsetsCompat.CONSUMED
        }

        binding.inputBox.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchByKeyword(binding.keywordSearch)
                true
            } else {
                false
            }
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
            searchByKeyword(sender)
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

    private fun searchByKeyword(sender: ProgressIndicator) {
        checkAndNext(sender) { word ->
            requireEntityWrapper().visitTag(requireContext(), Tag(word, word))
            pushFragment(
                R.id.navigation_search_viewpager, SearchViewPagerFragmentArgs(
                    keyword = word,
                ).toBundle()
            )
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

    override fun onClickSearchItem(tag: Tag) {
        if (tag.name?.isNotEmpty() == true) {
            requireEntityWrapper().visitTag(requireContext(), tag)
            pushFragment(
                R.id.navigation_search_viewpager, SearchViewPagerFragmentArgs(
                    keyword = tag.name,
                ).toBundle()
            )
        }
    }
}
