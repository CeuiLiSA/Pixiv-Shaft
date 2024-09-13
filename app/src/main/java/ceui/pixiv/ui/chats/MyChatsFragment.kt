package ceui.pixiv.ui.chats

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.ItemIllustSquareBinding
import ceui.lisa.databinding.ItemRedSectionHeaderBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Client
import ceui.loxia.SquareResponse
import ceui.loxia.WebIllust
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide

class MyChatsFragment : TitledViewPagerFragment(R.layout.fragment_pixiv_list), HomeTabContainer {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivValueViewModel {
        val responseStore = ResponseStore(
            keyProvider = { "home-square" },
            expirationTimeMillis = 1800,
            typeToken = SquareResponse::class.java,
            dataLoader = { Client.webApi.getSquareContents() }
        )
        responseStore.retrieveData()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.adapter = adapter
        binding.listView.layoutManager = GridLayoutManager(context, 3).apply {
            spanSizeLookup = object : SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (binding.listView.adapter?.getItemViewType(position) == RedSectionHeaderHolder::class.java.hashCode()) {
                        3
                    } else {
                        1
                    }
                }
            }
        }
        viewModel.result.observe(viewLifecycleOwner) { data ->
            val holders = mutableListOf<ListItemHolder>()

            data.body?.page?.ranking?.let { ranking ->
                val webIllusts = mutableListOf<WebIllust>()
                ranking.items?.map { it.id }?.forEach { id ->
                    data.body.thumbnails?.illust?.firstOrNull { it.id == id }?.let { webIllust ->
                        webIllusts.add(webIllust)
                    }
                }
                holders.add(RedSectionHeaderHolder("Ranking for ${ranking.date}"))
                holders.addAll(webIllusts.map { IllustSquareHolder(it) })
            }

            data.body?.page?.recommendByTag?.forEach { tag ->
                val webIllusts = mutableListOf<WebIllust>()
                tag.ids?.forEach { id ->
                    data.body.thumbnails?.illust?.firstOrNull { it.id == id }?.let { webIllust ->
                        webIllusts.add(webIllust)
                    }
                }
                holders.add(RedSectionHeaderHolder(tag.tag ?: ""))
                holders.addAll(webIllusts.map { IllustSquareHolder(it) })
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
                holders.addAll(webIllusts.map { IllustSquareHolder(it) })
            }

            adapter.submitList(holders)
        }
    }
}


class RedSectionHeaderHolder(
    val title: String,
    val type: Int = 0,
    val seeMoreString: String? = null
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
        binding.seeMore.text = holder.seeMoreString
        binding.seeMore.isVisible = holder.type != 0
        binding.seeMore.setOnClick {
            it.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(holder.type)
        }
    }
}

interface SeeMoreAction {

    fun seeMore(type: Int)
}

class IllustSquareHolder(val illust: WebIllust) : ListItemHolder() {

    override fun getItemId(): Long {
        return illust.id
    }
}


@ItemHolder(IllustSquareHolder::class)
class IllustSquareViewHolder(aa: ItemIllustSquareBinding) :
    ListItemViewHolder<ItemIllustSquareBinding, IllustSquareHolder>(aa) {

    override fun onBindViewHolder(holder: IllustSquareHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        Glide.with(context)
            .load(GlideUrlChild(holder.illust.url))
            .into(binding.squareImage)
        binding.squareImage.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust.toIllust())
        }
    }
}