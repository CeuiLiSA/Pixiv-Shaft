package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UserHAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.RxRun;
import ceui.lisa.core.RxRunnable;
import ceui.lisa.core.TimeRecord;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentUserHorizontalBinding;
import ceui.lisa.databinding.RecyUserPreviewHorizontalBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.repo.RecmdUserRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.FadeInLeftAnimator;


public class FragmentRecmdUserHorizontal extends NetListFragment<FragmentUserHorizontalBinding,
        ListUser, UserPreviewsBean> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_user_horizontal;
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewHorizontalBinding> adapter() {
        return new UserHAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, UserActivity.class);
                intent.putExtra(Params.USER_ID, allItems.get(position).getUser().getId());
                startActivity(intent);
            }
        });
    }

    @Override
    public BaseRepo repository() {
        return new RecmdUserRepo(true);
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
    public void onFirstLoaded(List<UserPreviewsBean> userPreviewsBeans) {
        mRefreshLayout.setEnableRefresh(false);
        mRefreshLayout.setEnableLoadMore(false);
    }

    @Override
    public void initRecyclerView() {
        baseBind.recyclerView.addItemDecoration(new LinearItemHorizontalDecoration(
                DensityUtil.dp2px(12.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext,
                LinearLayoutManager.HORIZONTAL, false);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.setHasFixedSize(true);
    }

    @Override
    public void showDataBase() {
        RxRun.runOn(new RxRunnable<List<IllustsBean>>() {
            @Override
            public List<IllustsBean> execute() throws Exception {
                TimeRecord.start();
                List<IllustRecmdEntity> entities = AppDatabase.getAppDatabase(mContext).recmdDao().getAll();
                Thread.sleep(100);
                List<IllustsBean> temp = new ArrayList<>();
                for (int i = 0; i < entities.size(); i++) {
                    IllustsBean illustsBean = Shaft.sGson.fromJson(
                            entities.get(i).getIllustJson(), IllustsBean.class);
                    temp.add(illustsBean);
                }
                return temp;
            }
        }, new NullCtrl<List<IllustsBean>>() {
            @Override
            public void success(List<IllustsBean> illustsBeans) {
                TimeRecord.end();
                for (IllustsBean illustsBean : illustsBeans) {
                    UserPreviewsBean userPreviewsBean = new UserPreviewsBean();
                    userPreviewsBean.setUser(illustsBean.getUser());
                    allItems.add(userPreviewsBean);
                }
                mAdapter.notifyItemRangeInserted(mAdapter.headerSize(), allItems.size());
            }

            @Override
            public void must(boolean isSuccess) {
                baseBind.refreshLayout.finishRefresh(isSuccess);
                baseBind.refreshLayout.setEnableRefresh(false);
                baseBind.refreshLayout.setEnableLoadMore(false);
            }
        });
    }
}
