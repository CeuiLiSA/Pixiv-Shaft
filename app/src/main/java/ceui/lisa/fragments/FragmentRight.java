package ceui.lisa.fragments;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.qmuiteam.qmui.util.QMUIDisplayHelper;
import com.qmuiteam.qmui.widget.popup.QMUIPopup;
import com.qmuiteam.qmui.widget.popup.QMUIPopups;
import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.EventAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.Container;
import ceui.lisa.core.FilterMapper;
import ceui.lisa.core.PageData;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentNewRightBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.helper.TagFilter;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.ShareIllust;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentRight extends NetListFragment<FragmentNewRightBinding, ListIllust, IllustsBean> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_new_right;
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new EventAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (viewType == 0) {
                    final String uuid = UUID.randomUUID().toString();
                    final PageData pageData = new PageData(uuid, allItems);
                    Container.get().addPageToMap(pageData);

                    Intent intent = new Intent(mContext, VActivity.class);
                    intent.putExtra(Params.POSITION, position);
                    intent.putExtra(Params.PAGE_UUID, uuid);
                    mContext.startActivity(intent);
                } else if (viewType == 1) {
                    Intent intent = new Intent(mContext, UserActivity.class);
                    intent.putExtra(Params.USER_ID, allItems.get(position).getUser().getId());
                    startActivity(intent);
                } else if (viewType == 2) {
                    if (allItems.get(position).getPage_count() == 1) {
                        IllustDownload.downloadIllust(allItems.get(position));
                    } else {
                        IllustDownload.downloadAllIllust(allItems.get(position));
                    }
                } else if (viewType == 3) {
                    PixivOperate.postLike(allItems.get(position), FragmentLikeIllust.TYPE_PUBLUC);
                } else if (viewType == 4) {
                    View popView = LayoutInflater.from(mContext).inflate(R.layout.pop_window, null);
                    QMUIPopup mNormalPopup = QMUIPopups.popup(mContext, QMUIDisplayHelper.dp2px(mContext, 250))
                            .preferredDirection(QMUIPopup.DIRECTION_BOTTOM)
                            .view(popView)
                            .dimAmount(0.5f)
                            .edgeProtection(QMUIDisplayHelper.dp2px(mContext, 20))
                            .offsetX(QMUIDisplayHelper.dp2px(mContext, 80))
                            .offsetYIfBottom(QMUIDisplayHelper.dp2px(mContext, 5))
                            .shadow(true)
                            .arrow(false)
                            .animStyle(QMUIPopup.ANIM_GROW_FROM_RIGHT)
                            .onDismiss(new PopupWindow.OnDismissListener() {
                                @Override
                                public void onDismiss() {
                                }
                            })
                            .show(v);

                    popView.findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            new ShareIllust(mContext, allItems.get(position)) {
                                @Override
                                public void onPrepare() {

                                }
                            }.execute();
                            mNormalPopup.dismiss();
                        }
                    });
                    popView.findViewById(R.id.show_comment).setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Intent intent = new Intent(mContext, TemplateActivity.class);
                            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论");
                            intent.putExtra(Params.ILLUST_ID, allItems.get(position).getId());
                            intent.putExtra(Params.ILLUST_TITLE, allItems.get(position).getTitle());
                            startActivity(intent);
                            mNormalPopup.dismiss();
                        }
                    });

                    TextView follow = popView.findViewById(R.id.follow);
                    if (allItems.get(position).getUser().isIs_followed()) {
                        follow.setText("取消关注");
                    } else {
                        follow.setText("添加关注");
                    }

                    popView.findViewById(R.id.stop_follow).setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (allItems.get(position).getUser().isIs_followed()) {
                                PixivOperate.postUnFollowUser(allItems.get(position).getUser().getUserId());
                                allItems.get(position).getUser().setIs_followed(false);
                                follow.setText("添加关注");
                            } else {
                                PixivOperate.postFollowUser(allItems.get(position).getUser().getUserId(),
                                        FragmentLikeIllust.TYPE_PUBLUC);
                                allItems.get(position).getUser().setIs_followed(true);
                                follow.setText("取消关注");
                            }
                            mNormalPopup.dismiss();
                        }
                    });
                }
            }
        });
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
                    restrict = FragmentLikeIllust.TYPE_PRIVATE;
                } else {
                    restrict = FragmentLikeIllust.TYPE_PUBLUC;
                }
                clearAndRefresh();
            }
        });
    }

    @Override
    public BaseRepo repository() {
        return new RemoteRepo<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                return Retro.getAppApi().getFollowUserIllust(sUserModel.getResponse().getAccess_token(), restrict);
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(
                        sUserModel.getResponse().getAccess_token(), mModel.getNextUrl());
            }

            @Override
            public RefreshFooter getFooter(Context context) {
                return new ClassicsFooter(context).setPrimaryColorId(R.color.white);
            }

            @Override
            public RefreshHeader getHeader(Context context) {
                return new DeliveryHeader(context);
            }

            @Override
            public Function<ListIllust, ListIllust> mapper() {
                return new FilterMapper();
            }

            @Override
            public boolean localData() {
                return Dev.isDev;
            }
        };
    }

    @Override
    public void initRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
    }


    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser && !isLoad && isAdded()) {
            FragmentTransaction transaction = getChildFragmentManager().beginTransaction();

            FragmentRecmdUserHorizontal recmdUser = new FragmentRecmdUserHorizontal();
            transaction.add(R.id.user_recmd_fragment, recmdUser, "FragmentRecmdUserHorizontal");
            transaction.commitNow();

            baseBind.refreshLayout.autoRefresh();

            isLoad = true;
        }
    }

    @Override
    public boolean autoRefresh() {
        return false;
    }

    private String restrict = FragmentLikeIllust.TYPE_PUBLUC;

    private boolean isLoad = false;

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
}
