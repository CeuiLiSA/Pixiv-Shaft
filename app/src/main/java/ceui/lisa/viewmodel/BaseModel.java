package ceui.lisa.viewmodel;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;


public class BaseModel<T> extends ViewModel {

    private MutableLiveData<List<T>> content;
    private String nextUrl = "";
    private boolean isLoaded = false;

    public BaseModel() {
        content = new MutableLiveData<>();
        content.setValue(new ArrayList<>());
    }

    public MutableLiveData<List<T>> getContent() {
        return content;
    }

    public void load(List<T> list) {
        List<T> current = content.getValue();
        if (current != null) {
            current.addAll(list);
        }
        content.setValue(current);
        isLoaded = true;
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
