package ceui.lisa.viewmodel;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import rxhttp.RxHttp;


public class HitoModel extends ViewModel {

    private MutableLiveData<Hito> content;

    public HitoModel() {
        content = new MutableLiveData<>();

    }

    public MutableLiveData<Hito> getContent() {
        return content;
    }

    public void setContent(MutableLiveData<Hito> content) {
        this.content = content;
    }
}
