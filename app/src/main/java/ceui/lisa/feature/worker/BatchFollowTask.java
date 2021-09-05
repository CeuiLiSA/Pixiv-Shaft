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

public class BatchFollowTask extends AbstractTask {

    private final int userID;
    private final int starType; // 0关注，1取消关注

    public BatchFollowTask(String name, int userID, int starType) {
        this.starType = starType;
        this.userID = userID;
        if (starType == 0) {
            this.name = ("添加关注 " + name);
        } else {
            this.name = ("取消关注 " + name);
        }
    }

    @Override
    public void run(IEnd end) {
        if (starType == 0) {
            Retro.getAppApi().postFollow(
                    sUserModel.getAccess_token(), userID, Params.TYPE_PUBLIC)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void next(NullResponse nullResponse) {
                            Intent intent = new Intent(Params.LIKED_USER);
                            intent.putExtra(Params.ID, userID);
                            intent.putExtra(Params.IS_LIKED, true);
                            LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                        }

                        @Override
                        public void must() {
                            end.next();
                        }
                    });
        } else {
            Retro.getAppApi().postUnFollow(
                    sUserModel.getAccess_token(), userID)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void next(NullResponse nullResponse) {
                            Intent intent = new Intent(Params.LIKED_USER);
                            intent.putExtra(Params.ID, userID);
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
