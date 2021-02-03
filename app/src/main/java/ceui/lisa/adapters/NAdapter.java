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
import ceui.lisa.activities.UserActivity;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.NovelBean;
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
                        intent.putExtra(Params.ID, allIllust.get(position).getSeries().getId());
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列详情");
                        mContext.startActivity(intent);
                    }
                });
            }
        } else {
            bindView.baseBind.series.setVisibility(View.GONE);
        }
        if (showShop) {
            bindView.baseBind.title.setText(String.format("#%d %s", position + 1, target.getTitle()));
        } else {
            bindView.baseBind.title.setText(target.getTitle());
        }
        bindView.baseBind.author.setText(target.getUser().getName());
        bindView.baseBind.howManyWord.setText(String.format("%d字", target.getText_length()));
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
                    if (target.isIs_bookmarked()) {

                    } else {
                        PixivOperate.postLikeNovel(allIllust.get(position), Shaft.sUserModel,
                                Params.TYPE_PRIVATE, bindView.baseBind.like);
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
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.CONTENT, allIllust.get(position));
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                    intent.putExtra("hideStatusBar", true);
                    mContext.startActivity(intent);
                } else if (viewType == 1) {
                    PixivOperate.postLikeNovel(allIllust.get(position), Shaft.sUserModel,
                            Params.TYPE_PUBLUC, v);
                } else if (viewType == 2) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.URL, allIllust.get(position).getImage_urls().getMaxImage());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情");
                    mContext.startActivity(intent);
                } else if (viewType == 3) {
                    Intent intent = new Intent(mContext, UserActivity.class);
                    intent.putExtra(Params.USER_ID, allIllust.get(position).getUser().getId());
                    mContext.startActivity(intent);
                }
            }
        });
    }
}
