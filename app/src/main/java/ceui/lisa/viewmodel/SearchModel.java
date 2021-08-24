package ceui.lisa.viewmodel;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class SearchModel extends ViewModel {

    //关键字
    private final MutableLiveData<String> keyword = new MutableLiveData<>();
    //收藏数
    private final MutableLiveData<String> starSize = new MutableLiveData<>();
    //关键字匹配模式
    private final MutableLiveData<String> searchType = new MutableLiveData<>();

    //排序模式
    private final MutableLiveData<String> sortType = new MutableLiveData<>();
    //上一个排序模式
    private final MutableLiveData<String> lastSortType = new MutableLiveData<>();

    //开始日期
    private final MutableLiveData<String> startDate = new MutableLiveData<>();
    //结束日期
    private final MutableLiveData<String> endDate = new MutableLiveData<>();

    private final MutableLiveData<String> nowGo = new MutableLiveData<>();

    private final MutableLiveData<Boolean> isNovel = new MutableLiveData<>();

    private final MutableLiveData<Boolean> isPremium = new MutableLiveData<>();

    private final MutableLiveData<Integer> r18Restriction = new MutableLiveData<>();

    public MutableLiveData<String> getKeyword() {
        return keyword;
    }

    public MutableLiveData<String> getStarSize() {
        return starSize;
    }


    public MutableLiveData<String> getSearchType() {
        return searchType;
    }

    public MutableLiveData<String> getSortType() {
        return sortType;
    }


    public MutableLiveData<String> getStartDate() {
        return startDate;
    }

    public MutableLiveData<String> getEndDate() {
        return endDate;
    }

    public MutableLiveData<String> getLastSortType() {
        return lastSortType;
    }

    public MutableLiveData<String> getNowGo() {
        return nowGo;
    }

    public MutableLiveData<Boolean> getIsNovel() {
        return isNovel;
    }

    public MutableLiveData<Boolean> getIsPremium() {
        return isPremium;
    }

    public MutableLiveData<Integer> getR18Restriction() {
        return r18Restriction;
    }
}
