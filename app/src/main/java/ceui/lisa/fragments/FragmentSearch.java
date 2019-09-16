package ceui.lisa.fragments;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.concurrent.TimeUnit;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.SearchHintAdapter;
import ceui.lisa.databinding.FragmentSearchBinding;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.TrendingtagResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentSearch extends BaseBindFragment<FragmentSearchBinding> {

    private ObservableEmitter<String> fuck = null;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_search;
    }

    @Override
    void initData() {
        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = Shaft.statusHeight;
        baseBind.head.setLayoutParams(headParams);
        Observable.create(new ObservableOnSubscribe<String>() {
            @Override
            public void subscribe(ObservableEmitter<String> emitter) throws Exception {
                fuck = emitter;
            }
        }).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .debounce(800, TimeUnit.MILLISECONDS)
                .subscribe(new ErrorCtrl<String>() {
                    @Override
                    public void onNext(String s) {
                        completeWord(s);
                    }
                });
        baseBind.inputBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                String key = String.valueOf(charSequence);
                if (key.length() != 0) {
                    fuck.onNext(key);
                    baseBind.clear.setVisibility(View.VISIBLE);
                } else {
                    baseBind.hintList.setVisibility(View.GONE);
                    baseBind.clear.setVisibility(View.INVISIBLE);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });
        baseBind.clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                baseBind.inputBox.setText("");
            }
        });
    }

    private void completeWord(String key) {
        Retro.getAppApi().searchCompleteWord(sUserModel.getResponse().getAccess_token(), key)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<TrendingtagResponse>() {
                    @Override
                    public void onNext(TrendingtagResponse trendingtagResponse) {
                        ViewGroup.LayoutParams layoutParams = baseBind.hintList.getLayoutParams();
                        layoutParams.height = getResources().getDisplayMetrics().heightPixels * 2 / 3;
                        baseBind.hintList.setVisibility(View.VISIBLE);
                        baseBind.hintList.setLayoutParams(layoutParams);
                        SearchHintAdapter searchHintAdapter = new SearchHintAdapter(trendingtagResponse.getList(), mContext, key);
                        baseBind.hintList.setLayoutManager(new LinearLayoutManager(mContext));
                        baseBind.hintList.setAdapter(searchHintAdapter);
                    }
                });
    }
}
