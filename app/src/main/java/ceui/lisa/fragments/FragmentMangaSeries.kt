package ceui.lisa.fragments

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.MangaSeriesAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.model.ListMangaSeries
import ceui.lisa.models.MangaSeriesItem
import ceui.lisa.repo.MangaSeriesRepo
import ceui.lisa.utils.Common
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecorationNoLRTB

class FragmentMangaSeries :
    NetListFragment<FragmentBaseListBinding, ListMangaSeries, MangaSeriesItem>() {

    private var userID: Int = 0

    override fun initBundle(bundle: Bundle) {
        userID = bundle.getInt(Params.USER_ID)
    }

    companion object {
        @JvmStatic
        fun newInstance(userID: Int): FragmentMangaSeries {
            return FragmentMangaSeries().apply {
                arguments = Bundle().apply {
                    putInt(Params.USER_ID, userID)
                }
            }
        }
    }

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return MangaSeriesAdapter(
            allItems,
            mContext
        ).setOnItemClickListener { v, position, viewType ->
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
            intent.putExtra(Params.ID, allItems[position].id)
            startActivity(intent)
        }
    }

    override fun repository(): BaseRepo {
        return MangaSeriesRepo(userID)
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_230)
    }

    override fun initRecyclerView() {
        mRecyclerView.layoutManager = LinearLayoutManager(mContext)
        mRecyclerView.addItemDecoration(LinearItemDecorationNoLRTB(DensityUtil.dp2px(1.0f)))
    }

    override fun initView() {
        super.initView()
        baseBind.toolbar.inflateMenu(R.menu.local_save)
        baseBind.toolbar.setOnMenuItemClickListener(
            Toolbar.OnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_bookmark) {
                    val entity = FeatureEntity()
                    entity.uuid = userID.toString() + "漫画系列作品"
                    entity.dataType = "漫画系列作品"
                    entity.userID = userID
                    entity.dateTime = System.currentTimeMillis()
                    AppDatabase.getAppDatabase(mContext).downloadDao().insertFeature(entity)
                    Common.showToast("已收藏到精华")
                    return@OnMenuItemClickListener true
                }
                false
            }
        )
    }
}
