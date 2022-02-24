package ceui.lisa.fragments;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.blankj.utilcode.util.BarUtils;
import com.blankj.utilcode.util.PathUtils;
import com.bumptech.glide.Glide;
import com.jaredrummler.android.colorpicker.ColorPickerDialog;
import com.skydoves.transformationlayout.OnTransformFinishListener;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.Arrays;
import java.util.Collections;

import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.NovelActivity;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.VAdapter;
import ceui.lisa.adapters.VNewAdapter;
import ceui.lisa.cache.Cache;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.databinding.FragmentNovelHolderBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.helper.NovelParseHelper;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.ScrollChange;
import gdut.bsx.share2.Share2;
import gdut.bsx.share2.ShareContentType;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentNovelHolder extends BaseFragment<FragmentNovelHolderBinding> {

    private boolean isOpen = false;
    private NovelBean mNovelBean;
    private NovelDetail mNovelDetail;

    public static FragmentNovelHolder newInstance(NovelBean novelBean) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, novelBean);
        FragmentNovelHolder fragment = new FragmentNovelHolder();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_novel_holder;
    }

    @Override
    public void initBundle(Bundle bundle) {
        mNovelBean = (NovelBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public void initView() {
        BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.hito_bg));
        if (Shaft.sSettings.getNovelHolderColor() != 0) {
            setBackgroundColor(Shaft.sSettings.getNovelHolderColor());
        }
        baseBind.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.transformationLayout.startTransform();
            }
        });
        baseBind.transformationLayout.onTransformFinishListener = new OnTransformFinishListener() {
            @Override
            public void onFinish(boolean isTransformed) {
                Common.showLog(className + isTransformed);
                isOpen = isTransformed;
            }
        };
    }

    @Override
    protected void initData() {
        getNovel(mNovelBean);
    }

    public void setBackgroundColor(int color) {
        Common.showLog(className + color);
        baseBind.relaRoot.setBackgroundColor(color);
    }

    public void setTextColor(int color) {
        Common.showLog(className + color);
        baseBind.toolbar.getOverflowIcon().setTint(Common.getNovelTextColor());
        setNovelAdapter();
    }

    private void getNovel(NovelBean novelBean) {
        mNovelBean = novelBean;
        if (mNovelBean.isIs_bookmarked()) {
            baseBind.like.setText(mContext.getString(R.string.string_179));
        } else {
            baseBind.like.setText(mContext.getString(R.string.string_180));
        }
        Common.showLog(className + "getNovel 000");
        baseBind.like.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showLog(className + "getNovel 111");
                PixivOperate.postLikeNovel(mNovelBean, Shaft.sUserModel,
                        Params.TYPE_PUBLIC, baseBind.like);
            }
        });

        baseBind.like.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.ILLUST_ID, mNovelBean.getId());
                intent.putExtra(Params.DATA_TYPE, Params.TYPE_NOVEL);
                intent.putExtra(Params.TAG_NAMES, mNovelBean.getTagNames());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                mContext.startActivity(intent);
                return true;
            }
        });

        View.OnClickListener seeUser = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showUser(mContext, mNovelBean.getUser());
            }
        };
        baseBind.userHead.setOnClickListener(seeUser);
        baseBind.userName.setOnClickListener(seeUser);
        baseBind.userName.setText(mNovelBean.getUser().getName());
        baseBind.viewPager.setLayoutManager(new ScrollChange(mContext));
        baseBind.viewPager.setHasFixedSize(false);
        baseBind.novelTitle.setText(String.format("%s%s", getString(R.string.string_182), mNovelBean.getTitle()));
        if (mNovelBean.getSeries() != null && !TextUtils.isEmpty(mNovelBean.getSeries().getTitle())) {
            baseBind.novelSeries.setVisibility(View.VISIBLE);
            baseBind.novelSeries.setText(String.format("%s%s", getString(R.string.string_183), mNovelBean.getSeries().getTitle()));
            baseBind.novelSeries.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.ID, mNovelBean.getSeries().getId());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列详情");
                    startActivity(intent);
                }
            });
        } else {
            baseBind.novelSeries.setVisibility(View.GONE);
        }
        if (mNovelBean.getTags() != null && mNovelBean.getTags().size() != 0) {
            baseBind.hotTags.setAdapter(new TagAdapter<TagsBean>(
                    mNovelBean.getTags()) {
                @Override
                public View getView(FlowLayout parent, int position, TagsBean trendTagsBean) {
                    TextView tv = (TextView) LayoutInflater.from(mContext).inflate(
                            R.layout.recy_single_novel_tag_text_small,
                            parent, false);
                    tv.setText(trendTagsBean.getName());
                    return tv;
                }
            });
            baseBind.hotTags.setOnTagClickListener(new TagFlowLayout.OnTagClickListener() {
                @Override
                public boolean onTagClick(View view, int position, FlowLayout parent) {
                    Intent intent = new Intent(mContext, SearchActivity.class);
                    intent.putExtra(Params.KEY_WORD, mNovelBean.getTags().get(position).getName());
                    intent.putExtra(Params.INDEX, 1);
                    startActivity(intent);
                    return false;
                }
            });
        }
        if (TextUtils.isEmpty(mNovelBean.getCaption())) {
            baseBind.description.setVisibility(View.GONE);
        } else {
            baseBind.description.setVisibility(View.VISIBLE);
            baseBind.description.setHtml(mNovelBean.getCaption());
        }
        baseBind.publishTime.setText(Common.getLocalYYYYMMDDHHMMString(mNovelBean.getCreate_date()));
        baseBind.viewCount.setText(String.valueOf(mNovelBean.getTotal_view()));
        baseBind.bookmarkCount.setText(String.valueOf(mNovelBean.getTotal_bookmarks()));
        baseBind.comment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.NOVEL_ID, mNovelBean.getId());
                intent.putExtra(Params.ILLUST_TITLE, mNovelBean.getTitle());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论");
                startActivity(intent);
            }
        });
        Glide.with(mContext).load(GlideUtil.getHead(mNovelBean.getUser())).into(baseBind.userHead);

        PixivOperate.insertNovelViewHistory(novelBean);
        baseBind.viewPager.setVisibility(View.INVISIBLE);
        if (novelBean.isLocalSaved()) {
            baseBind.progressRela.setVisibility(View.INVISIBLE);
            mNovelDetail = Cache.get().getModel(Params.NOVEL_KEY + mNovelBean.getId(), NovelDetail.class);
            refreshDetail(mNovelDetail);
        } else {
            baseBind.progressRela.setVisibility(View.VISIBLE);
            Retro.getAppApi().getNovelDetail(Shaft.sUserModel.getAccess_token(), novelBean.getId())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<NovelDetail>() {
                        @Override
                        public void success(NovelDetail novelDetail) {
                            novelDetail.setParsedChapters(NovelParseHelper.tryParseChapters(novelDetail.getNovel_text()));
                            refreshDetail(novelDetail);
                        }

                        @Override
                        public void must(boolean isSuccess) {
                            baseBind.progressRela.setVisibility(View.INVISIBLE);
                        }
                    });
        }

        baseBind.toolbar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return baseBind.awesomeCardCon.dispatchTouchEvent(event);
            }
        });
    }

    private void refreshDetail(NovelDetail novelDetail) {
        if (Dev.isDev && false) {
            Intent intent = new Intent(mContext, NovelActivity.class);
            intent.putExtra(Params.NOVEL_DETAIL, novelDetail);
            startActivity(intent);
            finish();
            return;
        }
        mNovelDetail = novelDetail;
        baseBind.viewPager.setVisibility(View.VISIBLE);
        baseBind.awesomeCardCon.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isOpen) {
                    baseBind.transformationLayout.finishTransform();
                    isOpen = false;
                    return true;
                }
                return baseBind.viewPager.dispatchTouchEvent(event);
            }
        });

        setNovelAdapter();

        if (novelDetail.getSeries_prev() != null && novelDetail.getSeries_prev().getId() != 0) {
            baseBind.showPrev.setVisibility(View.VISIBLE);
            baseBind.showPrev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    baseBind.transformationLayout.finishTransform();
                    getNovel(novelDetail.getSeries_prev());
                }
            });
        } else {
            baseBind.showPrev.setVisibility(View.INVISIBLE);
        }
        if (novelDetail.getSeries_next() != null && novelDetail.getSeries_next().getId() != 0) {
            baseBind.showNext.setVisibility(View.VISIBLE);
            baseBind.showNext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    baseBind.transformationLayout.finishTransform();
                    getNovel(novelDetail.getSeries_next());
                }
            });
        } else {
            baseBind.showNext.setVisibility(View.INVISIBLE);
        }
        baseBind.toolbar.getMenu().clear();
        baseBind.toolbar.inflateMenu(R.menu.novel_read_menu);
        baseBind.toolbar.getOverflowIcon().setTint(Common.getNovelTextColor());
        baseBind.saveNovelTxt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //需要下载txt文件
                IllustDownload.downloadNovel((BaseActivity<?>) mContext, mNovelBean, novelDetail, new Callback<Uri>() {
                    @Override
                    public void doSomething(Uri t) {
                        Common.showToast(getString(R.string.string_279), 2);
                    }
                });
            }
        });
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_change_color) {
                    if (Shaft.sSettings.getNovelHolderColor() != 0) {
                        ColorPickerDialog.newBuilder()
                                .setDialogId(Params.DIALOG_NOVEL_BG_COLOR)
                                .setColor(Shaft.sSettings.getNovelHolderColor())
                                .show(mActivity);
                    } else {
                        ColorPickerDialog.newBuilder()
                                .setDialogId(Params.DIALOG_NOVEL_BG_COLOR)
                                .setColor(getResources().getColor(R.color.novel_holder))
                                .show(mActivity);
                    }
                    return true;
                }else if(item.getItemId() == R.id.action_change_text_color){
                    if (Shaft.sSettings.getNovelHolderTextColor() != 0) {
                        ColorPickerDialog.newBuilder()
                                .setDialogId(Params.DIALOG_NOVEL_TEXT_COLOR)
                                .setColor(Shaft.sSettings.getNovelHolderTextColor())
                                .show(mActivity);
                    } else {
                        ColorPickerDialog.newBuilder()
                                .setDialogId(Params.DIALOG_NOVEL_TEXT_COLOR)
                                .setColor(getResources().getColor(R.color.white))
                                .show(mActivity);
                    }
                    return true;
                } else if (item.getItemId() == R.id.action_save) {
                    mNovelBean.setLocalSaved(true);
                    String fileName = Params.NOVEL_KEY + mNovelBean.getId();
                    Cache.get().saveModel(fileName, mNovelDetail);
                    DownloadEntity downloadEntity = new DownloadEntity();
                    downloadEntity.setFileName(fileName);
                    downloadEntity.setDownloadTime(System.currentTimeMillis());
                    downloadEntity.setFilePath(PathUtils.getInternalAppCachePath());
                    downloadEntity.setIllustGson(Shaft.sGson.toJson(mNovelBean));
                    AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(downloadEntity);
                    Common.showToast(getString(R.string.string_181), 2);
                    baseBind.transformationLayout.finishTransform();
                    return true;
                } else if (item.getItemId() == R.id.action_txt) {
                    //需要下载txt文件
                    IllustDownload.downloadNovel((BaseActivity<?>) mContext, mNovelBean, novelDetail, new Callback<Uri>() {
                        @Override
                        public void doSomething(Uri t) {
                            Common.showToast(getString(R.string.string_279), 2);
                        }
                    });
                    return true;
                } else if (item.getItemId() == R.id.action_txt_and_share) {
                    //不需要下载txt文件
                    IllustDownload.downloadNovel((BaseActivity<?>) mActivity, mNovelBean, novelDetail, new Callback<Uri>() {
                        @Override
                        public void doSomething(Uri uri) {
                            new Share2.Builder(mActivity)
                                    .setContentType(ShareContentType.FILE)
                                    .setShareFileUri(uri)
                                    .setTitle("Share File")
                                    .build()
                                    .shareBySystem();
                        }
                    });
                    Common.showToast(getString(R.string.string_279), 2);
                    return true;
                }
                return false;
            }
        });
    }

    private void setNovelAdapter() {
        NovelDetail novelDetail = mNovelDetail;
        // 如果解析成功，就使用新方式
        if(novelDetail.getParsedChapters() != null && novelDetail.getParsedChapters().size() > 0){

            baseBind.viewPager.setAdapter(new VNewAdapter(novelDetail.getParsedChapters(), mContext));
            if(novelDetail.getNovel_marker() != null){
                int parsedSize = novelDetail.getParsedChapters().size();
                int pageIndex = Math.min(novelDetail.getNovel_marker().getPage(),novelDetail.getParsedChapters().get(parsedSize-1).getChapterIndex());
                pageIndex = Math.max(pageIndex,novelDetail.getParsedChapters().get(0).getChapterIndex());
                baseBind.viewPager.scrollToPosition(pageIndex-1);
            }

            // 设置书签
            int markerPage = mNovelDetail.getNovel_marker().getPage();
            if(markerPage > 0){
                baseBind.saveNovel.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.novel_marker_add)));
            }else{
                baseBind.saveNovel.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(mContext, R.color.novel_marker_none)));
            }

            baseBind.saveNovel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    View someView = baseBind.viewPager.findChildViewUnder(0,0);
                    int currentPageIndex = baseBind.viewPager.findContainingViewHolder(someView).getAdapterPosition();
                    int chapterIndex = mNovelDetail.getParsedChapters().get(currentPageIndex).getChapterIndex();
                    PixivOperate.postNovelMarker(mNovelDetail.getNovel_marker(), mNovelBean.getId(), chapterIndex, baseBind.saveNovel);
                }
            });
        }
        // 旧方式
        else {
            if (novelDetail.getNovel_text().contains("[newpage]")) {
                String[] partList = novelDetail.getNovel_text().split("\\[newpage]");
                baseBind.viewPager.setAdapter(new VAdapter(
                        Arrays.asList(partList), mContext));
            } else {
                baseBind.viewPager.setAdapter(new VAdapter(
                        Collections.singletonList(novelDetail.getNovel_text()), mContext));
            }
        }
    }
}
