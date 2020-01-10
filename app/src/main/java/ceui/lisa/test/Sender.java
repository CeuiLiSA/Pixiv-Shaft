package ceui.lisa.test;

import java.io.File;

import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;
import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

public class Sender implements SendToRemote {

    @Override
    public void send(File file) {
        Common.showLog(file.getName() + " 正在上传");
        RequestBody requestFile = RequestBody.create(MediaType.parse("image/jpeg"), file);
        MultipartBody.Part body = MultipartBody.Part.createFormData("image_data", file.getName(), requestFile);
        Retro.getRankApi().uploadImage(body)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<String>() {
                    @Override
                    public void onNext(String s) {
                    }
                });
    }
}
