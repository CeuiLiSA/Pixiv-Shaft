package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.databinding.ViewDataBinding;

import com.bumptech.glide.Glide;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.databinding.FragmentNovelSeriesBinding;
import ceui.lisa.model.ListNovelOfSeries;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.UserBean;
import ceui.lisa.repo.NovelSeriesDetailRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class FragmentNovelSeriesDetail extends NetListFragment<FragmentNovelSeriesBinding,
        ListNovelOfSeries, NovelBean>{

    private int seriesID;

    public static FragmentNovelSeriesDetail newInstance(int seriesID) {
        Bundle args = new Bundle();
        args.putInt(Params.ID, seriesID);
        FragmentNovelSeriesDetail fragment = new FragmentNovelSeriesDetail();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_novel_series;
    }

    @Override
    public void initBundle(Bundle bundle) {
        seriesID = bundle.getInt(Params.ID);
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new NAdapter(allItems, mContext, true);
    }

    @Override
    public BaseRepo repository() {
        return new NovelSeriesDetailRepo(seriesID);
    }

    @Override
    public String getToolbarTitle() {
        return "小说系列";
    }

    @Override
    public void onResponse(ListNovelOfSeries listNovelOfSeries) {
        try {
            baseBind.cardPixiv.setVisibility(View.VISIBLE);
            baseBind.seriesTitle.setText(String.format("系列名称：%s", listNovelOfSeries.getNovel_series_detail().getTitle()));
            //每分钟五百字
            float minute = listNovelOfSeries.getNovel_series_detail().getTotal_character_count() / 500.0f;
            baseBind.seriesDetail.setText(String.format(getString(R.string.how_many_novels),
                    listNovelOfSeries.getNovel_series_detail().getContent_count(),
                    listNovelOfSeries.getNovel_series_detail().getTotal_character_count(),
                    (int) Math.floor(minute / 60),
                    ((int)minute) % 60));
            if (listNovelOfSeries.getList() != null && listNovelOfSeries.getList().size() != 0) {
                NovelBean bean = listNovelOfSeries.getList().get(0);
                UserBean userBean = bean.getUser();
                initUser(userBean);
            }
        } catch (Exception e) {
            e.printStackTrace();
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
                            Params.TYPE_PUBLUC);
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
                            Params.TYPE_PRIVATE);
                }
                return true;
            }
        });
    }
}
