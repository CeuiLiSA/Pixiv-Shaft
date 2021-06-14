package ceui.lisa.adapters

import android.content.Context
import android.widget.ImageView
import ceui.lisa.R
import ceui.lisa.databinding.RecyFeatureBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.MangaSeriesItem
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Locale

class FeatureAdapter(
    targetList: MutableList<FeatureEntity>,
    context: Context,
) : BaseAdapter<FeatureEntity, RecyFeatureBinding>(targetList, context) {

    val sdr = SimpleDateFormat(context.resources.getString(R.string.string_351), Locale.getDefault())

    override fun initLayout() {
        mLayoutID = R.layout.recy_feature
    }

    override fun bindData(
        target: FeatureEntity,
        bindView: ViewHolder<RecyFeatureBinding>,
        position: Int,
    ) {
        val times: String = sdr.format(target.dateTime)
        bindView.baseBind.starSize.text = times

        val views: List<ImageView> = Arrays.asList(
            bindView.baseBind.userShowOne,
            bindView.baseBind.userShowTwo,
            bindView.baseBind.userShowThree
        )
        var shows: List<Serializable> =
            ArrayList(target.allIllust.subList(0, Math.min(3, target.allIllust.size)))
        if (shows.size < 3) {
            shows += target.allMangaSeries.subList(0, Math.min(3 - shows.size, target.allMangaSeries.size))
        }

        for (index in 0..2) {
            val item: Serializable? = shows.getOrNull(index)
            var url: GlideUrl? = null
            if (item is IllustsBean) {
                url = GlideUtil.getMediumImg(item)
            } else if (item is MangaSeriesItem) {
                url = GlideUtil.getUrl(item.cover_image_urls.medium)
            }
            Glide.with(mContext)
                .load(url)
                .placeholder(R.color.light_bg)
                .into(views.get(index))
        }

        bindView.baseBind.illustCount.text = target.dataType
        bindView.baseBind.deleteItem.setOnClickListener {
            mOnItemClickListener.onItemClick(it, position, 1)
        }
        bindView.itemView.setOnClickListener {
            mOnItemClickListener.onItemClick(it, position, 0)
        }
    }
}
