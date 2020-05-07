package ceui.lisa.adapters

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.RecyIllustStaggerNewBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.ImageViewTarget

class FAdapter(targetList: MutableList<IllustsBean>, context: Context) :
        BaseAdapter<IllustsBean, RecyIllustStaggerNewBinding>(targetList, context) {

    override fun initLayout() {
        mLayoutID = R.layout.recy_illust_stagger_new
    }

    override fun bindData(target: IllustsBean?, bindView: ViewHolder<RecyIllustStaggerNewBinding>?, position: Int) {
        bindView?.baseBind?.hide?.visibility = View.INVISIBLE
        bindView?.baseBind?.illustImage?.let {
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(target))
                    .placeholder(R.color.second_light_bg)
                    .into(object : ImageViewTarget<Drawable>(it) {
                        override fun setResource(resource: Drawable?) {
                            it.setImageDrawable(resource)
                        }
                    })
        }
    }
}