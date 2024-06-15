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
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.databinding.RecyNovelMarkersBinding;
import ceui.lisa.models.MarkedNovelItem;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class NovelMarkersAdapter extends BaseAdapter<MarkedNovelItem, RecyNovelMarkersBinding> {
    public NovelMarkersAdapter(List<MarkedNovelItem> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_novel_markers;
    }

    @Override
    public void bindData(MarkedNovelItem target, ViewHolder<RecyNovelMarkersBinding> bindView, int position) {
        if (target.getNovel().getSeries() != null && !TextUtils.isEmpty(target.getNovel().getSeries().getTitle())) {
            bindView.baseBind.series.setVisibility(View.VISIBLE);
            bindView.baseBind.series.setText(String.format(mContext.getString(R.string.string_184),
                    target.getNovel().getSeries().getTitle()));
            bindView.baseBind.series.setOnClickListener(v -> {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.ID, target.getNovel().getSeries().getId());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列详情");
                    mContext.startActivity(intent);
            });
        } else {
            bindView.baseBind.series.setVisibility(View.GONE);
        }
        bindView.baseBind.title.setText(target.getNovel().getTitle());
        bindView.baseBind.novelTag.setAdapter(new TagAdapter<TagsBean>(target.getNovel().getTags()) {
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
                intent.putExtra(Params.KEY_WORD, target.getNovel().getTags().get(position).getName());
                intent.putExtra(Params.INDEX, 1);
                mContext.startActivity(intent);
                return true;
            }
        });
        bindView.baseBind.author.setText(target.getNovel().getUser().getName());
        bindView.baseBind.howManyWord.setText(String.format(Locale.getDefault(), "%d字", target.getNovel().getText_length()));
        bindView.baseBind.bookmarkCount.setText(String.valueOf(target.getNovel().getTotal_bookmarks()));
        Glide.with(mContext).load(GlideUtil.getUrl(target.getNovel().getImage_urls().getMaxImage())).into(bindView.baseBind.cover);
        Glide.with(mContext).load(GlideUtil.getHead(target.getNovel().getUser())).into(bindView.baseBind.userHead);

        bindView.baseBind.cover.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(Params.URL, GlideUtil.getUrl(target.getNovel().getImage_urls().getMaxImage()).toStringUrl());
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情");
            mContext.startActivity(intent);
        });

        bindView.baseBind.userHead.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, UserActivity.class);
            intent.putExtra(Params.USER_ID, target.getNovel().getUser().getId());
            mContext.startActivity(intent);
        });

        bindView.baseBind.author.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, UserActivity.class);
            intent.putExtra(Params.USER_ID, target.getNovel().getUser().getId());
            mContext.startActivity(intent);
        });

        bindView.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(Params.CONTENT, target.getNovel());
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
            intent.putExtra("hideStatusBar", true);
            mContext.startActivity(intent);
        });

        bindView.baseBind.mark.setOnClickListener(v ->
                PixivOperate.postNovelMarker(target.getNovel_marker(),
                                             target.getNovel().getId(),
                                             bindView.baseBind.mark));

    }
}
