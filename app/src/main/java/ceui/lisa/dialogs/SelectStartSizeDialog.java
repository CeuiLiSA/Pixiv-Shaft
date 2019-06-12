package ceui.lisa.dialogs;

import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;

import org.greenrobot.eventbus.EventBus;

import ceui.lisa.R;
import ceui.lisa.adapters.StarSizeAdapter;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.utils.Channel;

public class SelectStartSizeDialog extends BaseDialog {

    private RecyclerView mRecyclerView;
    private static final String[] ALL_SIZE = new String[]{" 500", " 1000", " 2500", " 5000", " 7500", " 10000", " 25000", " 50000"};
    private int index = 5;

    @Override
    void initLayout() {
        mLayoutID = R.layout.dialog_select_star_size;
    }

    @Override
    View initView(View v) {
        mRecyclerView = v.findViewById(R.id.recyclerView);
        LinearLayoutManager manager = new LinearLayoutManager(mContext);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.setHasFixedSize(true);
        StarSizeAdapter adapter = new StarSizeAdapter(ALL_SIZE, mContext);
        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                index = position;
            }
        });
        mRecyclerView.setAdapter(adapter);
        sure = v.findViewById(R.id.sure);
        sure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Channel channel = new Channel();
                channel.setReceiver("FragmentSearchResult");
                channel.setObject(ALL_SIZE[index]);
                EventBus.getDefault().post(channel);
                dismiss();
            }
        });
        cancel = v.findViewById(R.id.cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        return v;
    }


    @Override
    void initData() {

    }
}
