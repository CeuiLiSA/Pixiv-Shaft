package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.GridLayoutManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.adapters.MultiDownloadAdapter;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.DownloadItemDecoration;
import ceui.lisa.view.GridItemDecoration;
import ceui.lisa.view.GridScrollChangeManager;
import ceui.lisa.view.TagItemDecoration;
import io.reactivex.ObservableEmitter;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentMultiDownload extends BaseAsyncFragment<MultiDownloadAdapter, IllustsBean> {

    private FloatingActionButton download;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_multi_download;
    }

    @Override
    View initView(View v) {
        super.initView(v);
        ((TemplateFragmentActivity)getActivity()).setSupportActionBar(mToolbar);
        download = v.findViewById(R.id.start_download);
        download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IllustDownload.downloadAllIllust(allItems);
            }
        });
        return v;
    }

    @Override
    public void getFirstData() {
        allItems.clear();
        allItems.addAll(IllustChannel.get().getDownloadList());
        for (int i = 0; i < allItems.size(); i++) {
            allItems.get(i).setChecked(true);
        }
        showFirstData();
    }

    @Override
    String getToolbarTitle() {
        int selectCount = 0;
        for (int i = 0; i < allItems.size(); i++) {
            if(allItems.get(i).isChecked()){
                selectCount++;
            }
        }
        return "下载" + selectCount + "个作品";
    }

    @Override
    public void initAdapter() {
        mAdapter = new MultiDownloadAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                IllustChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
        mAdapter.setCallback(new Callback() {
            @Override
            public void doSomething(Object t) {
                mToolbar.setTitle(getToolbarTitle());
            }
        });
    }

    @Override
    public void initRecyclerView() {
        GridLayoutManager manager = new GridLayoutManager(mContext, 3);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new DownloadItemDecoration(2,
                DensityUtil.dp2px(1.0f)));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.download_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_1) {
            for (int i = 0; i < allItems.size(); i++) {
                if(!allItems.get(i).isChecked()){
                    allItems.get(i).setChecked(true);
                }
            }
            mAdapter.notifyDataSetChanged();
        }else if(item.getItemId() == R.id.action_2){
            for (int i = 0; i < allItems.size(); i++) {
                if(allItems.get(i).isChecked()){
                    allItems.get(i).setChecked(false);
                }
            }
            mAdapter.notifyDataSetChanged();
        }
        return super.onOptionsItemSelected(item);
    }
}
