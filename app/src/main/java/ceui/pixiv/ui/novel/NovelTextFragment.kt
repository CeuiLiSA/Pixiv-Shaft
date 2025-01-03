package ceui.pixiv.ui.novel

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.fragments.WebNovelParser
import ceui.lisa.models.ObjectSpec
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.WebNovel
import ceui.loxia.flag.FlagReasonFragmentArgs
import ceui.loxia.pushFragment
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.blocking.BlockingManager
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.works.blurBackground
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu

class NovelTextViewModel : ViewModel() {
    var webNovel: WebNovel? = null
}

class NovelTextFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val args by navArgs<NovelTextFragmentArgs>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val novelViewModel by viewModels<NovelTextViewModel>()
    private val bgViewModel by pixivValueViewModel({
        Client.appApi.getUserBookmarkedIllusts(SessionManager.loggedInUid, Params.TYPE_PUBLIC)
    })
    private val viewModel by pixivListViewModel({ Pair(novelViewModel, args.novelId) }) { (vm, novelId) ->
        DataSource<String, KListShow<String>>(
            dataFetcher = {
                val html = Client.appApi.getNovelText(novelId).string()
                object : KListShow<String> {
                    override val displayList: List<String>
                        get() {
                            val webNovel = WebNovelParser.parsePixivObject(html)?.novel
                            vm.webNovel = webNovel
                            return webNovel?.text?.split("\n") ?: listOf()
                        }
                    override val nextPageUrl: String?
                        get() = null
                }
            },
            itemMapper = { text -> WebNovelParser.buildNovelHolders(vm.webNovel, text) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        bgViewModel.result.observe(viewLifecycleOwner) { resp ->
            resp.displayList.getOrNull(args.novelId.mod(10))?.let {
                ObjectPool.update(it)
                blurBackground(binding, it.id)
            }
        }
        val authorId = ObjectPool.get<Novel>(args.novelId).value?.user?.id ?: 0L
        binding.toolbarLayout.naviMore.setOnClick {
            showActionMenu {
                add(
                    MenuItem(getString(R.string.view_comments)) {
                        pushFragment(R.id.navigation_comments_illust, CommentsFragmentArgs(args.novelId, authorId, ObjectType.NOVEL).toBundle())
                    }
                )
                add(
                    MenuItem(getString(R.string.string_5)) {

                    }
                )
            }
        }
    }
}