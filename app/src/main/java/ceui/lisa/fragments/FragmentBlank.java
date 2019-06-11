package ceui.lisa.fragments;

import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import ceui.lisa.R;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.GlideUtil;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class FragmentBlank extends BaseFragment {

    public IllustsBean getIllustsBean() {
        return mIllustsBean;
    }

    public void setIllustsBean(IllustsBean illustsBean) {
        mIllustsBean = illustsBean;
    }

    private IllustsBean mIllustsBean;

    public static FragmentBlank newInstance(IllustsBean illustsBean){
        FragmentBlank fragmentBlank = new FragmentBlank();
        fragmentBlank.setIllustsBean(illustsBean);
        return fragmentBlank;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_blank;
    }

    @Override
    View initView(View v) {
        ImageView imageView = v.findViewById(R.id.head_image);
        if(mIllustsBean != null) {
            Glide.with(mContext).
                    load(GlideUtil.getLargeImage(mIllustsBean))
                    .transition(withCrossFade())
                    .into(imageView);
        }
        return v;
    }

    @Override
    void initData() {

    }

}
