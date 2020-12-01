package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;

import com.github.ybq.android.spinkit.style.Wave;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.adapters.LAdapter;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.FragmentLikeIllustHorizontalBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.recyclerview.animators.FadeInLeftAnimator;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static ceui.lisa.activities.Shaft.sUserModel;
import static ceui.lisa.fragments.ListFragment.animateDuration;

public class FragmentLikeIllustHorizontal extends BaseFragment<FragmentLikeIllustHorizontalBinding> {

    private List<IllustsBean> allItems = new ArrayList<>();
    private UserDetailResponse mUserDetailResponse;
    private LAdapter mAdapter;
    private int type; // 1插画收藏    2插画作品     3漫画作品

    public static FragmentLikeIllustHorizontal newInstance(UserDetailResponse userDetailResponse,
                                                           int pType) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, userDetailResponse);
        args.putInt(Params.DATA_TYPE, pType);
        FragmentLikeIllustHorizontal fragment = new FragmentLikeIllustHorizontal();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        mUserDetailResponse = (UserDetailResponse) bundle.getSerializable(Params.CONTENT);
        type = bundle.getInt(Params.DATA_TYPE);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_like_illust_horizontal;
    }

    @Override
    public void initView() {
        Wave wave = new Wave();
        wave.setColor(android.R.attr.colorPrimary);
        baseBind.progress.setIndeterminateDrawable(wave);
        baseBind.recyclerView.addItemDecoration(new
                LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        FadeInLeftAnimator landingAnimator = new FadeInLeftAnimator();
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
                final PageData pageData = new PageData(allItems);
                Container.get().addPageToMap(pageData);

                Intent intent = new Intent(mContext, VActivity.class);
                intent.putExtra(Params.POSITION, position);
                intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                mContext.startActivity(intent);
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
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, baseBind.title.getText().toString());
                intent.putExtra(Params.USER_ID, mUserDetailResponse.getUser().getId());
                startActivity(intent);
            }
        });
    }

    @Override
    protected void initData() {
        Observable<ListIllust> api = null;
        if (type == 1) {
            api = Retro.getAppApi().getUserLikeIllust(sUserModel.getResponse().getAccess_token(),
                    mUserDetailResponse.getUser().getId(), Params.TYPE_PUBLUC);
        } else if (type == 2) {
            api = Retro.getAppApi().getUserSubmitIllust(sUserModel.getResponse().getAccess_token(),
                    mUserDetailResponse.getUser().getId(), Params.TYPE_ILLUST);
        } else if (type == 3) {
            api = Retro.getAppApi().getUserSubmitIllust(sUserModel.getResponse().getAccess_token(),
                    mUserDetailResponse.getUser().getId(), Params.TYPE_MANGA);
        }

        if (api != null) {
            api.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<ListIllust>() {
                        @Override
                        public void success(ListIllust listIllust) {
                            allItems.clear();
                            if (listIllust.getList().size() > 10) {
                                allItems.addAll(listIllust.getList().subList(0, 10));
                            } else {
                                allItems.addAll(listIllust.getList());
                            }
                            mAdapter.notifyItemRangeInserted(0, allItems.size());
                        }

                        @Override
                        public void must(boolean isSuccess) {
                            baseBind.progress.setVisibility(View.INVISIBLE);
                        }
                    });
        }
    }
}
