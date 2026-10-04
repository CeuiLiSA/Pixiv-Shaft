package ceui.lisa.core;

import java.util.List;

import ceui.lisa.model.ListNovel;
import ceui.lisa.utils.PixivOperate;
import ceui.loxia.Novel;

/**
 * 搜索结果按收藏数区间筛选小说 —— 对齐插画侧 {@link FilterMapper}。
 *
 * 背景：小说端点同样会收到官方 `bookmark_num_min` / `bookmark_num_max`，但**非会员端点会静默
 * 无视这组参数**（popular-preview 预览端点；以及非会员拿自己的 token 打 /v1/search/novel）。
 * 插画侧靠 {@link FilterMapper} 在客户端二次兜底，小说侧此前只有普通 {@link Mapper}，
 * 参数被无视后过滤就整条消失（表现为「选了喜欢！数但结果没变」）。这里补上同一层兜底。
 *
 * 与插画侧的差异：{@link FilterMapper} 会把「Xusers入り」关键字桶折进下限
 * （取两条桶里较高的门槛）；小说侧**不折** —— 小说路径里官方 bookmark 参数与关键字后缀互斥
 * （见 {@code SearchNovelRepo}，走了官方参数就不再拼后缀），折进去会覆盖用户显式设的
 * bookmarkMin，也违背那条被 WebNovelSearchParamsTest 锁定的策略。
 */
public class NovelFilterMapper extends Mapper<ListNovel> {

    private boolean filterStarSize = false;
    private int starSizeLimit = 0;
    // 收藏量区间的上限（bookmark_num_max 的客户端兜底）；0 = 不限
    private int starSizeMaxLimit = 0;

    @Override
    public ListNovel apply(ListNovel listNovel) {
        super.apply(listNovel);
        if (filterStarSize && (starSizeLimit > 0 || starSizeMaxLimit > 0)) {
            //筛选作品，只留下收藏数符合筛选条件的小说
            List<Novel> tempList =
                    PixivOperate.getListWithNovelStarSize(listNovel, starSizeLimit, starSizeMaxLimit);
            listNovel.setNovels(tempList);
        }

        return listNovel;
    }

    public NovelFilterMapper enableFilterStarSize() {
        this.filterStarSize = true;
        return this;
    }

    public void updateStarSizeLimit(int starSizeLimit) {
        this.starSizeLimit = starSizeLimit;
    }

    public void updateStarSizeMaxLimit(int starSizeMaxLimit) {
        this.starSizeMaxLimit = starSizeMaxLimit;
    }
}