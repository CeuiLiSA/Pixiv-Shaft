package ceui.lisa.viewmodel;

import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.model.ColorItem;
import ceui.lisa.utils.Common;

public class VPModel extends ViewModel {

    public VPModel() {
        Common.showLog("trace VPModel 构造" + allPages.size());
        for (int i = 0; i < 8; i++) {
            allPages.add(new ArrayList<>());
        }
    }

    private List<List<ColorItem>> allPages = new ArrayList<>();

    public List<List<ColorItem>> getAllPages() {
        return allPages;
    }

    public void setAllPages(List<List<ColorItem>> allPages) {
        this.allPages = allPages;
    }

    public List<ColorItem> getRightList(int index) {
        return allPages.get(index);
    }
}
