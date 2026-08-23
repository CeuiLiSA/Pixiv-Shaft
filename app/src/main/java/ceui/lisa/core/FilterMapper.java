package ceui.lisa.core;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.model.ListIllust;
import ceui.loxia.Illust;
import ceui.lisa.utils.PixivOperate;

/**
 * 从列表中筛选掉（去掉)已收藏的作品
 */
public class FilterMapper extends Mapper<ListIllust> {

    private boolean filterStarSize = false;
    private int starSizeLimit = 0;
    // 收藏量区间的上限（bookmark_num_max 的客户端兜底）；0 = 不限
    private int starSizeMaxLimit = 0;

    @Override
    public ListIllust apply(ListIllust listIllust) {
        super.apply(listIllust);
        if (Shaft.sSettings.isDeleteStarIllust()) {
            //筛选作品，只留下未收藏的作品
            List<Illust> tempList = PixivOperate.getListWithoutBooked(listIllust);
            listIllust.setIllusts(tempList);
        }

        if (filterStarSize && (starSizeLimit > 0 || starSizeMaxLimit > 0)) {
            //筛选作品，只留下收藏数符合筛选条件的作品
            List<Illust> tempList = PixivOperate.getListWithStarSize(listIllust, starSizeLimit, starSizeMaxLimit);
            listIllust.setIllusts(tempList);
        }

        return listIllust;
    }

    public FilterMapper enableFilterStarSize(){
        this.filterStarSize = true;
        return this;
    }

    public void updateStarSizeLimit(int starSizeLimit){
        this.starSizeLimit = starSizeLimit;
    }

    public void updateStarSizeMaxLimit(int starSizeMaxLimit){
        this.starSizeMaxLimit = starSizeMaxLimit;
    }
}
