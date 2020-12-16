package ceui.lisa.adapters

import android.content.Context
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.RecyUserPreviewHorizontalBinding
import ceui.lisa.models.UserPreviewsBean
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide

class UserHAdapter(targetList: MutableList<UserPreviewsBean>, context: Context) :
        BaseAdapter<UserPreviewsBean, RecyUserPreviewHorizontalBinding>(targetList, context) {

    override fun initLayout() {
        mLayoutID = R.layout.recy_user_preview_horizontal
    }

    override fun bindData(target: UserPreviewsBean,
                          bindView: ViewHolder<RecyUserPreviewHorizontalBinding>, position: Int) {
        bindView.baseBind.userName.text = allIllust[position].user.name
        Glide.with(mContext)
                .load(GlideUtil.getUrl(allIllust[position].user.profile_image_urls.medium))
                .placeholder(R.color.light_bg)
                .into(bindView.baseBind.userHead)
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener { v: View? ->
                mOnItemClickListener.onItemClick(v, position, 0) }
        }
    }
}