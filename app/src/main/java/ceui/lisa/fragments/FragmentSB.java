package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.Toolbar;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.SAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentSelectTagBinding;
import ceui.lisa.databinding.RecySelectTagBinding;
import ceui.lisa.dialogs.AddTagDialog;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.BaseCtrl;
import ceui.lisa.model.ListBookmarkTag;
import ceui.lisa.models.NullResponse;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentSB extends NetListFragment<FragmentSelectTagBinding,
        ListBookmarkTag, TagsBean, RecySelectTagBinding> {

    private int illustID;

    public static FragmentSB newInstance(int illustID) {
        Bundle args = new Bundle();
        args.putInt(Params.ILLUST_ID, illustID);
        FragmentSB fragment = new FragmentSB();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        illustID = bundle.getInt(Params.ILLUST_ID);
    }

    @Override
    public BaseAdapter<TagsBean, RecySelectTagBinding> adapter() {
        return new SAdapter(allItems, mContext);
    }

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_select_tag;
    }

    @Override
    public BaseCtrl present() {
        return new NetControl<ListBookmarkTag>() {
            @Override
            public Observable<ListBookmarkTag> initApi() {
                return Retro.getAppApi().getIllustBookmarkTags(Shaft.sUserModel.getResponse().getAccess_token(), illustID);
            }

            @Override
            public Observable<ListBookmarkTag> initNextApi() {
                return null;
            }
        };
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
                    baseBind.isPrivate.isChecked() ? FragmentLikeIllust.TYPE_PRIVATE : FragmentLikeIllust.TYPE_PUBLUC)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void onNext(NullResponse nullResponse) {
                            Common.showToast("收藏成功");
                            setFollowed();
                        }
                    });
        } else {

            String[] strings = new String[tempList.size()];
            tempList.toArray(strings);

            Retro.getAppApi().postLike(Shaft.sUserModel.getResponse().getAccess_token(), illustID,
                    baseBind.isPrivate.isChecked() ? FragmentLikeIllust.TYPE_PRIVATE : FragmentLikeIllust.TYPE_PUBLUC, strings)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void onNext(NullResponse nullResponse) {
                            Common.showToast("收藏成功");
                            setFollowed();
                        }
                    });
        }
    }

    private void setFollowed() {
        Channel channel = new Channel();
        channel.setReceiver("FragmentSingleIllust starIllust");
        channel.setObject(illustID);
        EventBus.getDefault().post(channel);
        mActivity.finish();
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

        TagsBean bookmarkTagsBean = new TagsBean();
        bookmarkTagsBean.setCount(0);
        bookmarkTagsBean.setSelected(true);
        bookmarkTagsBean.setName(tag);

        allItems.add(0, bookmarkTagsBean);
        mAdapter.notifyItemInserted(0);
        mRecyclerView.scrollToPosition(0);
        mAdapter.notifyItemRangeChanged(0, allItems.size());
    }

    @Override
    public void initToolbar(Toolbar toolbar) {
        super.initToolbar(toolbar);
        toolbar.inflateMenu(R.menu.add_tag);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_add) {
                    AddTagDialog dialog = new AddTagDialog();
                    dialog.show(getChildFragmentManager(), "AddTagDialog");
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public void initView(View view) {
        super.initView(view);
        baseBind.submitArea.setOnClickListener(v -> submitStar());
    }

    @Override
    public String getToolbarTitle() {
        return "按标签收藏";
    }
}
