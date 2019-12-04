package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.PagerSnapHelper;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.LAdapter;
import ceui.lisa.databinding.FragmentLikeIllustHorizontalBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.model.UserDetailResponse;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.recyclerview.animators.FadeInLeftAnimator;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentLikeIllustHorizontal extends BaseBindFragment<FragmentLikeIllustHorizontalBinding> {

    private List<IllustsBean> allItems = new ArrayList<>();
    private UserDetailResponse mUserDetailResponse;
    private LAdapter mAdapter;
    private int type; // 1插画收藏    2插画作品     3漫画作品

    public static FragmentLikeIllustHorizontal newInstance(UserDetailResponse userDetailResponse, int pType) {
        FragmentLikeIllustHorizontal fragmentLikeIllustHorizontal = new FragmentLikeIllustHorizontal();
        fragmentLikeIllustHorizontal.mUserDetailResponse = userDetailResponse;
        fragmentLikeIllustHorizontal.type = pType;
        return fragmentLikeIllustHorizontal;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_like_illust_horizontal;
    }

    @Override
    public void initView(View view) {
        baseBind.recyclerView.addItemDecoration(new
                LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        FadeInLeftAnimator landingAnimator = new FadeInLeftAnimator();
        final long animateDuration = 400L;
        landingAnimator.setAddDuration(animateDuration);
        landingAnimator.setRemoveDuration(animateDuration);
        landingAnimator.setMoveDuration(animateDuration);
        landingAnimator.setChangeDuration(animateDuration);
        baseBind.recyclerView.setItemAnimator(landingAnimator);
        LinearLayoutManager manager = new LinearLayoutManager(
                mContext, LinearLayoutManager.HORIZONTAL, false);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.setHasFixedSize(true);
        PagerSnapHelper snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(baseBind.recyclerView);
        mAdapter = new LAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                DataChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
        baseBind.recyclerView.setAdapter(mAdapter);
        ViewGroup.LayoutParams layoutParams = baseBind.recyclerView.getLayoutParams();
        layoutParams.width = MATCH_PARENT;
        layoutParams.height = mAdapter.getImageSize() + mContext.getResources()
                .getDimensionPixelSize(R.dimen.sixteen_dp);
        baseBind.recyclerView.setLayoutParams(layoutParams);
        if (type == 1) {
            baseBind.title.setText("插画/漫画收藏");
            baseBind.howMany.setText(String.format(getString(R.string.how_many_illust_works),
                    mUserDetailResponse.getProfile().getTotal_illust_bookmarks_public()));
        } else if (type == 2) {
            baseBind.title.setText("插画作品");
            baseBind.howMany.setText(String.format(getString(R.string.how_many_illust_works),
                    mUserDetailResponse.getProfile().getTotal_illusts()));

        } else if (type == 3) {
            baseBind.title.setText("漫画作品");
            baseBind.howMany.setText(String.format(getString(R.string.how_many_illust_works),
                    mUserDetailResponse.getProfile().getTotal_manga()));
        }
        baseBind.howMany.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT,
                        baseBind.title.getText().toString());
                intent.putExtra(Params.USER_ID, mUserDetailResponse.getUser().getId());
                startActivity(intent);
            }
        });
    }

    @Override
    void initData() {
        Observable<ListIllustResponse> api = null;
        if (type == 1) {
            api = Retro.getAppApi().getUserLikeIllust(sUserModel.getResponse().getAccess_token(),
                    mUserDetailResponse.getUser().getId(), FragmentLikeIllust.TYPE_PUBLUC);
        } else if (type == 2) {
            api = Retro.getAppApi().getUserSubmitIllust(sUserModel.getResponse().getAccess_token(),
                    mUserDetailResponse.getUser().getId(), "illust");
        } else if (type == 3) {
            api = Retro.getAppApi().getUserSubmitIllust(sUserModel.getResponse().getAccess_token(),
                    mUserDetailResponse.getUser().getId(), "manga");
        }

        if (api != null) {
            api.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<ListIllustResponse>() {
                        @Override
                        public void success(ListIllustResponse listIllustResponse) {
                            allItems.clear();
                            if (listIllustResponse.getList().size() > 10) {
                                allItems.addAll(listIllustResponse.getList().subList(0, 10));
                            } else {
                                allItems.addAll(listIllustResponse.getList());
                            }
                            mAdapter.notifyItemRangeInserted(0, allItems.size());
                        }
                    });
        }
    }
}
