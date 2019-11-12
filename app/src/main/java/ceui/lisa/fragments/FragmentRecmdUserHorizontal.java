package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.widget.ProgressBar;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.ybq.android.spinkit.style.DoubleBounce;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.UserHorizontalAdapter;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListUserResponse;
import ceui.lisa.model.UserPreviewsBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Dev;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.recyclerview.animators.FadeInLeftAnimator;

import static ceui.lisa.activities.Shaft.sUserModel;
import static ceui.lisa.fragments.FragmentList.animateDuration;

/**
 * 推荐用户
 */
public class FragmentRecmdUserHorizontal extends BaseFragment {

    private ProgressBar mProgressBar;
    private RecyclerView mRecyclerView;
    private List<UserPreviewsBean> allItems = new ArrayList<>();
    private UserHorizontalAdapter mAdapter;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_user_horizontal;
    }

    @Override
    View initView(View v) {
        mProgressBar = v.findViewById(R.id.progress);
        DoubleBounce doubleBounce = new DoubleBounce();
        doubleBounce.setColor(getResources().getColor(R.color.white));
        mProgressBar.setIndeterminateDrawable(doubleBounce);
        mRecyclerView = v.findViewById(R.id.recyclerView);
        mRecyclerView.addItemDecoration(new LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        FadeInLeftAnimator landingAnimator = new FadeInLeftAnimator();
        landingAnimator.setAddDuration(animateDuration);
        landingAnimator.setRemoveDuration(animateDuration);
        landingAnimator.setMoveDuration(animateDuration);
        landingAnimator.setChangeDuration(animateDuration);
        mRecyclerView.setItemAnimator(landingAnimator);
        LinearLayoutManager manager = new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.setHasFixedSize(true);
        mAdapter = new UserHorizontalAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent;
                intent = new Intent(mContext, UActivity.class);
                intent.putExtra("user id", allItems.get(position).getUser().getId());
                startActivity(intent);

            }
        });
        mRecyclerView.setAdapter(mAdapter);
        return v;
    }

    @Override
    void initData() {
        getFirstData();
    }

    private void getFirstData() {
        Retro.getAppApi().getRecmdUser(sUserModel.getResponse().getAccess_token())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<ListUserResponse>() {
                    @Override
                    public void success(ListUserResponse listUserResponse) {
                        allItems.clear();
                        allItems.addAll(listUserResponse.getList());
                        mAdapter.notifyItemRangeInserted(0, listUserResponse.getList().size());
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }
                });
    }
}
