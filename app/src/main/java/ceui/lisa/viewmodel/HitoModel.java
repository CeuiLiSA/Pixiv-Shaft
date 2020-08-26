package ceui.lisa.viewmodel;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;


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
