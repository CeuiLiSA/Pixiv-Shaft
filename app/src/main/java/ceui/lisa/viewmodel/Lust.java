package ceui.lisa.viewmodel;


import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import ceui.lisa.models.IllustsBean;

public class Lust extends ViewModel {

    private MutableLiveData<IllustsBean> lust;


    public MutableLiveData<IllustsBean> getLust() {
        if (lust == null) {
            lust = new MutableLiveData<>();
        }
        return lust;
    }
}
