package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.bumptech.glide.Glide;

import java.util.Collections;
import java.util.UUID;

import ceui.lisa.R;
import ceui.lisa.activities.VActivity;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.FragmentImageBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.core.Container;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentImage extends BaseFragment<FragmentImageBinding> {

    private IllustsBean mIllustsBean;

    public static FragmentImage newInstance(IllustsBean i) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, i);
        FragmentImage fragment = new FragmentImage();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        mIllustsBean = ((IllustsBean) bundle.getSerializable(Params.CONTENT));
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_image;
    }

    @Override
    public void initView(View view) {
        Glide.with(mContext)
                .load(GlideUtil.getLargeImage(mIllustsBean))
                .transition(withCrossFade())
                .into(baseBind.illustImage);
        baseBind.illustImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View pView) {
                final String uuid = UUID.randomUUID().toString();
                final PageData pageData = new PageData(uuid, Collections.singletonList(mIllustsBean));
                Container.get().addPageToMap(pageData);

                Intent intent = new Intent(mContext, VActivity.class);
                intent.putExtra(Params.POSITION, 0);
                intent.putExtra(Params.PAGE_UUID, uuid);
                mContext.startActivity(intent);


//                DataChannel.get().setIllustList(Collections.singletonList(mIllustsBean));
//                Intent intent = new Intent(mContext, ViewPagerActivity.class);
//                intent.putExtra("position", 0);
//                startActivity(intent);
            }
        });
    }
}
