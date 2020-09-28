package ceui.lisa.viewmodel;

import android.content.Context;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.DataView;
import ceui.lisa.utils.Common;


public class BaseModel<T> extends ViewModel implements DataView {

    private List<T> content = new ArrayList<>();
    private String nextUrl = "";
    private boolean isLoaded = false;
    private BaseRepo mBaseRepo;

    public List<T> getContent() {
        return content;
    }

    public void load(List<T> list) {
        if (!Common.isEmpty(list)) {
            content.addAll(list);
        }
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

    @Override
    public boolean localData() {
        return mBaseRepo.localData();
    }

    public BaseRepo getBaseRepo() {
        return mBaseRepo;
    }

    public void setBaseRepo(BaseRepo baseRepo) {
        mBaseRepo = baseRepo;
    }
}
