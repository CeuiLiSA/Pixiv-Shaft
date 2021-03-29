package ceui.lisa.standard;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.utils.Common;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public abstract class ListViewModel<Bean> extends ViewModel {

    private boolean isLoaded = false;

    private final MutableLiveData<List<Bean>> mLiveData = new MutableLiveData<>();
    private final List<Bean> mValues = new ArrayList<>();
    private final Repository<Bean> mRepository = initRepository();

    public void load(boolean isRefresh) {
        if (isRefresh) {
            mRepository.initApi()
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<ListShow<Bean>>() {
                        @Override
                        public void success(ListShow<Bean> beanListShow) {
                            isLoaded = true;
                            List<Bean> income = beanListShow.getList();
                            if (!Common.isEmpty(income)) {
                                mValues.addAll(income);
                                mLiveData.setValue(mValues);
                            }
                        }
                    });
        }
    }

    public abstract Repository<Bean> initRepository();

    public static final int PAGE_SIZE = 20;

}
