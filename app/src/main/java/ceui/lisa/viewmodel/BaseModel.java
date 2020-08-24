package ceui.lisa.viewmodel;

import android.content.Context;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.DataView;


public class BaseModel<T> extends ViewModel implements DataView {

    private MutableLiveData<List<T>> content;
    private String nextUrl = "";
    private boolean isLoaded = false;
    private BaseRepo mBaseRepo;

    public BaseModel() {
    }

    public MutableLiveData<List<T>> getContent() {
        if (content == null) {
            content = new MutableLiveData<>();
            content.setValue(new ArrayList<>());
        }
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

    @Override
    public boolean hasNext() {
        return mBaseRepo.hasNext();
    }

    @Override
    public boolean enableRefresh() {
        return mBaseRepo.enableRefresh();
    }

    @Override
    public RefreshHeader getHeader(Context context) {
        return mBaseRepo.getHeader(context);
    }

    @Override
    public RefreshFooter getFooter(Context context) {
        return mBaseRepo.getFooter(context);
    }

    @Override
    public boolean showNoDataHint() {
        return mBaseRepo.showNoDataHint();
    }

    @Override
    public String token() {
        return mBaseRepo.token();
    }

    public BaseRepo getBaseRepo() {
        return mBaseRepo;
    }

    public void setBaseRepo(BaseRepo baseRepo) {
        mBaseRepo = baseRepo;
    }
}
