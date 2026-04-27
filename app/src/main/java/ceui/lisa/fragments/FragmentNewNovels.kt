package ceui.lisa.fragments

import android.os.Bundle
import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListNovel
import ceui.lisa.models.NovelBean
import ceui.lisa.repo.NewNovelRepo
import ceui.lisa.utils.Params

class FragmentNewNovels : NetListFragment<FragmentBaseListBinding, ListNovel, NovelBean>() {

    private var restrict: String = Params.TYPE_ALL

    override fun initBundle(bundle: Bundle) {
        super.initBundle(bundle)
        bundle.getString(ARG_RESTRICT)?.takeIf { it.isNotEmpty() }?.let { restrict = it }
    }

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return NewNovelRepo(restrict)
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_197)
    }

    // FragmentRight 切换 全部/公开/私人 时复用本 fragment，按新 restrict 重拉。
    fun setRestrict(restrict: String) {
        if (this.restrict == restrict) return
        this.restrict = restrict
        (mRemoteRepo as? NewNovelRepo)?.restrict = restrict
        if (isAdded) {
            forceRefresh()
        }
    }

    companion object {
        const val ARG_RESTRICT = "restrict"

        @JvmStatic
        fun newInstance(restrict: String): FragmentNewNovels {
            return FragmentNewNovels().apply {
                arguments = Bundle().apply { putString(ARG_RESTRICT, restrict) }
            }
        }
    }
}
