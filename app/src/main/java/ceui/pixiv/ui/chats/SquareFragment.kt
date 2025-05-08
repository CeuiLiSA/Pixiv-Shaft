package ceui.pixiv.ui.chats

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.LiveData
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.ItemIllustSquareBinding
import ceui.lisa.databinding.ItemRedSectionHeaderBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Client
import ceui.loxia.WebIllust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.repo.HybridRepository
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.settings.CookieNotSyncException
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.ArtworksMap
import com.bumptech.glide.Glide
import com.tencent.mmkv.MMKV

class SquareFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by navArgs<SquareFragmentArgs>()
    private val viewModel by pixivValueViewModel(
        argsProducer = { Pair(safeArgs.objectType, MMKV.defaultMMKV()) },
        repositoryProducer = { (objectType, prefStore) ->
            SquareRepository(
                objectType,
                prefStore,
                createResponseStore({ "home-square-${safeArgs.objectType}" })
            )
        })


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.GRID_AND_SECTION_HEADER)
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.adapter = adapter
        binding.listView.updatePadding(left = 3.ppppx, right = 3.ppppx)
        viewModel.result.observe(viewLifecycleOwner) { loadResult ->
            val data = loadResult?.data ?: return@observe
            val holders = mutableListOf<ListItemHolder>()
            val ids = mutableListOf<Long>()

            data.body?.page?.ranking?.let { ranking ->
                val webIllusts = mutableListOf<WebIllust>()
                ranking.items?.map { it.id }?.forEach { id ->
                    data.body.thumbnails?.illust?.firstOrNull { it.id == id }?.let { webIllust ->
                        webIllusts.add(webIllust)
                    }
                }
                holders.add(RedSectionHeaderHolder("Ranking for ${ranking.date}"))
                holders.addAll(webIllusts.map {
                    ids.add(it.id)
                    IllustSquareHolder(it)
                })
            }


            data.body?.page?.editorRecommend?.let { editorRecommend ->
                val webIllusts = mutableListOf<WebIllust>()
                editorRecommend.forEach { recmd ->
                    recmd.illustId?.let { id ->
                        data.body.thumbnails?.illust?.firstOrNull { it.id == id }
                            ?.let { webIllust ->
                                webIllusts.add(webIllust)
                            }
                    }
                }
                holders.add(RedSectionHeaderHolder("Editor Recommend Works"))
                holders.addAll(webIllusts.map {
                    ids.add(it.id)
                    IllustSquareHolder(it)
                })
            }


            data.body?.page?.recommend?.ids?.let { recommendIllustIds ->
                val webIllusts = mutableListOf<WebIllust>()
                recommendIllustIds.forEach { id ->
                    data.body.thumbnails?.illust?.firstOrNull { it.id == id }?.let { webIllust ->
                        webIllusts.add(webIllust)
                    }
                }
                holders.add(RedSectionHeaderHolder("Recommend Works"))
                holders.addAll(webIllusts.map {
                    ids.add(it.id)
                    IllustSquareHolder(it)
                })
            }



            data.body?.page?.recommendByTag?.forEach { tag ->
                val webIllusts = mutableListOf<WebIllust>()
                tag.ids?.forEach { id ->
                    data.body.thumbnails?.illust?.firstOrNull { it.id == id }?.let { webIllust ->
                        webIllusts.add(webIllust)
                    }
                }
                holders.add(RedSectionHeaderHolder(tag.tag ?: ""))
                holders.addAll(webIllusts.map {
                    ids.add(it.id)
                    IllustSquareHolder(it)
                })
            }

            val tags =
                (data.body?.page?.tags ?: listOf()) + (data.body?.page?.trendingTags ?: listOf())
            tags.distinctBy { it.tag }.forEach { tag ->
                val webIllusts = mutableListOf<WebIllust>()
                tag.ids?.forEach { id ->
                    data.body?.thumbnails?.illust?.firstOrNull { it.id == id }?.let { webIllust ->
                        webIllusts.add(webIllust)
                    }
                }
                holders.add(RedSectionHeaderHolder(tag.tag ?: ""))
                holders.addAll(webIllusts.map {
                    ids.add(it.id)
                    IllustSquareHolder(it)
                })
            }

            adapter.submitList(holders) {
                ArtworksMap.store[fragmentViewModel.fragmentUniqueId] = ids
            }
        }
    }
}


class RedSectionHeaderHolder(
    val title: String,
    val type: Int = 0,
    val seeMoreString: String? = null,
    val liveEndText: LiveData<String>? = null
) : ListItemHolder() {


    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return title == (other as? RedSectionHeaderHolder)?.title
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return title == (other as? RedSectionHeaderHolder)?.title &&
                type == (other as? RedSectionHeaderHolder)?.type &&
                seeMoreString == (other as? RedSectionHeaderHolder)?.seeMoreString
    }
}


@ItemHolder(RedSectionHeaderHolder::class)
class RedSectionHeaderViewHolder(aa: ItemRedSectionHeaderBinding) :
    ListItemViewHolder<ItemRedSectionHeaderBinding, RedSectionHeaderHolder>(aa) {

    override fun onBindViewHolder(holder: RedSectionHeaderHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.title.text = holder.title
        binding.seeMoreLayout.isVisible = holder.type != 0
        binding.seeMoreLayout.setOnClick {
            it.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(holder.type)
        }
        val liveEndText = holder.liveEndText
        if (liveEndText != null) {
            liveEndText.observe(lifecycleOwner) { liveText ->
                binding.seeMore.text = liveText
            }
        } else {
            binding.seeMore.text = holder.seeMoreString
        }
    }
}

interface SeeMoreAction {

    fun seeMore(type: Int)
}

object SeeMoreType {
    const val USER_CREATED_ILLUST = 199
    const val USER_CREATED_MANGA = 200
    const val USER_BOOKMARKED_ILLUST = 201
    const val USER_CREATED_NOVEL = 202
    const val RELATED_ILLUST = 203
}
