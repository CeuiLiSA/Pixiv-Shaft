package ceui.lisa.http;

import com.google.gson.Gson;

import java.io.IOException;

import ceui.lisa.model.ErrorResponse;
import ceui.lisa.utils.Common;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import retrofit2.HttpException;

public abstract class ErrorCtrl<T> implements Observer<T> {

    @Override
    public void onSubscribe(Disposable d) {

    }

    @Override
    public void onError(Throwable e) {
        e.printStackTrace();
        if(e instanceof HttpException) {
            try {
                HttpException httpException = (HttpException) e;
                String responseString = httpException.response().errorBody().string();
                Gson gson = new Gson();  //这个errorBody().string()只能获取一次，下一次就为空了
                ErrorResponse response = gson.fromJson(responseString, ErrorResponse.class);
                if(response != null){
                    if(response.getErrors() != null) {
                        Common.showToast(response.getErrors().getSystem().getMessage());
                    }
                    if(response.getError() != null){
                        Common.showToast(response.getError().getMessage());
                    }
                }else {
                    Common.showToast(e.toString());
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    @Override
    public void onComplete() {

    }
}
