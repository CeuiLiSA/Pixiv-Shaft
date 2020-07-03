package ceui.lisa.fragments;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.base.SwipeFragment;
import ceui.lisa.databinding.FragmentIllustBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import jp.wasabeef.glide.transformations.BlurTransformation;
import me.next.tagview.TagCloudView;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

public class FragmentIllust extends SwipeFragment<FragmentIllustBinding> {

    private IllustsBean illust;

    public static FragmentIllust newInstance(IllustsBean illustsBean) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, illustsBean);
        FragmentIllust fragment = new FragmentIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        illust = (IllustsBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_illust;
    }

    @Override
    protected void initView() {
        if (illust != null) {
            Glide.with(mContext)
                    .load(GlideUtil.getOriginal(illust, 0))
                    .transition(withCrossFade())
                    .into(baseBind.illustImage);

            ViewGroup.LayoutParams params = baseBind.illustImage.getLayoutParams();
            int imageSize = mContext.getResources().getDisplayMetrics().widthPixels;
            params.height = imageSize * illust.getHeight() / illust.getWidth();
            params.width = imageSize;
            baseBind.illustImage.setLayoutParams(params);

            Glide.with(mContext)
                    .load(GlideUtil.getOriginalWithInvertProxy(illust, 0))
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(baseBind.illustImage);


            Glide.with(mContext)
                    .load(GlideUtil.getHead(illust.getUser()))
                    .transition(withCrossFade())
                    .into(baseBind.userHead);
            baseBind.title.setText(illust.getTitle());
            baseBind.userName.setText(illust.getUser().getName());
            baseBind.postTime.setText(illust.getCreate_date().substring(0, 16));
            baseBind.totalView.setText(String.valueOf(illust.getTotal_view()));
            baseBind.totalLike.setText(String.valueOf(illust.getTotal_bookmarks()));
            baseBind.illustTag.setAdapter(new TagAdapter<TagsBean>(illust.getTags()) {
                @Override
                public View getView(FlowLayout parent, int position, TagsBean s) {
                    TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_line_text_new,
                            parent, false);
                    tv.setText(s.getName());
                    return tv;
                }
            });

            baseBind.illustLike.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.CONTENT, illust);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "喜欢这个作品的用户");
                    startActivity(intent);
                }
            });
            if (illust.isIs_bookmarked()) {
                baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp);
            } else {
                baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp);
            }
            baseBind.postLike.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (illust.isIs_bookmarked()) {
                        baseBind.postLike.setImageResource(R.drawable.ic_favorite_grey_24dp);
                    } else {
                        baseBind.postLike.setImageResource(R.drawable.ic_favorite_red_24dp);
                    }
                    PixivOperate.postLike(illust, FragmentLikeIllust.TYPE_PUBLUC);
                }
            });
            baseBind.postLike.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!illust.isIs_bookmarked()) {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(Params.ILLUST_ID, illust.getId());
                        intent.putExtra(Params.LAST_CLASS, getClass().getSimpleName());
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                        startActivity(intent);
                    }
                    return true;
                }
            });
        }

        baseBind.download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float current = baseBind.coreLinear.getTranslationY();
                baseBind.coreLinear.setTranslationY(current - 20.0f);
                Common.showLog(className + current);
            }
        });

        baseBind.showComment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float current = baseBind.coreLinear.getTranslationY();
                baseBind.coreLinear.setTranslationY(current + 20.0f);
            }
        });
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
    }
}
