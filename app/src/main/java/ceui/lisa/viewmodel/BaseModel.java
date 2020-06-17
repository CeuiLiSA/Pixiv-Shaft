package ceui.lisa.viewmodel;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;


public class BaseModel<T> extends ViewModel {

    private MutableLiveData<List<T>> content;
    private String nextUrl = "";
    private String token;
    private boolean isLoaded = false;

    public BaseModel() {
        token = Shaft.sUserModel.getResponse().getAccess_token();
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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
