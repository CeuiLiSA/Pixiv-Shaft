package ceui.lisa.adapters

import android.content.Context
import android.content.Intent
import android.view.View
import ceui.lisa.databinding.RecyWatchlistNovelBinding
import ceui.lisa.models.WatchlistNovelItem
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import com.bumptech.glide.Glide

class WatchlistNovelAdapter(
    list: MutableList<WatchlistNovelItem>,
    context: Context
) : BaseAdapter<WatchlistNovelItem, RecyWatchlistNovelBinding>(list, context) {
    override fun initLayout() {
        mLayoutID = R.layout.recy_watchlist_novel
    }

    override fun bindData(
        target: WatchlistNovelItem,
        bindView: ViewHolder<RecyWatchlistNovelBinding>,
        position: Int
    ) {
        if (isInvalidItem(target)) {
            bindView.baseBind.title.text = target.mask_text
            bindView.baseBind.author.text = ""
            bindView.baseBind.lastDate.text = ""
            bindView.baseBind.contentCount.text = ""
            bindView.baseBind.readLatest.visibility = View.INVISIBLE
            bindView.baseBind.cover.visibility = View.INVISIBLE
            bindView.itemView.setOnClickListener {}
        } else {
            bindView.baseBind.title.text = target.title
            bindView.baseBind.author.text = target.user!!.name
            Glide.with(mContext).load(GlideUtil.getUrl(target.url!!)).into(bindView.baseBind.cover)
            bindView.baseBind.lastDate.text = target.last_published_content_datetime!!
            bindView.baseBind.contentCount.text = "%d话".format(target.published_content_count)
            bindView.itemView.setOnClickListener {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(Params.ID, target.id)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列详情")
                mContext.startActivity(intent)
            }
            bindView.baseBind.readLatest.setOnClickListener {
                PixivOperate.getNovelByID(Shaft.sUserModel, target.latest_content_id!!.toLong(), mContext, null)
            }
        }
        Glide.with(mContext).load(GlideUtil.getHead(target.user)).into(bindView.baseBind.userHead)
    }

    private fun isInvalidItem(target: WatchlistNovelItem): Boolean {
        // 表示できない作品です
        return (target.title == "" && target.url == null
                && target.mask_text != null && target.user!!.id == 0)
    }

}