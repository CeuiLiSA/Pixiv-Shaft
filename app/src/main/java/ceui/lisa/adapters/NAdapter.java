package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.NovelBean;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;

public class NAdapter extends BaseAdapter<NovelBean, RecyNovelBinding> {

    public NAdapter(List<NovelBean> targetList, Context context) {
        super(targetList, context);
        handleClick();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_novel;
    }

    @Override
    public void bindData(NovelBean target, ViewHolder<RecyNovelBinding> bindView, int position) {
        bindView.baseBind.title.setText(target.getTitle());
        if (target.getSeries() != null && !TextUtils.isEmpty(target.getSeries().getTitle())) {
            bindView.baseBind.series.setText(String.format("系列：%s", target.getSeries().getTitle()));
        }
        bindView.baseBind.author.setText(target.getUser().getName());
        bindView.baseBind.howManyWord.setText(target.getText_length() + "字");
        Glide.with(mContext).load(GlideUtil.getMediumImg(target.getImage_urls().getMaxImage())).into(bindView.baseBind.cover);
        Glide.with(mContext).load(GlideUtil.getHead(target.getUser())).into(bindView.baseBind.userHead);
        if (target.isIs_bookmarked()) {
            bindView.baseBind.like.setText("取消收藏");
        } else {
            bindView.baseBind.like.setText("收藏");
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
            bindView.baseBind.like.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (target.isIs_bookmarked()) {

                    } else {
                        PixivOperate.postLikeNovel(allIllust.get(position), Shaft.sUserModel,
                                FragmentLikeIllust.TYPE_PRIVATE, bindView.baseBind.like);
                    }
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
                    DataChannel.get().setNovelList(allIllust);
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.INDEX, position);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                    intent.putExtra("hideStatusBar", false);
                    mContext.startActivity(intent);
                } else if (viewType == 1) {
                    PixivOperate.postLikeNovel(allIllust.get(position), Shaft.sUserModel,
                            FragmentLikeIllust.TYPE_PUBLUC, v);
                } else if (viewType == 2) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.URL, allIllust.get(position).getImage_urls().getMaxImage());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情");
                    mContext.startActivity(intent);
                }
            }
        });
    }
}
