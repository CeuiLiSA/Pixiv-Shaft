package ceui.lisa.feature.worker;

import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import ceui.lisa.activities.Shaft;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.models.NullResponse;
import ceui.lisa.utils.Params;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class BatchStarTask extends AbstractTask {

    private final int illustID;
    private final int starType; // 0收藏，1取消收藏

    public BatchStarTask(String name, int illustID, int starType) {
        this.illustID = illustID;
        this.starType = starType;
        if (starType == 0) {
            this.name = ("添加收藏 " + name);
        } else {
            this.name = ("取消收藏 " + name);
        }
    }

    @Override
    public void run(IEnd end) {
        if (starType == 0) {
            Retro.getAppApi().postLikeIllust(Shaft.sUserModel.getAccess_token(), illustID, Params.TYPE_PUBLIC)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void next(NullResponse nullResponse) {
                            Intent intent = new Intent(Params.LIKED_ILLUST);
                            intent.putExtra(Params.ID, illustID);
                            intent.putExtra(Params.IS_LIKED, true);
                            LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                        }

                        @Override
                        public void must() {
                            end.next();
                        }
                    });
        } else {
            Retro.getAppApi().postDislikeIllust(sUserModel.getAccess_token(), illustID)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void next(NullResponse nullResponse) {
                            Intent intent = new Intent(Params.LIKED_ILLUST);
                            intent.putExtra(Params.ID, illustID);
                            intent.putExtra(Params.IS_LIKED, false);
                            LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                        }

                        @Override
                        public void must() {
                            end.next();
                        }
                    });
        }
    }
}
