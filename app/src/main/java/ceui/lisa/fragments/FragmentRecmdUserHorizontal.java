package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.lisa.R;
import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UserHAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentUserHorizontalBinding;
import ceui.lisa.databinding.RecyUserPreviewHorizontalBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.BaseCtrl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import io.reactivex.Observable;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.FadeInLeftAnimator;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentRecmdUserHorizontal extends NetListFragment<FragmentUserHorizontalBinding,
        ListUser, UserPreviewsBean, RecyUserPreviewHorizontalBinding> {

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_user_horizontal;
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewHorizontalBinding> adapter() {
        return new UserHAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, UActivity.class);
                intent.putExtra(Params.USER_ID, allItems.get(position).getUser().getId());
                startActivity(intent);
            }
        });
    }

    @Override
    public BaseCtrl present() {
        return new NetControl<ListUser>() {
            @Override
            public Observable<ListUser> initApi() {
                return Retro.getAppApi().getRecmdUser(sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListUser> initNextApi() {
                return null;
            }
        };
    }

    @Override
    public BaseItemAnimator animation() {
        FadeInLeftAnimator fade = new FadeInLeftAnimator();
        fade.setAddDuration(animateDuration);
        fade.setRemoveDuration(animateDuration);
        fade.setMoveDuration(animateDuration);
        fade.setChangeDuration(animateDuration);
        return fade;
    }

    @Override
    public void firstSuccess() {
        mRefreshLayout.setEnableRefresh(false);
        mRefreshLayout.setEnableLoadMore(false);
    }

    @Override
    public void initRecyclerView() {
        baseBind.recyclerView.addItemDecoration(new LinearItemHorizontalDecoration(
                DensityUtil.dp2px(8.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext,
                LinearLayoutManager.HORIZONTAL, false);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.setHasFixedSize(true);
    }
}
