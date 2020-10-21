package ceui.lisa.fragments

import android.os.Bundle
import androidx.databinding.ViewDataBinding
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListNovel
import ceui.lisa.models.NovelBean
import ceui.lisa.repo.PopularNovelRepo
import ceui.lisa.utils.Params

class FragmentPopularNovel : NetListFragment<FragmentBaseListBinding, ListNovel, NovelBean>() {

    private var word = ""

    override fun initBundle(bundle: Bundle) {
        word = bundle.getString(Params.KEY_WORD) ?: ""
    }

    companion object {
        @JvmStatic
        fun newInstance(word: String): FragmentPopularNovel =
                FragmentPopularNovel().apply {
                    arguments = Bundle().apply {
                        putString(Params.KEY_WORD, word)
                    }
                }
    }

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return PopularNovelRepo(word)
    }
}