package ceui.lisa.fragments;

import com.blankj.utilcode.util.BarUtils;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentHBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Display;
import ceui.lisa.models.HitoResponse;
import ceui.lisa.utils.ClipBoardUtils;
import ceui.lisa.utils.Common;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentH extends BaseBindFragment<FragmentHBinding> implements Display<HitoResponse> {

    private static final String[] TYPES = new String[]{"动画", "漫画", "游戏", "小说", "原创", "网络", "其他"};

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_h;
    }

    @Override
    void initData() {
        BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.hito_bg));
        baseBind.next.setOnClickListener(v -> freshData());
        baseBind.hitoText.setOnLongClickListener(v -> {
            ClipBoardUtils.putTextIntoClipboard(mContext, baseBind.hitoText.getText().toString());
            return true;
        });
        baseBind.like.setOnClickListener(v -> Common.showToast("下版本更新再做吧？"));
        freshData();
    }

    private void freshData() {
        Retro.getHitoApi()
                .getHito()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<HitoResponse>() {
                    @Override
                    public void success(HitoResponse hitoResponse) {
                        invoke(hitoResponse);
                    }
                });
    }

    @Override
    public void invoke(HitoResponse response) {
        baseBind.hitoText.setText(String.format("%s", response.getHitokoto()));
        baseBind.from.setText(String.format("《%s》", response.getFrom()));
        baseBind.creator.setText(String.format("—— %s", response.getCreator()));
        baseBind.hitoType.setText(TYPES[(int) response.getType().charAt(0) - (int) 'a']);
    }
}
