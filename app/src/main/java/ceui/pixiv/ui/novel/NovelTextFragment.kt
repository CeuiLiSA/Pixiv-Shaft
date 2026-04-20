package ceui.pixiv.ui.novel

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.ItemBigReadButtonBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.combineLatest
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.DownloadNovelTask
import ceui.pixiv.ui.works.blurBackground
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu


class NovelTextFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment,
    NovelSeriesActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val novelId: Long by lazy { arguments?.getLong(Params.NOVEL_ID, 0L) ?: 0L }
    private val bgViewModel by pixivValueViewModel(
        dataFetcher = {
            Client.appApi.getUserBookmarkedIllusts(
                SessionManager.loggedInUid,
                Params.TYPE_PUBLIC
            )
        },
        responseStore = createResponseStore({ "user-${SessionManager.loggedInUid}-bookmarked-illusts" })
    )
    private val textModel by constructVM({ novelId }) { id ->
        NovelTextViewModel(id)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, textModel, ListMode.VERTICAL)
        bgViewModel.result.observe(viewLifecycleOwner) { resp ->
            resp.displayList.getOrNull(novelId.mod(10))?.let {
                ObjectPool.update(it)
                blurBackground(binding, it.id)
            }
        }
        binding.toolbarLayout.root.visibility = View.GONE

        val bottomView = ItemBigReadButtonBinding.inflate(layoutInflater)
        binding.bottomCovered.isVisible = true
        binding.bottomCovered.addView(bottomView.root)
        bottomView.btnRead.setOnClick {
            val ctx = requireContext()
            val intent = Intent(ctx, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说正文")
                putExtra(Params.NOVEL_ID, novelId)
            }
            ctx.startActivity(intent)
        }
        val liveNovel = ObjectPool.get<Novel>(novelId)
        combineLatest(
            liveNovel,
            textModel.webNovel
        ).observe(viewLifecycleOwner) { (novel, webNovel) ->
            if (novel != null) {
                runOnceWithinFragmentLifecycle("visit-novel-${novelId}") {
                    requireEntityWrapper().visitNovel(requireContext(), novel)
                }
            }

            binding.toolbarLayout.naviMore.setOnClick {
                if (novel == null || webNovel == null) {
                    return@setOnClick
                }
                val authorId = novel.user?.id ?: 0L
                showActionMenu {
                    add(
                        MenuItem(getString(R.string.view_comments)) {
                            val intent =
                                Intent(requireContext(), TemplateActivity::class.java).apply {
                                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                                    putExtra(Params.NOVEL_ID, novelId.toInt())
                                }
                            startActivity(intent)
                        }
                    )
                    add(
                        MenuItem(getString(R.string.string_110)) {
                            shareNovel(novel)
                        }
                    )
                    add(
                        MenuItem(getString(R.string.string_5)) {
                            DownloadNovelTask(
                                requireActivity().lifecycleScope,
                                novel,
                                webNovel
                            ).start { }
                        }
                    )
                }
            }
        }

    }

    companion object {
        fun newInstance(novelId: Long): NovelTextFragment = NovelTextFragment().apply {
            arguments = Bundle().apply { putLong(Params.NOVEL_ID, novelId) }
        }
    }
}
