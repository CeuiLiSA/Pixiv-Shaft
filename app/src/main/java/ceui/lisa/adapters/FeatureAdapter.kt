package ceui.lisa.adapters

import android.content.Context
import ceui.lisa.R
import ceui.lisa.databinding.RecyFeatureBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale

class FeatureAdapter(
    targetList: MutableList<FeatureEntity>,
    context: Context
) : BaseAdapter<FeatureEntity, RecyFeatureBinding>(targetList, context) {

    val sdr = SimpleDateFormat(context.resources.getString(R.string.string_351), Locale.getDefault())

    override fun initLayout() {
        mLayoutID = R.layout.recy_feature
    }

    override fun bindData(
        target: FeatureEntity,
        bindView: ViewHolder<RecyFeatureBinding>,
        position: Int
    ) {
        val times: String = sdr.format(target.dateTime)
        bindView.baseBind.starSize.text = times

        if (!Common.isEmpty(target.allIllust)) {
            when {
                target.allIllust.size >= 3 -> {
                    Glide.with(mContext).load(
                        GlideUtil.getMediumImg(
                            target
                                .allIllust[0]
                        )
                    )
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.userShowOne)
                    Glide.with(mContext).load(
                        GlideUtil.getMediumImg(
                            target
                                .allIllust[1]
                        )
                    )
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.userShowTwo)
                    Glide.with(mContext).load(
                        GlideUtil.getMediumImg(
                            target
                                .allIllust[2]
                        )
                    )
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.userShowThree)
                }
                target.allIllust.size == 2 -> {
                    Glide.with(mContext).load(
                        GlideUtil.getMediumImg(
                            target
                                .allIllust[0]
                        )
                    )
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.userShowOne)
                    Glide.with(mContext).load(
                        GlideUtil.getMediumImg(
                            target
                                .allIllust[1]
                        )
                    )
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.userShowTwo)
                    Glide.with(mContext).load(R.color.light_bg)
                        .into(bindView.baseBind.userShowThree)
                }
                target.allIllust.size == 1 -> {
                    Glide.with(mContext).load(
                        GlideUtil.getMediumImg(
                            target
                                .allIllust[0]
                        )
                    )
                        .placeholder(R.color.light_bg)
                        .into(bindView.baseBind.userShowOne)
                    Glide.with(mContext).load(R.color.light_bg)
                        .into(bindView.baseBind.userShowTwo)
                    Glide.with(mContext).load(R.color.light_bg)
                        .into(bindView.baseBind.userShowThree)
                }
            }
        } else {
            Glide.with(mContext).load(R.color.light_bg)
                .into(bindView.baseBind.userShowOne)
            Glide.with(mContext).load(R.color.light_bg)
                .into(bindView.baseBind.userShowTwo)
            Glide.with(mContext).load(R.color.light_bg)
                .into(bindView.baseBind.userShowThree)
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
