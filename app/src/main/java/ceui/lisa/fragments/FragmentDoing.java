package ceui.lisa.fragments;

import androidx.databinding.ViewDataBinding;

import java.util.List;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.DoingAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.feature.worker.AbstractTask;
import ceui.lisa.feature.worker.Worker;
import ceui.lisa.interfaces.FeedBack;

public class FragmentDoing extends LocalListFragment<FragmentBaseListBinding, AbstractTask>{

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new DoingAdapter(allItems, mContext);
    }

    @Override
    protected void initData() {
        super.initData();
        Worker.get().setFeedBack(new FeedBack() {
            @Override
            public void doSomething() {
                allItems.remove(0);
                mAdapter.notifyItemRemoved(0);
                mAdapter.notifyItemRangeChanged(0, allItems.size());
            }
        });
    }

    @Override
    public void onDestroyView() {
        Worker.get().setFeedBack(null);
        super.onDestroyView();
    }

    @Override
    public BaseRepo repository() {
        return new LocalRepo<List<AbstractTask>>() {
            @Override
            public List<AbstractTask> first() {
                return Worker.get().getRunningTask();
            }

            @Override
            public List<AbstractTask> next() {
                return null;
            }
        };
    }

    @Override
    public String getToolbarTitle() {
        return "任务中心";
    }
}
