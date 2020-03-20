package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.github.ybq.android.spinkit.style.DoubleBounce;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.UserHorizontalAdapter;
import ceui.lisa.databinding.FragmentUserHorizontalBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.recyclerview.animators.FadeInLeftAnimator;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 推荐用户
 */
public class FragmentRecmdUserHorizontal extends BaseBindFragment<FragmentUserHorizontalBinding> {

    private List<UserPreviewsBean> allItems = new ArrayList<>();
    private UserHorizontalAdapter mAdapter;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_user_horizontal;
    }

    @Override
    public void initView(View view) {
        DoubleBounce doubleBounce = new DoubleBounce();
        doubleBounce.setColor(getResources().getColor(R.color.white));
        baseBind.progress.setIndeterminateDrawable(doubleBounce);
        baseBind.recyclerView.addItemDecoration(new LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        FadeInLeftAnimator landingAnimator = new FadeInLeftAnimator();
        final long animateDuration = 400L;
        landingAnimator.setAddDuration(animateDuration);
        landingAnimator.setRemoveDuration(animateDuration);
        landingAnimator.setMoveDuration(animateDuration);
        landingAnimator.setChangeDuration(animateDuration);
        baseBind.recyclerView.setItemAnimator(landingAnimator);
        LinearLayoutManager manager = new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.setHasFixedSize(true);
        mAdapter = new UserHorizontalAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent;
                intent = new Intent(mContext, UActivity.class);
                intent.putExtra(Params.USER_ID, allItems.get(position).getUser().getId());
                startActivity(intent);

            }
        });
        baseBind.recyclerView.setAdapter(mAdapter);
    }

    @Override
    void initData() {
        Retro.getAppApi().getRecmdUser(sUserModel.getResponse().getAccess_token())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<ListUser>() {
                    @Override
                    public void success(ListUser listUser) {
                        allItems.clear();
                        allItems.addAll(listUser.getList());
                        mAdapter.notifyItemRangeInserted(0, listUser.getList().size());
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.progress.setVisibility(View.INVISIBLE);
                    }
                });
    }
}
