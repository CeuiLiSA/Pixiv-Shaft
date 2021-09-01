package ceui.lisa.viewmodel;

import java.util.ArrayList;
import java.util.List;

import androidx.lifecycle.ViewModel;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.helper.AppLevelViewModelHelper;
import ceui.lisa.utils.Common;


public class BaseModel<T> extends ViewModel{

    List<T> content = null;
    private boolean isLoaded = false;
    private BaseRepo mBaseRepo;

    public BaseModel() {
        Common.showLog("trace 构造 000");
    }

    public List<T> getContent() {
        if (content == null) {
            content = new ArrayList<>();
        }
        return content;
    }

    public void load(List<T> list, boolean isFresh) {
        if (isFresh) {
            content.clear();
        }
        content.addAll(list);
        isLoaded = true;
    }

    public void load(List<T> list, int index) {
        content.addAll(index, list);
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

    public void tidyAppViewModel(){
        tidyAppViewModel(content);
    }

    public void tidyAppViewModel(List<T> list) {
        extracted(list);
    }

    private void extracted(List<T> list) {
        AppLevelViewModelHelper.fill(list);
    }
}
