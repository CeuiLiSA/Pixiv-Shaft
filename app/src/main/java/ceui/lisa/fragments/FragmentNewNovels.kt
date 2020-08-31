package ceui.lisa.fragments

import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import ceui.lisa.models.NovelBean
import io.reactivex.Observable

class FragmentNewNovels: NetListFragment<FragmentBaseListBinding, ListNovel, NovelBean>(){

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return object : RemoteRepo<ListNovel>() {
            override fun initApi(): Observable<ListNovel> {
                return Retro.getAppApi().getBookedUserSubmitNovel(token())
            }

            override fun initNextApi(): Observable<ListNovel> {
                return Retro.getAppApi().getNextNovel(token(), mModel.nextUrl)
            }
        }
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_197)
    }
}