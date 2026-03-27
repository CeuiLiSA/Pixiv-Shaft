package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.VActivity;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.RecyTimelineIllustBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.loxia.DateParse;

public class TimelineAdapter extends BaseAdapter<IllustsBean, RecyTimelineIllustBinding> {

    public TimelineAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
        handleClick();
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_timeline_illust;
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyTimelineIllustBinding> bindView, int position) {
        // Timeline lines
        bindView.baseBind.lineTop.setVisibility(position == 0 ? View.INVISIBLE : View.VISIBLE);

        // User info
        if (target.getUser() != null) {
            bindView.baseBind.userName.setText(target.getUser().getName());
            GlideUrl userIconUrl = GlideUtil.getHead(target.getUser());
            if (userIconUrl != null) {
                Glide.with(mContext)
                        .load(userIconUrl)
                        .into(bindView.baseBind.userIcon);
            }
        }

        // Time
        bindView.baseBind.postTime.setText(DateParse.INSTANCE.getTimeAgo(mContext, target.getCreate_date()));

        // Image
        GlideUrl imgUrl = GlideUtil.getLargeImage(target);
        int screenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        float density = mContext.getResources().getDisplayMetrics().density;
        // card width = screenWidth - 12dp*2 (parent padding) - 24dp (rail) - 10dp (card margin)
        int cardWidth = screenWidth - (int) (58 * density);
        int maxHeight = (int) (screenWidth * 0.85f);
        float ratio = (float) target.getHeight() / (float) target.getWidth();
        int imgHeight = Math.max((int) (cardWidth * ratio), (int) (120 * density));
        ViewGroup.LayoutParams imgParams = bindView.baseBind.illustImage.getLayoutParams();
        imgParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        imgParams.height = Math.min(imgHeight, maxHeight);
        bindView.baseBind.illustImage.setLayoutParams(imgParams);

        Glide.with(mContext)
                .load(imgUrl)
                .placeholder(R.color.second_light_bg)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(bindView.baseBind.illustImage);

        // Title
        bindView.baseBind.title.setText(target.getTitle());

        // Stats
        bindView.baseBind.viewCount.setText(String.format(Locale.getDefault(), "%,d", target.getTotal_view()));
        bindView.baseBind.bookmarkCount.setText(String.format(Locale.getDefault(), "%,d", target.getTotal_bookmarks()));

        // Page count
        if (target.getPage_count() > 1) {
            bindView.baseBind.pageCount.setVisibility(View.VISIBLE);
            bindView.baseBind.pageCount.setText(String.format(Locale.getDefault(), "%dP", target.getPage_count()));
        } else {
            bindView.baseBind.pageCount.setVisibility(View.GONE);
        }

        // Click
        bindView.baseBind.card.setOnClickListener(view -> {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(view, position, 0);
            }
        });
    }

    private void handleClick() {
        setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                final PageData pageData = new PageData(uuid, nextUrl, allItems);
                Container.get().addPageToMap(pageData);

                Intent intent = new Intent(mContext, VActivity.class);
                intent.putExtra(Params.POSITION, position);
                intent.putExtra(Params.PAGE_UUID, uuid);
                mContext.startActivity(intent);
            }
        });
    }
}
