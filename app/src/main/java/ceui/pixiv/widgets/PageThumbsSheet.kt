package ceui.pixiv.widgets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.CellComicThumbBinding
import ceui.lisa.databinding.SheetComicThumbsBinding
import ceui.pixiv.ui.common.viewBinding
import com.bumptech.glide.Glide

/**
 * 多图预览网格的共用底座:漫画阅读器底栏的「页面预览」和详情页阅读胶囊长按出的预览(#1085)
 * 是同一张网格 —— 缩略图 + 页码角标 + 当前页高亮,点一张就跳过去。数据从哪来、点了往哪跳
 * 由子类决定,sheet 本身只管画。
 *
 * 缩略图的 Glide 模型由子类直接给成品对象:网络图必须包一层
 * [ceui.lisa.utils.GlideUrlChild](带 Pixiv 头 + 图片域名重写,见 issue #865),快照页则是本地
 * file Uri —— 两种来源在这里没有分支。某一页取不到图就给 null(Glide 吃 null,只是画不出那一格),
 * 不要把它从列表里剔掉:索引即页序,少一个元素后面全部错位。
 */
abstract class PageThumbsSheet : PixivBottomSheet(R.layout.sheet_comic_thumbs) {

    private val binding by viewBinding(SheetComicThumbsBinding::bind)

    /** 每页一个 Glide 能直接吃的模型,顺序即页序。空列表 = 没什么可预览的,sheet 自行关闭。 */
    protected abstract fun thumbModels(): List<Any?>

    /** 高亮并滚到这一页。 */
    protected abstract fun currentIndex(): Int

    /** 点了某一页。sheet 会在回调之后自行 dismiss。 */
    protected abstract fun onPagePicked(index: Int)

    /** 标题;留空则用布局里的「页面预览」。 */
    protected open fun sheetTitle(): String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val models = thumbModels()
        if (models.isEmpty()) { dismiss(); return }
        val current = currentIndex().coerceIn(0, models.size - 1)

        sheetTitle().takeIf { it.isNotEmpty() }?.let { binding.comicThumbsTitle.text = it }

        val span = if (resources.configuration.screenWidthDp >= 600) 5 else 3
        binding.comicThumbsList.layoutManager = GridLayoutManager(requireContext(), span)
        binding.comicThumbsList.adapter = ThumbAdapter(models, current) { idx ->
            onPagePicked(idx)
            dismiss()
        }
        binding.comicThumbsList.scrollToPosition(current)
    }

    private class ThumbAdapter(
        val models: List<Any?>,
        val current: Int,
        val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<ThumbAdapter.VH>() {

        class VH(val b: CellComicThumbBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(CellComicThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = models.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ctx = holder.b.root.context
            holder.b.cellThumbIndex.text = ctx.getString(
                R.string.comic_reader_page_indicator, position + 1, models.size,
            )
            holder.b.cellThumbImage.alpha = if (position == current) 1f else 0.85f
            Glide.with(holder.b.cellThumbImage)
                .load(models[position])
                .into(holder.b.cellThumbImage)
            holder.b.root.setOnClickListener { onClick(position) }
        }
    }
}
