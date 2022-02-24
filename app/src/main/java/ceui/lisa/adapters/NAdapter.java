package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class NAdapter extends BaseAdapter<NovelBean, RecyNovelBinding> {

    private boolean showShop = false;

    public NAdapter(List<NovelBean> targetList, Context context) {
        super(targetList, context);
        handleClick();
    }

    public NAdapter(List<NovelBean> targetList, Context context, boolean showShop) {
        super(targetList, context);
        handleClick();
        this.showShop = showShop;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_novel;
    }

    @Override
    public void bindData(NovelBean target, ViewHolder<RecyNovelBinding> bindView, int position) {
        if (target.getSeries() != null && !TextUtils.isEmpty(target.getSeries().getTitle())) {
            bindView.baseBind.series.setVisibility(View.VISIBLE);
            bindView.baseBind.series.setText(String.format(mContext.getString(R.string.string_184),
                    target.getSeries().getTitle()));
            if (showShop) {

            } else {
                bindView.baseBind.series.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(Params.ID, allItems.get(position).getSeries().getId());
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列详情");
                        mContext.startActivity(intent);
                    }
                });
            }
        } else {
            bindView.baseBind.series.setVisibility(View.GONE);
        }
        if (showShop) {
            bindView.baseBind.title.setText(String.format(Locale.getDefault(), "#%d %s", position + 1, target.getTitle()));
        } else {
            bindView.baseBind.title.setText(target.getTitle());
        }
        bindView.baseBind.novelTag.setAdapter(new TagAdapter<TagsBean>(target.getTags()) {
            @Override
            public View getView(FlowLayout parent, int position, TagsBean s) {
                TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_line_text_new,
                        parent, false);
                String tag = s.getName();
                tv.setText(tag);
                return tv;
            }
        });
        bindView.baseBind.novelTag.setOnTagClickListener(new TagFlowLayout.OnTagClickListener() {
            @Override
            public boolean onTagClick(View view, int position, FlowLayout parent) {
                Intent intent = new Intent(mContext, SearchActivity.class);
                intent.putExtra(Params.KEY_WORD, target.getTags().get(position).getName());
                intent.putExtra(Params.INDEX, 1);
                mContext.startActivity(intent);
                return true;
            }
        });
        bindView.baseBind.author.setText(target.getUser().getName());
        bindView.baseBind.howManyWord.setText(String.format(Locale.getDefault(), "%d字", target.getText_length()));
        bindView.baseBind.bookmarkCount.setText(String.valueOf(target.getTotal_bookmarks()));
        Glide.with(mContext).load(GlideUtil.getUrl(target.getImage_urls().getMaxImage())).into(bindView.baseBind.cover);
        Glide.with(mContext).load(GlideUtil.getHead(target.getUser())).into(bindView.baseBind.userHead);
        if (target.isIs_bookmarked()) {
            bindView.baseBind.like.setText(R.string.string_169);
        } else {
            bindView.baseBind.like.setText(R.string.string_170);
        }
        if (mOnItemClickListener != null) {
            bindView.baseBind.like.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemClickListener.onItemClick(bindView.baseBind.like, position, 1);
                }
            });
            bindView.baseBind.cover.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemClickListener.onItemClick(bindView.baseBind.like, position, 2);
                }
            });
            bindView.baseBind.userHead.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemClickListener.onItemClick(bindView.baseBind.like, position, 3);
                }
            });
            bindView.baseBind.author.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemClickListener.onItemClick(bindView.baseBind.like, position, 3);
                }
            });
            bindView.baseBind.like.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.ILLUST_ID, target.getId());
                    intent.putExtra(Params.DATA_TYPE, Params.TYPE_NOVEL);
                    intent.putExtra(Params.TAG_NAMES, target.getTagNames());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                    mContext.startActivity(intent);
                    return true;
                }
            });
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }

    private void handleClick() {
        setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (viewType == 0) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.CONTENT, allItems.get(position));
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                    intent.putExtra("hideStatusBar", true);
                    mContext.startActivity(intent);
                } else if (viewType == 1) {
                    PixivOperate.postLikeNovel(allItems.get(position), Shaft.sUserModel,
                            Params.TYPE_PUBLIC, v);
                } else if (viewType == 2) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.URL, GlideUtil.getUrl(allItems.get(position).getImage_urls().getMaxImage()).toStringUrl());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情");
                    mContext.startActivity(intent);
                } else if (viewType == 3) {
                    Intent intent = new Intent(mContext, UserActivity.class);
                    intent.putExtra(Params.USER_ID, allItems.get(position).getUser().getId());
                    mContext.startActivity(intent);
                }
            }
        });
    }
}
