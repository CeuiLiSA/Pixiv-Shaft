package ceui.lisa.fragments;

import android.net.Uri;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;

import com.afollestad.dragselectrecyclerview.DragSelectReceiver;
import com.afollestad.dragselectrecyclerview.DragSelectTouchListener;

import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.MultiDownldAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.databinding.FragmentMultiDownloadBinding;
import ceui.lisa.databinding.RecyMultiDownloadBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.feature.worker.BatchStarTask;
import ceui.lisa.feature.worker.Worker;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.OnItemLongClickListener;
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

    private DragSelectTouchListener dragSelectTouchListener;
    private boolean dragStartCheckStatus;

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
                        //不需要下载txt文件
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
                } else if (item.getItemId() == R.id.action_6) {
                    for (int i = 0; i < allItems.size(); i++) {
                        allItems.get(i).setChecked(allItems.get(i).isIs_bookmarked());
                    }
                    mAdapter.notifyDataSetChanged();
                } else if (item.getItemId() == R.id.action_7) {
                    for (int i = 0; i < allItems.size(); i++) {
                        allItems.get(i).setChecked(!Common.isIllustDownloaded(allItems.get(i)));
                    }
                    mAdapter.notifyDataSetChanged();
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

        MyReceiver receiver = new MyReceiver();
        dragSelectTouchListener = DragSelectTouchListener.Companion.create(
                mContext, receiver, null);
        baseBind.recyclerView.addOnItemTouchListener(dragSelectTouchListener);

        RecyclerView.ItemAnimator itemAnimator = mRecyclerView.getItemAnimator();
        if (itemAnimator != null) {
            itemAnimator.setChangeDuration(0);
            ((SimpleItemAnimator) itemAnimator).setSupportsChangeAnimations(false);
        }
    }

    private class MyReceiver implements DragSelectReceiver {

        @Override
        public int getItemCount() {
            return allItems.size();
        }

        @Override
        public boolean isIndexSelectable(int i) {
            return true;
        }

        @Override
        public boolean isSelected(int i) {
            return allItems.get(i).isChecked();
        }

        @Override
        public void setSelected(int i, boolean b) {
            if (dragStartCheckStatus) b = !b;
            allItems.get(i).setChecked(b);
            mAdapter.notifyItemChanged(i);
//            Common.showLog("MyReceiver setSelected " + i + ",status="+b);
        }
    }

    @Override
    public BaseAdapter<IllustsBean, RecyMultiDownloadBinding> adapter() {
        MultiDownldAdapter adapter = new MultiDownldAdapter(allItems, mContext);
        adapter.setCallback(new Callback() {
            @Override
            public void doSomething(Object t) {
                baseBind.toolbarTitle.setText(getToolbarTitle());
            }
        });
        adapter.setOnItemLongClickListener(new OnItemLongClickListener() {
            @Override
            public void onItemLongClick(View v, int position, int viewType) {
                dragStartCheckStatus = allItems.get(position).isChecked();
                dragSelectTouchListener.setIsActive(true, position);
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
    protected void initData() {
        super.initData();
        baseBind.refreshLayout.setEnableRefresh(false);
        baseBind.refreshLayout.setEnableLoadMore(false);
    }

    @Override
    public void onFirstLoaded(List<IllustsBean> illustsBeans) {
        initToolbar(baseBind.toolbar);
    }
}
