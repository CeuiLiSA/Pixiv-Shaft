package ceui.lisa.adapters

import android.content.Context
import ceui.lisa.R
import ceui.lisa.databinding.RecyArticalHorizonBinding
import ceui.lisa.models.SpotlightArticlesBean
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide
import android.view.View as View1

class PivisionHAdapter(targetList: MutableList<SpotlightArticlesBean>, context: Context) :
        BaseAdapter<SpotlightArticlesBean, RecyArticalHorizonBinding>(targetList, context) {

    override fun initLayout() {
        mLayoutID = R.layout.recy_artical_horizon
    }

    override fun bindData(target: SpotlightArticlesBean,
                          bindView: ViewHolder<RecyArticalHorizonBinding>, position: Int) {
        bindView.baseBind.title.text = allIllust[position].title
        Glide.with(mContext)
                .load(GlideUtil.getUrl(allIllust[position].thumbnail))
                .into(bindView.baseBind.illustImage)
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener { v: View1? ->
                mOnItemClickListener.onItemClick(v, position, 0) }
        }
    }
}