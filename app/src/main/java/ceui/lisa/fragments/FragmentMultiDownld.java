package ceui.lisa.fragments;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.MultiDownldAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.Container;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.FragmentMultiDownloadBinding;
import ceui.lisa.databinding.RecyMultiDownloadBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.feature.worker.BatchStarTask;
import ceui.lisa.feature.worker.Worker;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.DownloadItemDecoration;
import gdut.bsx.share2.Share2;
import gdut.bsx.share2.ShareContentType;

public class FragmentMultiDownld extends LocalListFragment<FragmentMultiDownloadBinding,
        IllustsBean> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_multi_download;
    }

    @Override
    public void initView() {
        super.initView();
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
                                    content.append(illustsBean.getMeta_pages().get(i).getImage_urls().getOriginal());
                                    content.append("\n");
                                }
                            }
                        }
                    }
                    String result = content.toString();
                    if (TextUtils.isEmpty(result)) {
                        Common.showToast("没有选择任何作品");
                    } else {
                        IllustDownload.downloadNovel((BaseActivity<?>) mContext,
                                System.currentTimeMillis() + "_download_tasks.txt", result,
                                new Callback<Uri>() {
                                    @Override
                                    public void doSomething(Uri t) {
                                        new Share2.Builder(mActivity)
                                                .setContentType(ShareContentType.FILE)
                                                .setShareFileUri(t)
                                                .setTitle("Share File")
                                                .build()
                                                .shareBySystem();
                                    }
                                });
                    }
                } else if (item.getItemId() == R.id.action_4) {
                    for (IllustsBean allItem : allItems) {
                        BatchStarTask task = new BatchStarTask(allItem.getUser().getName(),
                                allItem.getId(), 0);
                        Worker.get().addTask(task);
                    }
                    Worker.get().start();
                } else if (item.getItemId() == R.id.action_5) {
                    for (IllustsBean allItem : allItems) {
                        BatchStarTask task = new BatchStarTask(allItem.getUser().getName(),
                                allItem.getId(), 1);
                        Worker.get().addTask(task);
                    }
                    Worker.get().start();
                }
                return false;
            }
        });
        baseBind.startDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IllustDownload.downloadAllIllust(allItems, (BaseActivity<?>) mContext);
            }
        });
    }

    @Override
    public BaseAdapter<IllustsBean, RecyMultiDownloadBinding> adapter() {
        MultiDownldAdapter adapter = new MultiDownldAdapter(allItems, mContext);
        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                final PageData pageData = new PageData(allItems);
                Container.get().addPageToMap(pageData);

                Intent intent = new Intent(mContext, VActivity.class);
                intent.putExtra(Params.POSITION, position);
                intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                mContext.startActivity(intent);
            }
        });
        adapter.setCallback(new Callback() {
            @Override
            public void doSomething(Object t) {
                baseBind.toolbarTitle.setText(getToolbarTitle());
            }
        });
        return adapter;
    }

    @Override
    public BaseRepo repository() {
        return new LocalRepo<List<IllustsBean>>() {
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
        if (Common.isEmpty(allItems)) {
            return getString(R.string.string_221);
        } else {
            int selectCount = 0;
            int fileCount = 0;
            for (int i = 0; i < allItems.size(); i++) {
                if (allItems.get(i).isChecked()) {
                    fileCount = fileCount + allItems.get(i).getPage_count();
                    selectCount++;
                }
            }
            return selectCount + getString(R.string.string_222) + fileCount + getString(R.string.string_223);
        }
    }

    @Override
    public void onFirstLoaded(List<IllustsBean> illustsBeans) {
        initToolbar(baseBind.toolbar);
    }
}
