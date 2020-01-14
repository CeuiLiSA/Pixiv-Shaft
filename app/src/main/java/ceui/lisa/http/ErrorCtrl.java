package ceui.lisa.http;

import android.text.TextUtils;

import com.google.gson.Gson;

import java.io.IOException;

import ceui.lisa.models.Error500;
import ceui.lisa.models.Error500Obj;
import ceui.lisa.models.ErrorResponse;
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
        if (e instanceof HttpException) {
            try {
                HttpException httpException = (HttpException) e;
                //这个errorBody().string()只能获取一次，下一次就为空了
                String responseString = httpException.response().errorBody().string();
                if (!TextUtils.isEmpty(responseString) &&
                        responseString.contains("{") &&
                        responseString.contains("}") &&
                        responseString.contains(":")) {
                    Gson gson = new Gson();
                    if(responseString.contains("validation_errors") || httpException.code() == 500){
                        if(responseString.contains("body\":{")){
                            Error500Obj response = gson.fromJson(responseString, Error500Obj.class);
                            if (response != null && response.getBody() != null) {
                                if (!TextUtils.isEmpty(response.getBody().getValidation_errors().getMail_address())) {
                                    Common.showToast(response.getBody().getValidation_errors().getMail_address());
                                }
                            } else {
                                Common.showToast(e.toString());
                            }
                        } else {
                            Error500 response = gson.fromJson(responseString, Error500.class);
                            if (response != null) {
                                if (!TextUtils.isEmpty(response.getMessage())) {
                                    Common.showToast(response.getMessage());
                                }
                            } else {
                                Common.showToast(e.toString());
                            }
                        }
                    } else {
                        ErrorResponse response = gson.fromJson(responseString, ErrorResponse.class);
                        if (response != null) {
                            if (response.getBody() != null &&
                                    response.getBody().getValidation_errors() != null &&
                                    !TextUtils.isEmpty(response.getBody().getValidation_errors().getMail_address())) {
                                Common.showToast(response.getBody().getValidation_errors().getMail_address(), true);
                            } else {
                                if (response.getErrors() != null) {
                                    Common.showToast(response.getErrors().getSystem().getMessage(), true);
                                }
                                if (response.getError() != null) {
                                    if (!TextUtils.isEmpty(response.getError().getMessage())) {
                                        Common.showToast(response.getError().getMessage(), true);
                                    } else if (!TextUtils.isEmpty(response.getError().getReason())) {
                                        Common.showToast(response.getError().getReason(), true);
                                    } else if (!TextUtils.isEmpty(response.getError().getUser_message())) {
                                        Common.showToast(response.getError().getUser_message(), true);
                                    }
                                }
                            }
                        } else {
                            Common.showToast(e.toString());
                        }
                    }
                } else {
                    Common.showToast(e.toString());
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    @Override
    public void onComplete() {
        Common.showLog(getClass().getSimpleName() + " onComplete() ");
    }
}
