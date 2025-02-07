package ceui.pixiv.ui.novel

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.Series
import ceui.loxia.combineLatest
import ceui.loxia.pushFragment
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.shareIllust
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.DownloadNovelTask
import ceui.pixiv.ui.works.blurBackground
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import kotlin.getValue


class NovelTextFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment, NovelSeriesActionReceiver {

    private val safeArgs by navArgs<NovelTextFragmentArgs>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val bgViewModel by pixivValueViewModel(
        dataFetcher = { Client.appApi.getUserBookmarkedIllusts(SessionManager.loggedInUid, Params.TYPE_PUBLIC) },
        responseStore = createResponseStore({"user-${SessionManager.loggedInUid}-bookmarked-illusts"})
    )
    private val textModel by constructVM({ safeArgs.novelId }) { novelId->
        NovelTextViewModel(novelId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, textModel, ListMode.VERTICAL)
        bgViewModel.result.observe(viewLifecycleOwner) { resp ->
            resp.displayList.getOrNull(safeArgs.novelId.mod(10))?.let {
                ObjectPool.update(it)
                blurBackground(binding, it.id)
            }
        }
        val liveNovel = ObjectPool.get<Novel>(safeArgs.novelId)
        combineLatest(liveNovel, textModel.webNovel).observe(viewLifecycleOwner) { (novel, webNovel) ->
            binding.toolbarLayout.naviMore.setOnClick {
                if (novel == null || webNovel == null) {
                    return@setOnClick
                }

                val authorId = novel.user?.id ?: 0L
                showActionMenu {
                    add(
                        MenuItem(getString(R.string.view_comments)) {
                            pushFragment(R.id.navigation_comments_illust, CommentsFragmentArgs(safeArgs.novelId, authorId, ObjectType.NOVEL).toBundle())
                        }
                    )
                    add(
                        MenuItem(getString(R.string.string_110)) {
                            shareNovel(novel)
                        }
                    )
                    add(
                        MenuItem(getString(R.string.string_5)) {
                            DownloadNovelTask(requireActivity().lifecycleScope, novel, webNovel).start {

                            }
                        }
                    )
                }
            }
        }
    }
}
