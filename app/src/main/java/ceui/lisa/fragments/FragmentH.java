package ceui.lisa.fragments;

import com.blankj.utilcode.util.BarUtils;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentHBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.HitoResponse;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentH extends BaseBindFragment<FragmentHBinding> {

    private static final String[] TYPES = new String[]{"动画", "漫画", "游戏", "小说", "原创", "网络", "其他"};

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_h;
    }

    @Override
    void initData() {
        BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.hito_bg));
        freshData();
        baseBind.next.setOnClickListener(v -> freshData());
    }

    private void freshData() {
        Retro.getHitoApi()
                .getHito()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<HitoResponse>() {
                    @Override
                    public void success(HitoResponse hitoResponse) {
                        setData(hitoResponse);
                    }
                });
    }

    private void setData(HitoResponse response) {
        baseBind.hitoText.setText(String.format("%s", response.getHitokoto()));
        baseBind.from.setText(String.format("《%s》", response.getFrom()));
        baseBind.creator.setText(String.format("—— %s", response.getCreator()));
        baseBind.hitoType.setText(TYPES[(int) response.getType().charAt(0) - (int) 'a']);
    }
}
