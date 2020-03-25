package ceui.lisa.fragments;

import android.content.Intent;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;

import java.io.File;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.MultiDownldAdapter;
import ceui.lisa.helper.TextWriter;
import ceui.lisa.databinding.FragmentMultiDownloadBinding;
import ceui.lisa.databinding.RecyMultiDownloadBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.core.BaseCtrl;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.core.DataControl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.DownloadItemDecoration;
import gdut.bsx.share2.FileUtil;
import gdut.bsx.share2.Share2;
import gdut.bsx.share2.ShareContentType;

public class FragmentMultiDownld extends LocalListFragment<FragmentMultiDownloadBinding,
        IllustsBean, RecyMultiDownloadBinding> {

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_multi_download;
    }

    @Override
    public void initView(View view) {
        super.initView(view);
        baseBind.toolbar.inflateMenu(R.menu.download_menu);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
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
                    for (IllustsBean illustsBean : allItems) {
                        if (illustsBean.isChecked()) {
                            if (illustsBean.getPage_count() == 1) {
                                content.append(illustsBean.getMeta_single_page().getOriginal_image_url());
                                content.append("\n");
                            } else {
                                for (int i = 0; i < illustsBean.getPage_count(); i++) {
                                    content.append(illustsBean.getMeta_pages().get(i).getImage_urls().getMaxImage());
                                    content.append("\n");
                                }
                            }
                        }
                    }
                    String result = content.toString();
                    if (TextUtils.isEmpty(result)) {
                        Common.showToast("没有选择任何作品");
                    } else {
                        TextWriter.writeToTxt(System.currentTimeMillis() + "_download_tasks.txt",
                                result, new Callback<File>() {
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
                }
                return false;
            }
        });
        baseBind.startDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IllustDownload.downloadAllIllust(mActivity, allItems);
            }
        });
    }

    @Override
    public BaseAdapter<IllustsBean, RecyMultiDownloadBinding> adapter() {
        MultiDownldAdapter adapter = new MultiDownldAdapter(allItems, mContext);
        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                DataChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
        adapter.setCallback(new Callback() {
            @Override
            public void doSomething(Object t) {
                mToolbar.setTitle(getToolbarTitle());
            }
        });
        return adapter;
    }

    @Override
    public BaseCtrl present() {
        return new DataControl<List<IllustsBean>>() {
            @Override
            public List<IllustsBean> first() {
                return DataChannel.get().getDownloadList();
            }

            @Override
            public List<IllustsBean> next() {
                return null;
            }
        };
    }

    @Override
    public void initRecyclerView() {
        GridLayoutManager manager = new GridLayoutManager(mContext, 3);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new DownloadItemDecoration(2,
                DensityUtil.dp2px(1.0f)));
    }

    @Override
    public String getToolbarTitle() {
        if (allItems.size() == 0) {
            return "计算中";
        } else {
            int selectCount = 0;
            int fileCount = 0;
            for (int i = 0; i < allItems.size(); i++) {
                if (allItems.get(i).isChecked()) {
                    fileCount = fileCount + allItems.get(i).getPage_count();
                    selectCount++;
                }
            }
            return selectCount + "个插画, 共" + fileCount + "个文件";
        }
    }

    @Override
    public void onFirstLoaded(List<IllustsBean> illustsBeans) {
        initToolbar(baseBind.toolbar);
    }
}
