package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.databinding.ViewDataBinding;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.BaseCtrl;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.FragmentNovelSeriesBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.NovelSeries;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.UserBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.ScrollChange;
import io.reactivex.Observable;

public class FragmentNovelSeries extends NetListFragment<FragmentNovelSeriesBinding,
        NovelSeries, NovelBean, RecyNovelBinding>{

    private NovelBean novelBean;

    public static FragmentNovelSeries newInstance(NovelBean n) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, n);
        FragmentNovelSeries fragment = new FragmentNovelSeries();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_novel_series;
    }

    @Override
    public void initBundle(Bundle bundle) {
        novelBean = (NovelBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new NAdapter(allItems, mContext, true);
    }

    @Override
    public BaseCtrl present() {
        return new NetControl<NovelSeries>() {
            @Override
            public Observable<NovelSeries> initApi() {
                return Retro.getAppApi().getNovelSeries(mModel.getToken(), novelBean.getSeries().getId());
            }

            @Override
            public Observable<NovelSeries> initNextApi() {
                return Retro.getAppApi().getNextSeriesNovel(mModel.getToken(), mModel.getNextUrl());
            }
        };
    }

    @Override
    public String getToolbarTitle() {
        return "小说系列";
    }

    @Override
    public void onResponse(NovelSeries novelSeries) {
        baseBind.cardPixiv.setVisibility(View.VISIBLE);
        baseBind.seriesTitle.setText("系列名称：" + novelSeries.getNovel_series_detail().getTitle());
        //每分钟五百字
        float minute = novelSeries.getNovel_series_detail().getTotal_character_count() / 500.0f;
        baseBind.seriesDetail.setText(String.format(getString(R.string.how_many_novels),
                novelSeries.getNovel_series_detail().getContent_count(),
                novelSeries.getNovel_series_detail().getTotal_character_count(),
                (int) Math.floor(minute / 60),
                ((int)minute) % 60));
        if (novelSeries.getList() != null && novelSeries.getList().size() != 0) {
            NovelBean bean = novelSeries.getList().get(0);
            UserBean userBean = bean.getUser();
            initUser(userBean);
        }
    }


    private void initUser(UserBean userBean) {
        View.OnClickListener seeUser = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showUser(mContext, userBean);
            }
        };
        baseBind.userHead.setOnClickListener(seeUser);
        baseBind.userName.setOnClickListener(seeUser);
        baseBind.userName.setText(userBean.getName());
        Glide.with(mContext).load(GlideUtil.getHead(userBean)).into(baseBind.userHead);
        if (userBean.isIs_followed()) {
            baseBind.postLikeUser.setText("取消关注");
        } else {
            baseBind.postLikeUser.setText("添加关注");
        }

        baseBind.postLikeUser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (userBean.isIs_followed()) {
                    baseBind.postLikeUser.setText("添加关注");
                    userBean.setIs_followed(false);
                    PixivOperate.postUnFollowUser(userBean.getId());
                } else {
                    baseBind.postLikeUser.setText("取消关注");
                    userBean.setIs_followed(true);
                    PixivOperate.postFollowUser(userBean.getId(),
                            FragmentLikeIllust.TYPE_PUBLUC);
                }
            }
        });
        baseBind.postLikeUser.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (!userBean.isIs_followed()) {
                    baseBind.postLikeUser.setText("取消关注");
                    userBean.setIs_followed(true);
                    PixivOperate.postFollowUser(userBean.getId(),
                            FragmentLikeIllust.TYPE_PRIVATE);
                }
                return true;
            }
        });
    }
}
