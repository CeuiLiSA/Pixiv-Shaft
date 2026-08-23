package ceui.lisa.model;

import java.util.List;

import ceui.loxia.Illust;

public class RecmdIllust extends ListIllust {

    private List<Illust> ranking_illusts;

    public List<Illust> getRanking_illusts() {
        return ranking_illusts;
    }

    public void setRanking_illusts(List<Illust> ranking_illusts) {
        this.ranking_illusts = ranking_illusts;
    }
}
