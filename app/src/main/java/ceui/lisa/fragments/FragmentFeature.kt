package ceui.lisa.fragments

import android.content.Intent
import android.text.TextUtils
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.FeatureAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.core.LocalRepo
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import com.google.gson.reflect.TypeToken
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MessageDialogBuilder
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction

class FragmentFeature : LocalListFragment<FragmentBaseListBinding, FeatureEntity>() {

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return FeatureAdapter(allItems, mContext).setOnItemClickListener { v, position, viewType ->
            if (viewType == 0) {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, allItems[position].dataType)
                intent.putExtra(Params.USER_ID, allItems[position].userID)
                intent.putExtra(Params.ILLUST_ID, allItems[position].illustID)
                intent.putExtra(Params.ILLUST_TITLE, allItems[position].illustTitle)
                startActivity(intent)
            } else if (viewType == 1) {
                MessageDialogBuilder(activity)
                    .setTitle(getString(R.string.string_143))
                    .setMessage(getString(R.string.string_252))
                    .setSkinManager(QMUISkinManager.defaultInstance(context))
                    .addAction(getString(R.string.string_142)) { dialog, index -> dialog.dismiss() }
                    .addAction(
                        0,
                        getString(R.string.string_141),
                        QMUIDialogAction.ACTION_PROP_NEGATIVE
                    ) { dialog, index ->
                        AppDatabase.getAppDatabase(mContext).downloadDao()
                            .deleteFeature(allItems[position])
                        Common.showToast<String>(getString(R.string.string_220))
                        dialog.dismiss()
                        allItems.removeAt(position)
                        mAdapter.notifyItemRemoved(position)
                        mAdapter.notifyItemRangeChanged(position, allItems.size - position)
                    }
                    .show()
            }
        }
    }

    override fun initView() {
        super.initView()
        baseBind.toolbar.inflateMenu(R.menu.delete_all)
        baseBind.toolbar.setOnMenuItemClickListener(object : Toolbar.OnMenuItemClickListener {
            override fun onMenuItemClick(item: MenuItem?): Boolean {
                if (item?.itemId == R.id.action_delete) {
                    if (Common.isEmpty(allItems)) {
                        Common.showToast(getString(R.string.string_254))
                        return true
                    }
                    MessageDialogBuilder(activity)
                        .setTitle(getString(R.string.string_143))
                        .setMessage(getString(R.string.string_253))
                        .setSkinManager(QMUISkinManager.defaultInstance(context))
                        .addAction(getString(R.string.string_142)) { dialog, index -> dialog.dismiss() }
                        .addAction(
                            0,
                            getString(R.string.string_141),
                            QMUIDialogAction.ACTION_PROP_NEGATIVE
                        ) { dialog, index ->
                            AppDatabase.getAppDatabase(mContext).downloadDao().deleteAllFeature()
                            Common.showToast<String>(getString(R.string.string_220))
                            dialog.dismiss()
                            mAdapter.clear()
                            emptyRela.visibility = View.VISIBLE
                        }
                        .show()
                    return true
                }
                return false
            }
        })
    }

    override fun repository(): BaseRepo {
        return object : LocalRepo<List<FeatureEntity>>() {
            override fun first(): List<FeatureEntity> {
                return AppDatabase.getAppDatabase(mContext)
                    .downloadDao()
                    .getFeatureList(PAGE_SIZE, 0)
            }

            override fun next(): List<FeatureEntity>? {
                return AppDatabase.getAppDatabase(mContext)
                    .downloadDao()
                    .getFeatureList(PAGE_SIZE, allItems.size)
            }
        }
    }

    override fun onFirstLoaded(items: MutableList<FeatureEntity>) {
        super.onFirstLoaded(items)
        for (item in items) {
            if (!TextUtils.isEmpty(item.illustJson)) {
                item.allIllust = Shaft.sGson.fromJson<List<IllustsBean>>(
                    item.illustJson,
                    object : TypeToken<List<IllustsBean>>() {}.type
                )
            }
        }
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_249)
    }
}
