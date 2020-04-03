package ceui.lisa.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.utils.Common;

public class BaseModel<T> extends ViewModel {

    private MutableLiveData<List<T>> content;
    private String nextUrl = "";
    private int lastSize = 0;
    private boolean isLoaded = false;

    public BaseModel() {
        content = new MutableLiveData<>();
        content.setValue(new ArrayList<>());
    }

    public MutableLiveData<List<T>> getContent() {
        return content;
    }

    public void load(List<T> list, Class c) {
        List<T> current = content.getValue();
        if (current != null) {
            lastSize = current.size();
            current.addAll(list);
        }
        Common.showLog(c.getSimpleName() + "设置了已加载");
        content.setValue(current);
        isLoaded = true;
    }

    public int getLastSize() {
        return lastSize;
    }

    public boolean isLoaded() {
        return isLoaded;
    }

    public String getNextUrl() {
        return nextUrl;
    }

    public void setNextUrl(String nextUrl) {
        this.nextUrl = nextUrl;
    }
}
