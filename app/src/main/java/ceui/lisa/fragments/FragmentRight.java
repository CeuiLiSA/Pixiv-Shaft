package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.FragmentTransaction;

import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentNewRightBinding;
import ceui.lisa.helper.TagFilter;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.RightRepo;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentRight extends NetListFragment<FragmentNewRightBinding, ListIllust, IllustsBean> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_new_right;
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public void initView() {
        super.initView();

        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = Shaft.statusHeight;
        baseBind.head.setLayoutParams(headParams);

        baseBind.toolbar.inflateMenu(R.menu.fragment_left);
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActivity instanceof MainActivity) {
                    ((MainActivity) mActivity).getDrawer().openDrawer(GravityCompat.START, true);
                }
            }
        });
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_search) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索");
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
        baseBind.seeMore.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐用户");
            startActivity(intent);
        });
        baseBind.showPrivate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    restrict = Params.TYPE_PRIVATE;
                } else {
                    restrict = Params.TYPE_PUBLUC;
                }
                ((RightRepo) mRemoteRepo).setRestrict(restrict);
                clearAndRefresh();
            }
        });
    }

    @Override
    public BaseRepo repository() {
        return new RightRepo(restrict);
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }

    @Override
    public void lazyData() {
        super.lazyData();

        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();

        FragmentRecmdUserHorizontal recmdUser = new FragmentRecmdUserHorizontal();
        transaction.add(R.id.user_recmd_fragment, recmdUser, "FragmentRecmdUserHorizontal");
        transaction.commitAllowingStateLoss();

        baseBind.refreshLayout.autoRefresh();
    }

    @Override
    public boolean autoRefresh() {
        return false;
    }

    private String restrict = Params.TYPE_PUBLUC;

    @Override
    public void showDataBase() {
        Observable.create((ObservableOnSubscribe<List<IllustRecmdEntity>>) emitter -> {
            List<IllustRecmdEntity> temp = AppDatabase.getAppDatabase(mContext).recmdDao().getAll();
            Thread.sleep(100);
            emitter.onNext(temp);
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(entities -> {
                    List<IllustsBean> temp = new ArrayList<>();
                    for (int i = 0; i < entities.size(); i++) {
                        IllustsBean illustsBean = Shaft.sGson.fromJson(
                                entities.get(i).getIllustJson(), IllustsBean.class);
                        if (!TagFilter.judge(illustsBean)) {
                            temp.add(illustsBean);
                        }
                    }
                    return temp;
                })
                .subscribe(new NullCtrl<List<IllustsBean>>() {
                    @Override
                    public void success(List<IllustsBean> illustsBeans) {
                        allItems.addAll(illustsBeans);
                        mAdapter.notifyItemRangeInserted(mAdapter.headerSize(), allItems.size());
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.refreshLayout.finishRefresh(isSuccess);
                        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                    }
                });
    }

    @Override
    public void forceRefresh() {
        baseBind.recyclerView.smoothScrollToPosition(0);
//        super.forceRefresh();
    }
}
