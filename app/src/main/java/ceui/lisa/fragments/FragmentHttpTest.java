package ceui.lisa.fragments;

import android.view.View;

import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentNewBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.viewmodel.Hito;
import ceui.lisa.viewmodel.HitoModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import rxhttp.RxHttp;

public class FragmentHttpTest extends BaseFragment<FragmentNewBinding> {

    private HitoModel mHitoModel;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_new;
    }

    @Override
    public void initView(View view) {
        mHitoModel = new ViewModelProvider(this).get(HitoModel.class);
    }

    @Override
    void initData() {
        mHitoModel.getContent().observe(this, new androidx.lifecycle.Observer<Hito>() {
            @Override
            public void onChanged(Hito hito) {
                baseBind.content.setText(hito.toString());
            }
        });
        if (mHitoModel.getContent().getValue() == null) {
            RxHttp.get("https://api.imjad.cn/hitokoto/?cat=&charset=utf-8&encode=json")
                    .asClass(Hito.class)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<Hito>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull Hito hito) {
                            mHitoModel.getContent().setValue(hito);
                        }

                        @Override
                        public void onError(@NonNull Throwable e) {
                            e.printStackTrace();
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        }

    }
}
