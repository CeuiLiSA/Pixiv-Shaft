package ceui.lisa.viewmodel;

import android.content.Context;

import androidx.lifecycle.ViewModel;

import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.core.BaseRepo;
import ceui.lisa.utils.Common;


public class BaseModel<T> extends ViewModel{

    private List<T> content = new ArrayList<>();
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

    public BaseRepo getBaseRepo() {
        return mBaseRepo;
    }

    public void setBaseRepo(BaseRepo baseRepo) {
        mBaseRepo = baseRepo;
    }
}
