package ceui.lisa.fragments;

import androidx.recyclerview.widget.RecyclerView;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfaces.ListShow;

public abstract class AutoClipFragment<N extends ListShow<R>,
        T extends RecyclerView.Adapter<RecyclerView.ViewHolder>,
        R> extends BaseListFragment<N, T, R> {

    @Override
    public void onResume() {
        super.onResume();
        if (mAdapter instanceof IllustStagAdapter && Shaft.sSettings.isStaggerAnime()) {
            ((IllustStagAdapter) mAdapter).flipToOrigin();
        }

        if (mAdapter instanceof IllustAdapter && Shaft.sSettings.isGridAnime()) {
            ((IllustAdapter) mAdapter).flipToOrigin();
        }
    }
}
