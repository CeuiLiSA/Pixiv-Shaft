package ceui.lisa.adapters

import android.content.Context
import ceui.lisa.R
import ceui.lisa.databinding.RecyFeatureBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide

class FratureAdapter(
        targetList: MutableList<FeatureEntity>,
        context: Context
): BaseAdapter<FeatureEntity, RecyFeatureBinding>(targetList, context) {

    override fun initLayout() {
        mLayoutID = R.layout.recy_feature
    }

    override fun bindData(target: FeatureEntity, bindView: ViewHolder<RecyFeatureBinding>, position: Int) {
        bindView.baseBind.starSize.text = target.uuid
        if (target.allIllust != null && target.allIllust.size >= 3) {
            Glide.with(mContext).load(GlideUtil.getMediumImg(target
                    .allIllust[0]))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.userShowOne)
            Glide.with(mContext).load(GlideUtil.getMediumImg(target
                    .allIllust[1]))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.userShowTwo)
            Glide.with(mContext).load(GlideUtil.getMediumImg(target
                    .allIllust[2]))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.userShowThree)
        } else {
            Glide.with(mContext).load(R.color.light_bg)
                    .into(bindView.baseBind.userShowOne)
            Glide.with(mContext).load(R.color.light_bg)
                    .into(bindView.baseBind.userShowTwo)
            Glide.with(mContext).load(R.color.light_bg)
                    .into(bindView.baseBind.userShowThree)
        }
        bindView.baseBind.illustCount.text = target.dataType
        bindView.itemView.setOnClickListener {
            mOnItemClickListener.onItemClick(it, position, 0)
        }
    }
}