package ceui.lisa.fragments

import android.os.Bundle
import android.view.View
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
    private var hideToolbar: Boolean = false

    override fun initBundle(bundle: Bundle) {
        super.initBundle(bundle)
        bundle.getString(ARG_RESTRICT)?.takeIf { it.isNotEmpty() }?.let { restrict = it }
        hideToolbar = bundle.getBoolean(ARG_HIDE_TOOLBAR, false)
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (hideToolbar) {
            // 嵌入 FragmentRight 时父页面已经有自己的 toolbar，隐藏自带的避免重复。
            view.findViewById<View>(R.id.toolbar)?.visibility = View.GONE
        }
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
        const val ARG_HIDE_TOOLBAR = "hide_toolbar"

        @JvmStatic
        fun newInstance(restrict: String): FragmentNewNovels {
            return FragmentNewNovels().apply {
                arguments = Bundle().apply {
                    putString(ARG_RESTRICT, restrict)
                    putBoolean(ARG_HIDE_TOOLBAR, true)
                }
            }
        }
    }
}
