package ceui.lisa.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.databinding.ViewDataBinding;

import com.bumptech.glide.Glide;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.cache.Cache;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.databinding.FragmentNovelSeriesBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.helper.NovelParseHelper;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.model.ListNovelOfSeries;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.models.NovelSeriesItem;
import ceui.lisa.models.UserBean;
import ceui.lisa.models.WebNovel;
import ceui.lisa.repo.NovelSeriesDetailRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class FragmentNovelSeriesDetail extends NetListFragment<FragmentNovelSeriesBinding,
        ListNovelOfSeries, NovelBean> {

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
    public void initToolbar(Toolbar toolbar) {
        super.initToolbar(toolbar);
        toolbar.inflateMenu(R.menu.novel_series_download);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.batch_download) {
                    for (NovelBean novelBean : allItems) {
                        if (novelBean.isLocalSaved()) {
                            saveNovelToDownload(novelBean, Cache.get().getModel(Params.NOVEL_KEY + novelBean.getId(), NovelDetail.class));
                        } else {
                            Retro.getAppApi().getNovelDetailV2(Shaft.sUserModel.getAccess_token(), novelBean.getId()).enqueue(new retrofit2.Callback<ResponseBody>() {
                                @Override
                                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                                    new WebNovelParser(response) {
                                        @Override
                                        public void onNovelPrepared(@NonNull NovelDetail novelDetail, @NonNull WebNovel webNovel) {
                                            saveNovelToDownload(novelBean, novelDetail);
                                        }
                                    };
                                }

                                @Override
                                public void onFailure(Call<ResponseBody> call, Throwable t) {
                                }
                            });
                        }
                    }
                } else if (item.getItemId() == R.id.batch_download_as_one) {
                    Map<Integer, String> taskContainer = new HashMap<>();
                    String lineSeparator = System.lineSeparator();
                    for (NovelBean novelBean : allItems) {
                        if (novelBean.isLocalSaved()) {
                            String sb = lineSeparator + novelBean.getTitle() + " - " + novelBean.getId() + lineSeparator +
                                    Cache.get().getModel(Params.NOVEL_KEY + novelBean.getId(), NovelDetail.class).getNovel_text();
                            taskContainer.put(novelBean.getId(), sb);
                            if (taskContainer.size() == allItems.size()) {
                                String content = taskContainer.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).collect(Collectors.joining(lineSeparator));
                                saveNovelSeriesToDownload(mResponse.getNovel_series_detail(), content);
                            }
                        } else {
                            Retro.getAppApi().getNovelDetailV2(Shaft.sUserModel.getAccess_token(), novelBean.getId()).enqueue(new retrofit2.Callback<ResponseBody>() {
                                @Override
                                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                                    new WebNovelParser(response) {
                                        @Override
                                        public void onNovelPrepared(@NonNull NovelDetail novelDetail, @NonNull WebNovel webNovel) {
                                            String sb = lineSeparator + novelBean.getTitle() + " - " + novelBean.getId() + lineSeparator +
                                                    novelDetail.getNovel_text();
                                            taskContainer.put(novelBean.getId(), sb);
                                            if (taskContainer.size() == allItems.size()) {
                                                String content = taskContainer.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).collect(Collectors.joining(lineSeparator));
                                                saveNovelSeriesToDownload(mResponse.getNovel_series_detail(), content);
                                            }
                                        }
                                    };
                                }

                                @Override
                                public void onFailure(Call<ResponseBody> call, Throwable t) {
                                }
                            });
                        }
                    }
                }
                return true;
            }
        });
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
            baseBind.seriesDescription.setHtml(listNovelOfSeries.getNovel_series_detail().getDisplay_text());
            //每分钟五百字
            float minute = listNovelOfSeries.getNovel_series_detail().getTotal_character_count() / 500.0f;
            baseBind.seriesDetail.setText(String.format(getString(R.string.how_many_novels),
                    listNovelOfSeries.getNovel_series_detail().getContent_count(),
                    listNovelOfSeries.getNovel_series_detail().getTotal_character_count(),
                    (int) Math.floor(minute / 60),
                    ((int) minute) % 60));
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
                            Params.TYPE_PUBLIC);
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

    private void saveNovelToDownload(NovelBean novelBean, NovelDetail novelDetail) {
        IllustDownload.downloadNovel((BaseActivity<?>) mContext, novelBean, novelDetail, new Callback<Uri>() {
            @Override
            public void doSomething(Uri t) {
                Common.showToast(getString(R.string.string_279), 2);
            }
        });
    }

    private void saveNovelSeriesToDownload(NovelSeriesItem novelSeriesItem, String content) {
        IllustDownload.downloadNovel((BaseActivity<?>) mContext, novelSeriesItem, content, new Callback<Uri>() {
            @Override
            public void doSomething(Uri t) {
                Common.showToast(getString(R.string.string_279), 2);
            }
        });
    }
}
