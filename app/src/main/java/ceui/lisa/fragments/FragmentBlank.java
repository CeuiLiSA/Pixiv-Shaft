package ceui.lisa.fragments;

import android.support.v7.widget.CardView;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.bumptech.glide.Glide;
import com.rengwuxian.materialedittext.MaterialEditText;

import ceui.lisa.R;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Local;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

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
