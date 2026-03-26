package ceui.lisa.adapters

import android.content.Context
import android.content.Intent
import android.view.View
import ceui.lisa.databinding.RecyWatchlistMangaBinding
import ceui.lisa.models.WatchlistMangaItem
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UserActivity
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide

class WatchlistMangaAdapter(
    list: MutableList<WatchlistMangaItem>,
    context: Context
) : BaseAdapter<WatchlistMangaItem, RecyWatchlistMangaBinding>(list, context) {
    override fun initLayout() {
        mLayoutID = R.layout.recy_watchlist_manga
    }

    override fun bindData(
        target: WatchlistMangaItem,
        bindView: ViewHolder<RecyWatchlistMangaBinding>,
        position: Int
    ) {
        if (isInvalidItem(target)) {
            bindView.baseBind.title.text = target.mask_text
            bindView.baseBind.author.text = ""
            bindView.baseBind.lastDate.text = ""
            bindView.baseBind.contentCount.text = ""
            bindView.baseBind.viewLatest.visibility = View.INVISIBLE
            bindView.baseBind.cover.visibility = View.INVISIBLE
            bindView.itemView.setOnClickListener {}
            bindView.baseBind.author.setOnClickListener {}
            bindView.baseBind.userHead.setOnClickListener {}
        } else {
            bindView.baseBind.title.text = target.title
            bindView.baseBind.author.text = target.user!!.name
            Glide.with(mContext).load(GlideUtil.getUrl(target.url!!)).into(bindView.baseBind.cover)
            bindView.baseBind.lastDate.text = target.last_published_content_datetime!!
            bindView.baseBind.contentCount.text = mContext.getString(R.string.episode_number, target.published_content_count)
            bindView.itemView.setOnClickListener {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(Params.MANGA_SERIES_ID, target.id)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
                mContext.startActivity(intent)
            }
            bindView.baseBind.viewLatest.setOnClickListener {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(Params.MANGA_SERIES_ID, target.id)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
                mContext.startActivity(intent)
            }
            bindView.baseBind.author.setOnClickListener {
                val intent = Intent(mContext, UserActivity::class.java)
                intent.putExtra(Params.USER_ID, target.user!!.id)
                mContext.startActivity(intent)
            }
            bindView.baseBind.userHead.setOnClickListener {
                val intent = Intent(mContext, UserActivity::class.java)
                intent.putExtra(Params.USER_ID, target.user!!.id)
                mContext.startActivity(intent)
            }
        }
        Glide.with(mContext).load(GlideUtil.getHead(target.user)).into(bindView.baseBind.userHead)
    }

    private fun isInvalidItem(target: WatchlistMangaItem): Boolean {
        return (target.title == "" && target.url == null
                && target.mask_text != null && target.user!!.id == 0)
    }
}
