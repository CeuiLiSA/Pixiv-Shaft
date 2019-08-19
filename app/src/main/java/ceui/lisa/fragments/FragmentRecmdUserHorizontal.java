package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ProgressBar;

import com.github.ybq.android.spinkit.style.DoubleBounce;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.adapters.UserHorizontalAdapter;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListUserResponse;
import ceui.lisa.model.UserPreviewsBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

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
        LinearLayoutManager manager = new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.setHasFixedSize(true);
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
                .subscribe(new Observer<ListUserResponse>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(ListUserResponse listUserResponse) {
                        if (listUserResponse != null) {
                            allItems.clear();
                            allItems.addAll(listUserResponse.getList());
                            mAdapter = new UserHorizontalAdapter(allItems, mContext);
                            mAdapter.setOnItemClickListener(new OnItemClickListener() {
                                @Override
                                public void onItemClick(View v, int position, int viewType) {
                                    Intent intent = new Intent(mContext, UserDetailActivity.class);
                                    intent.putExtra("user id", allItems.get(position).getUser().getId());
                                    startActivity(intent);
                                }
                            });
                            mRecyclerView.setAdapter(mAdapter);
                        }
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }
}
