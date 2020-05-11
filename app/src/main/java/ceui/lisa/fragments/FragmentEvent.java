package ceui.lisa.fragments;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.databinding.ViewDataBinding;

import com.qmuiteam.qmui.util.QMUIDisplayHelper;
import com.qmuiteam.qmui.widget.popup.QMUIPopup;
import com.qmuiteam.qmui.widget.popup.QMUIPopups;
import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.EventAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.ShareIllust;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentEvent extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        if (Shaft.sSettings.isDoubleStaggerData()) {
            return new IAdapter(allItems, mContext);
        } else {
            return new EventAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
                @Override
                public void onItemClick(View v, int position, int viewType) {
                    if (viewType == 0) {
                        DataChannel.get().setIllustList(allItems);
                        Intent intent = new Intent(mContext, ViewPagerActivity.class);
                        intent.putExtra("position", position);
                        startActivity(intent);
                    } else if (viewType == 1) {
                        Intent intent = new Intent(mContext, UActivity.class);
                        intent.putExtra(Params.USER_ID, allItems.get(position).getUser().getId());
                        startActivity(intent);
                    } else if (viewType == 2) {
                        if (allItems.get(position).getPage_count() == 1) {
                            IllustDownload.downloadIllust(mActivity, allItems.get(position));
                        } else {
                            IllustDownload.downloadAllIllust(mActivity, allItems.get(position));
                        }
                    } else if (viewType == 3) {
                        PixivOperate.postLike(allItems.get(position), sUserModel, FragmentLikeIllust.TYPE_PUBLUC);
                    } else if (viewType == 4) {
                        View popView = LayoutInflater.from(mContext).inflate(R.layout.pop_window, null);
                        QMUIPopup mNormalPopup = QMUIPopups.popup(mContext, QMUIDisplayHelper.dp2px(getContext(), 250))
                                .preferredDirection(QMUIPopup.DIRECTION_BOTTOM)
                                .view(popView)
                                .dimAmount(0.5f)
                                .edgeProtection(QMUIDisplayHelper.dp2px(mContext, 20))
                                .offsetX(QMUIDisplayHelper.dp2px(mContext, 80))
                                .offsetYIfBottom(QMUIDisplayHelper.dp2px(mContext, 5))
                                .shadow(true)
                                .arrow(true)
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

                                    @Override
                                    public void onExecuteSuccess(Void aVoid) {

                                    }

                                    @Override
                                    public void onExecuteFail(Exception e) {

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
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new RemoteRepo<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                return Retro.getAppApi().getFollowUserIllust(sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(
                        sUserModel.getResponse().getAccess_token(), mModel.getNextUrl());
            }

            @Override
            public RefreshFooter getFooter(Context context) {
                ClassicsFooter classicsFooter = new ClassicsFooter(context);
                classicsFooter.setPrimaryColorId(R.color.white);
                return classicsFooter;
            }

            @Override
            public RefreshHeader getHeader(Context context) {
                return new DeliveryHeader(context);
            }
        };
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        if (Shaft.sSettings.isDoubleStaggerData()) {
            staggerRecyclerView();
        } else {
            super.initRecyclerView();
        }
        baseBind.recyclerView.setBackgroundColor(getResources().getColor(R.color.white));
        baseBind.refreshLayout.setPrimaryColorsId(R.color.white);
    }
}
