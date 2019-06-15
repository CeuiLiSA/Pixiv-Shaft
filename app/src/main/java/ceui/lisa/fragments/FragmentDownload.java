//package ceui.lisa.fragments;
//
//import android.os.Bundle;
//import android.view.View;
//
//import org.greenrobot.eventbus.EventBus;
//import org.greenrobot.eventbus.Subscribe;
//import org.greenrobot.eventbus.ThreadMode;
//
//import java.util.List;
//
//import ceui.lisa.adapters.DownloadTaskAdapter;
//import ceui.lisa.database.IllustTask;
//import ceui.lisa.download.TaskQueue;
//import ceui.lisa.interfaces.OnItemClickListener;
//import ceui.lisa.utils.Channel;
//import ceui.lisa.utils.Common;
//
//public class FragmentDownload extends BaseDataFragment<DownloadTaskAdapter, IllustTask> {
//
//    @Override
//    List<IllustTask> initList() {
//        return TaskQueue.get().getTasks();
//    }
//
//    @Override
//    View initView(View v) {
//        super.initView(v);
//        mRefreshLayout.setEnableLoadMore(false);
//        mRefreshLayout.setEnableRefresh(false);
//        return v;
//    }
//
//    @Override
//    boolean showToolbar() {
//        return false;
//    }
//
//    @Override
//    void initAdapter() {
//        mAdapter = new DownloadTaskAdapter(allItems, mContext);
//        mAdapter.setOnItemClickListener(new OnItemClickListener() {
//            @Override
//            public void onItemClick(View v, int position, int viewType) {
//                Common.showLog(className + position);
//            }
//        });
//    }
//
//    @Subscribe(threadMode = ThreadMode.MAIN)
//    public void onMessageEvent(Channel event) {
//        if(className.contains(event.getReceiver())) {
//            int position = (int) event.getObject();
//            mAdapter.notifyItemRemoved(position);
//            mAdapter.notifyItemRangeChanged(position, allItems.size() - position);
//
//            Common.showLog(className + "删除一个 还剩 " + TaskQueue.get().getTasks().size());
//            if(position == 0){
//                getFirstData();
//            }
//        }
//    }
//
//    @Override
//    public void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        EventBus.getDefault().register(this);
//        Common.showLog(className + "EVENTBUS 注册了");
//    }
//
//    @Override
//    public void onDestroy() {
//        EventBus.getDefault().unregister(this);
//        super.onDestroy();
//        Common.showLog(className + "EVENTBUS 取消注册了");
//    }
//}
