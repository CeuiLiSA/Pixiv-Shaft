package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;

import androidx.recyclerview.widget.LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.adapters.SelectTagAdapter;
import ceui.lisa.dialogs.AddTagDialog;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.BookmarkTagsBean;
import ceui.lisa.model.IllustBookmarkTags;
import ceui.lisa.model.NullResponse;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentSelectBookTag extends BaseListFragment<IllustBookmarkTags, SelectTagAdapter, BookmarkTagsBean> {

    private int illustID;
    private Switch mSwitch;
    private String bookType = "";

    public static FragmentSelectBookTag newInstance(int illustID) {
        FragmentSelectBookTag fragment = new FragmentSelectBookTag();
        fragment.illustID = illustID;
        return fragment;
    }

    @Override
    String getToolbarTitle() {
        return "按标签收藏";
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_select_tag;
    }

    @Override
    void initRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(16.0f)));
    }

    @Override
    View initView(View v) {
        super.initView(v);
        ((TemplateFragmentActivity) getActivity()).setSupportActionBar(mToolbar);
        mToolbar.setNavigationOnClickListener(view -> getActivity().finish());
        mToolbar.setTitle(getToolbarTitle());
        mSwitch = v.findViewById(R.id.is_private);
        Button button = v.findViewById(R.id.submit_area);
        button.setOnClickListener(view -> submitStar());
        return v;
    }

    private void submitStar() {
        List<String> tempList = new ArrayList<>();
        for (int i = 0; i < allItems.size(); i++) {
            if (allItems.get(i).isSelected()) {
                tempList.add(allItems.get(i).getName());
            }
        }

        if (tempList.size() == 0) {
            Retro.getAppApi().postLike(Shaft.sUserModel.getResponse().getAccess_token(), illustID,
                    mSwitch.isChecked() ? FragmentLikeIllust.TYPE_PRIVATE : FragmentLikeIllust.TYPE_PUBLUC)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void onNext(NullResponse nullResponse) {
                            Common.showToast("收藏成功");
                        }
                    });
        } else {


            String[] strings = new String[tempList.size()];

            tempList.toArray(strings);


            Retro.getAppApi().postLike(Shaft.sUserModel.getResponse().getAccess_token(), illustID,
                    mSwitch.isChecked() ? FragmentLikeIllust.TYPE_PRIVATE : FragmentLikeIllust.TYPE_PUBLUC, strings)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void onNext(NullResponse nullResponse) {
                            Common.showToast("收藏成功");
                            Channel channel = new Channel();
                            channel.setReceiver("FragmentSingleIllust starIllust");
                            channel.setObject(illustID);
                            EventBus.getDefault().post(channel);
                            getActivity().finish();
                        }
                    });
        }

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    Observable<IllustBookmarkTags> initApi() {
        return Retro.getAppApi().getIllustBookmarkTags(Shaft.sUserModel.getResponse().getAccess_token(), illustID);
    }

    @Override
    Observable<IllustBookmarkTags> initNextApi() {
        return null;
    }

    @Override
    void initAdapter() {
        mAdapter = new SelectTagAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Common.showLog(className + position);
            }
        });
    }

    public void addTag(String tag) {
        boolean isExist = false;
        for (int i = 0; i < allItems.size(); i++) {
            if (allItems.get(i).getName().equals(tag)) {
                isExist = true;
                break;
            }
        }

        if (isExist) {
            Common.showToast("该标签已存在");
            return;
        }

        BookmarkTagsBean bookmarkTagsBean = new BookmarkTagsBean();
        bookmarkTagsBean.setCount(0);
        bookmarkTagsBean.setSelected(true);
        bookmarkTagsBean.setName(tag);
        allItems.add(0, bookmarkTagsBean);
        mAdapter.notifyItemInserted(0);
        mRecyclerView.scrollToPosition(0);
        mAdapter.notifyItemRangeChanged(0, allItems.size());
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.add_tag, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_add) {
            AddTagDialog dialog = new AddTagDialog();
            dialog.show(getChildFragmentManager(), "AddTagDialog");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
