package ceui.lisa.viewmodel;


import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;

import ceui.lisa.models.IllustsBean;

public class Dust extends ViewModel {

    private MutableLiveData<List<IllustsBean>> dust;
    private MutableLiveData<Integer> index;

    public MutableLiveData<List<IllustsBean>> getDust() {
        if (dust == null) {
            dust = new MutableLiveData<>();
        }
        return dust;
    }

    public MutableLiveData<Integer> getIndex() {
        if (index == null) {
            index = new MutableLiveData<>();
        }
        return index;
    }
}
