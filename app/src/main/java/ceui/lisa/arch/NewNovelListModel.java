package ceui.lisa.arch;

import ceui.lisa.core.RemoteRepo;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import io.reactivex.Observable;

public class NewNovelListModel extends ListModel<NovelBean, ListNovel> {

    @Override
    public RemoteRepo<ListNovel> repository() {
        return new RemoteRepo<ListNovel>() {
            @Override
            public Observable<ListNovel> initApi() {
                return Retro.getAppApi().getNewNovels(token());
            }

            @Override
            public Observable<ListNovel> initNextApi() {
                return Retro.getAppApi().getNextNovel(token(), nextUrl);
            }
        };
    }
}
