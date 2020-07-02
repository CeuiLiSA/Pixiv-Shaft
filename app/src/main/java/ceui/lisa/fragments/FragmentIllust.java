package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.SearchActivity;
import ceui.lisa.databinding.FragmentIllustBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import jp.wasabeef.glide.transformations.BlurTransformation;
import me.next.tagview.TagCloudView;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

public class FragmentIllust extends BaseFragment<FragmentIllustBinding> {

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
    public void initView(View view) {
        if (illust != null) {
            Glide.with(mContext)
                    .load(R.mipmap.blue_img_bg)
                    .transition(withCrossFade())
                    .into(baseBind.illustImage);

            Glide.with(mContext)
                    .load(R.mipmap.blue_img_bg)
                    .transition(withCrossFade())
                    .into(baseBind.userHead);
            Common.showLog(className + illust.getTitle());

            baseBind.illustTag.setAdapter(new TagAdapter<TagsBean>(illust.getTags()) {
                @Override
                public View getView(FlowLayout parent, int position, TagsBean s) {
                    TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_line_text_new,
                            parent, false);
                    tv.setText(s.getName());
                    return tv;
                }
            });

        }
    }
}
