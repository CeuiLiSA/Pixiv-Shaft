package ceui.lisa.adapters

import android.content.Context
import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.RecyNovelSeriesOfUserBinding
import ceui.lisa.models.NovelSeriesItem
import ceui.lisa.utils.Params

class NovelSeriesAdapter(
    list: MutableList<NovelSeriesItem>,
    context: Context
) : BaseAdapter<NovelSeriesItem, RecyNovelSeriesOfUserBinding>(list, context) {

    override fun initLayout() {
        mLayoutID = R.layout.recy_novel_series_of_user
    }

    override fun bindData(
        target: NovelSeriesItem,
        bindView: ViewHolder<RecyNovelSeriesOfUserBinding>,
        position: Int
    ) {
        bindView.baseBind.title.text = target.title
        bindView.baseBind.description.text = target.display_text
        bindView.itemView.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(Params.ID, allItems[position].id)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列详情")
            mContext.startActivity(intent)
        }
    }
}
