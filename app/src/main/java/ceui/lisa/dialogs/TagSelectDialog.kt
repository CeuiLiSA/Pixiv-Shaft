//package ceui.lisa.dialogs
//
//import android.content.Intent
//import android.os.Bundle
//import android.support.v7.widget.LinearLayoutManager
//import android.support.v7.widget.RecyclerView
//import android.view.View
//import android.widget.ProgressBar
//import ceui.lisa.R
//import ceui.lisa.adapters.BookTagAdapter
//import ceui.lisa.fragments.FragmentLikeIllust
//import ceui.lisa.http.ErrorCtrl
//import ceui.lisa.http.Retro
//import ceui.lisa.interfaces.OnItemClickListener
//import ceui.lisa.model.BookmarkTags
//import ceui.lisa.utils.Channel
//import ceui.lisa.view.LinearItemDecoration
//import com.scwang.smartrefresh.layout.api.RefreshLayout
//import com.scwang.smartrefresh.layout.util.DensityUtil
//import io.reactivex.Observer
//import io.reactivex.android.schedulers.AndroidSchedulers
//import io.reactivex.disposables.Disposable
//import io.reactivex.schedulers.Schedulers
//import kotlinx.android.synthetic.main.dialog_select_tag.*
//import org.reactivestreams.Subscriber
//import org.reactivestreams.Subscription
//import java.util.*
//
//class TagSelectDialog : BaseDialog(){
//
//    var restrict : String = ""
//    var allItems : MutableList<BookmarkTags.BookmarkTagsBean> = ArrayList()
//    var adapter: BookTagAdapter? = null
//    var mRecyclerView: RecyclerView? = null
//    var mProgressBar: ProgressBar? = null
//    var mRefreshLayout: RefreshLayout? = null
//
//
//    override fun initView(v: View?): View? {
//        mRecyclerView = v?.findViewById(R.id.recyclerView)
//        mProgressBar = v?.findViewById(R.id.progress)
//        mRefreshLayout = v?.findViewById<RefreshLayout>(R.id.refreshLayout)
//        val layoutManager = LinearLayoutManager(mContext)
//        mRecyclerView?.layoutManager = layoutManager
//        mRecyclerView?.addItemDecoration(LinearItemDecoration(DensityUtil.dp2px(12.0f)))
//        return v
//    }
//
//    override fun initData() {
//        Retro.getAppApi().getBookmarkTags(mUserModel.response.access_token, mUserModel.response.user.id, restrict)
//                .subscribeOn(Schedulers.newThread())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(object : ErrorCtrl<BookmarkTags>(){
//                    override fun onNext(t: BookmarkTags) {
//                        allItems.addAll(t.bookmark_tags)
//                        adapter = BookTagAdapter(allItems, mContext)
//                        adapter?.setOnItemClickListener(object : OnItemClickListener{
//                            override fun onItemClick(v: View?, position: Int, viewType: Int) {
//                                val channel = Channel<Any>()
//                                channel.receiver = "FragmentLikeIllust " + FragmentLikeIllust.TYPE_PUBLUC
//                            }
//                        })
//                        mProgressBar?.visibility = View.INVISIBLE
//                        mRecyclerView?.adapter = adapter
//                    }
//
//                    override fun onError(e: Throwable) {
//                        super.onError(e)
//                        mProgressBar?.visibility = View.INVISIBLE
//                    }
//                })
//    }
//
//
//    override fun initLayout() {
//        mLayoutID = R.layout.dialog_select_tag
//    }
//
//
//    companion object {
//        fun newInstance(tagType : String): TagSelectDialog {
//            val tagSelectDialog = TagSelectDialog()
//            tagSelectDialog.restrict = tagType
//            return tagSelectDialog
//        }
//    }
//}