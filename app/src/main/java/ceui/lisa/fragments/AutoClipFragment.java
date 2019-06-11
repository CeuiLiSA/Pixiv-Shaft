package ceui.lisa.fragments;

import android.support.v7.widget.RecyclerView;

import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfaces.ListShow;

public abstract class AutoClipFragment<N extends ListShow<R>,
        T extends RecyclerView.Adapter<RecyclerView.ViewHolder>,
        R> extends BaseListFragment<N, T, R> {

    @Override
    public void onResume() {
        super.onResume();
        if(mAdapter instanceof IllustStagAdapter){
            ((IllustStagAdapter) mAdapter).flipToOrigin();
        }
    }
}
