package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.RequestManager;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.SAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.databinding.FragmentSelectTagBinding;
import ceui.lisa.databinding.RecySelectTagBinding;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListBookmarkTag;
import ceui.lisa.model.ListTag;
import ceui.lisa.models.NullResponse;
import ceui.lisa.models.TagsBean;
import ceui.lisa.repo.SelectTagRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentSB extends NetListFragment<FragmentSelectTagBinding,
        ListBookmarkTag, TagsBean> {

    private int illustID;
    private String lastClass = "";

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
    public void initActivityBundle(Bundle bundle) {
        lastClass = bundle.getString(Params.LAST_CLASS);
    }

    @Override
    public BaseAdapter<TagsBean, RecySelectTagBinding> adapter() {
        return new SAdapter(allItems, mContext);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_select_tag;
    }

    @Override
    public BaseRepo repository() {
        return new SelectTagRepo(illustID);
    }

    private void submitStar() {
        List<String> tempList = new ArrayList<>();
        for (int i = 0; i < allItems.size(); i++) {
            if (allItems.get(i).isSelectedLocalOrRemote()) {
                tempList.add(allItems.get(i).getName());
            }
        }

        if (tempList.size() == 0) {
            boolean isPrivate = baseBind.isPrivate.isChecked();
            String toastMsg = isPrivate ? getString(R.string.like_novel_success_private) : getString(R.string.like_novel_success_public);
            Retro.getAppApi().postLike(Shaft.sUserModel.getAccess_token(), illustID,
                    isPrivate ? Params.TYPE_PRIVATE : Params.TYPE_PUBLUC)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void next(NullResponse nullResponse) {
                            Common.showToast(toastMsg);
                            setFollowed();
                        }
                    });
        } else {
            boolean isPrivate = baseBind.isPrivate.isChecked();
            String toastMsg = isPrivate ? getString(R.string.like_novel_success_private) : getString(R.string.like_novel_success_public);
            String[] strings = new String[tempList.size()];
            tempList.toArray(strings);

            Retro.getAppApi().postLike(Shaft.sUserModel.getAccess_token(), illustID,
                    isPrivate ? Params.TYPE_PRIVATE : Params.TYPE_PUBLUC, strings)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void next(NullResponse nullResponse) {
                            Common.showToast(toastMsg);
                            setFollowed();
                        }
                    });
        }
    }

    private void setFollowed() {
        //通知其他页面刷新，设置这个作品为已收藏
        Intent intent = new Intent(Params.LIKED_ILLUST);
        intent.putExtra(Params.ID, illustID);
        intent.putExtra(Params.IS_LIKED, true);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
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
    public void beforeFirstLoad(List<TagsBean> tagsBeans) {
        super.beforeFirstLoad(tagsBeans);
        for (TagsBean tagsBean : tagsBeans) {
            tagsBean.setSelected(Shaft.sSettings.isStarWithTagSelectAll());
        }
    }

    @Override
    public void initToolbar(Toolbar toolbar) {
        super.initToolbar(toolbar);
        toolbar.inflateMenu(R.menu.add_tag);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_add) {
                    final QMUIDialog.EditTextDialogBuilder builder = new QMUIDialog.EditTextDialogBuilder(mActivity);
                    builder.setTitle("添加标签")
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .setPlaceholder("请输入标签(收藏夹)名")
                            .setInputType(InputType.TYPE_CLASS_TEXT)
                            .addAction("取消", new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                }
                            })
                            .addAction("添加", new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    CharSequence text = builder.getEditText().getText();
                                    if (text != null && text.length() > 0) {
                                        addTag(text.toString());
                                        dialog.dismiss();
                                    } else {
                                        Toast.makeText(getActivity(), "请填入标签", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            })
                            .show();
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public void initView() {
        super.initView();
        baseBind.isPrivate.setChecked(Shaft.sSettings.isPrivateStar());
        baseBind.submitArea.setOnClickListener(v -> submitStar());
    }

//    @Override
//    public void onFirstLoaded(List<TagsBean> tagsBeans) {
//        super.onFirstLoaded(tagsBeans);
//        getLikedTags();
//    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_238);
    }

//    private void getLikedTags() {
//        if (true) {
//            return;
//        }
//        Retro.getAppApi().getBookmarkTags(Shaft.sUserModel.getAccess_token(),
//                Shaft.sUserModel.getUserId(), Params.TYPE_PUBLUC)
//                .subscribeOn(Schedulers.newThread())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(new NullCtrl<ListTag>() {
//                    @Override
//                    public void success(ListTag listTag) {
//                        if (!Common.isEmpty(listTag.getList()) && !Common.isEmpty(allItems)) {
//                            for (TagsBean tagsBean : listTag.getList()) {
//                                for (TagsBean allItem : allItems) {
//                                    Common.showLog("left " + allItem.getName() + "right " + tagsBean.getName());
//                                    allItem.setSelected(
//                                            TextUtils.equals(allItem.getName(), tagsBean.getName())
//                                    );
//                                }
//                            }
//                            mAdapter.notifyDataSetChanged();
//                        }
//                    }
//                });
//    }
}
