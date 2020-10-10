package ceui.lisa.fragments

import android.content.Intent
import android.text.TextUtils
import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.FratureAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.core.LocalRepo
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import com.google.gson.reflect.TypeToken

class FragmentFeature: LocalListFragment<FragmentBaseListBinding, FeatureEntity>() {

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return FratureAdapter(allItems, mContext).setOnItemClickListener { v, position, viewType ->
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, allItems[position].dataType)
            intent.putExtra(Params.USER_ID, allItems[position].userID)
            startActivity(intent)
        }
    }

    override fun repository(): BaseRepo {
        return object :LocalRepo<List<FeatureEntity>>(){
            override fun first(): List<FeatureEntity> {
                return AppDatabase.getAppDatabase(mContext).downloadDao().featureList
            }

            override fun next(): List<FeatureEntity>? {
                return null
            }
        }
    }

    override fun onFirstLoaded(items: MutableList<FeatureEntity>) {
        super.onFirstLoaded(items)
        for (item in items) {
            if (!TextUtils.isEmpty(item.illustJson)) {
                item.allIllust = Shaft.sGson.fromJson<List<IllustsBean>>(item.illustJson,
                        object : TypeToken<List<IllustsBean>>() {}.type)
            }
        }
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_249)
    }
}