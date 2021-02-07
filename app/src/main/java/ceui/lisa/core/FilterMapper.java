package ceui.lisa.core;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.PixivOperate;

/**
 * 从列表中筛选掉（去掉)已收藏的作品
 */
public class FilterMapper extends Mapper<ListIllust> {

    private boolean filterFakeStarSize = false;
    private int starSizeLimit = 0;

    @Override
    public ListIllust apply(ListIllust listIllust) {
        super.apply(listIllust);
        if (Shaft.sSettings.isDeleteStarIllust()) {
            //筛选作品，只留下未收藏的作品
            List<IllustsBean> tempList = PixivOperate.getListWithoutBooked(listIllust);
            listIllust.setIllusts(tempList);
        }

        if (filterFakeStarSize && starSizeLimit > 0) {
            //筛选作品，只留下收藏数符合筛选条件的作品
            List<IllustsBean> tempList = PixivOperate.getListWithStarSize(listIllust, starSizeLimit);
            listIllust.setIllusts(tempList);
        }

        return listIllust;
    }

    public FilterMapper enableFilterFakeStarSize(){
        this.filterFakeStarSize = true;
        return this;
    }

    public void updateStarSizeLimit(int starSizeLimit){
        this.starSizeLimit = starSizeLimit;
    }
}
