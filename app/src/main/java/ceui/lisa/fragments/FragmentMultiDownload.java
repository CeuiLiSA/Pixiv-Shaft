package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.MultiDownloadAdapter;
import ceui.lisa.core.TextWritter;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.DownloadItemDecoration;
import gdut.bsx.share2.FileUtil;
import gdut.bsx.share2.Share2;
import gdut.bsx.share2.ShareContentType;

public class FragmentMultiDownload extends BaseAsyncFragment<MultiDownloadAdapter, IllustsBean> {

    private FloatingActionButton download;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_multi_download;
    }

    @Override
    View initView(View v) {
        super.initView(v);
        ((TemplateActivity) getActivity()).setSupportActionBar(mToolbar);
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
        allItems.addAll(DataChannel.get().getDownloadList());
        for (int i = 0; i < allItems.size(); i++) {
            allItems.get(i).setChecked(true);
        }
        showFirstData();
    }

    @Override
    String getToolbarTitle() {
        int selectCount = 0;
        for (int i = 0; i < allItems.size(); i++) {
            if (allItems.get(i).isChecked()) {
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
                DataChannel.get().setIllustList(allItems);
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
        mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
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
                if (!allItems.get(i).isChecked()) {
                    allItems.get(i).setChecked(true);
                }
            }
            mAdapter.notifyDataSetChanged();
        } else if (item.getItemId() == R.id.action_2) {
            for (int i = 0; i < allItems.size(); i++) {
                if (allItems.get(i).isChecked()) {
                    allItems.get(i).setChecked(false);
                }
            }
            mAdapter.notifyDataSetChanged();
        } else if (item.getItemId() == R.id.action_3) {
            StringBuilder content = new StringBuilder();
            for (IllustsBean illustsBean : DataChannel.get().getDownloadList()) {
                if(illustsBean.getPage_count() == 1) {
                    content.append(illustsBean.getMeta_single_page().getOriginal_image_url());
                    content.append("\n");
                }else {
                    for (int i = 0; i < illustsBean.getPage_count(); i++) {
                        content.append(illustsBean.getMeta_pages().get(i).getImage_urls().getMaxImage());
                        content.append("\n");
                    }
                }
            }
            TextWritter.writeToTxt(System.currentTimeMillis() + "_log.txt",
                    content.toString(), new Callback<File>() {
                        @Override
                        public void doSomething(File t) {
                            new Share2.Builder(mActivity)
                                    .setContentType(ShareContentType.FILE)
                                    .setShareFileUri(FileUtil.getFileUri(mContext, ShareContentType.FILE, t))
                                    .setTitle("Share File")
                                    .build()
                                    .shareBySystem();
                        }
                    });
        }
        return super.onOptionsItemSelected(item);
    }
}
