package ceui.lisa.adapters

import android.content.Context
import ceui.lisa.R
import ceui.lisa.databinding.RecyItemLiveBinding
import ceui.lisa.models.Live
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide

class LiveAdapter(targetList: MutableList<Live>, context: Context):
        BaseAdapter<Live, RecyItemLiveBinding>(targetList, context) {

    override fun bindData(target: Live?, bindView: ViewHolder<RecyItemLiveBinding>?, position: Int) {
        if(bindView != null) {
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(target?.thumbnail_image_url))
                    .into(bindView.baseBind.image)
        }
    }

    override fun initLayout() {
        mLayoutID = R.layout.recy_item_live
    }
}