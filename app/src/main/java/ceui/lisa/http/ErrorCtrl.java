package ceui.lisa.http;

import android.text.TextUtils;

import java.io.IOException;

import ceui.lisa.activities.Shaft;
import ceui.lisa.core.TryCatchObserver;
import ceui.lisa.models.Error500;
import ceui.lisa.models.Error500Obj;
import ceui.lisa.models.ErrorResponse;
import ceui.lisa.models.ErrorResponse2;
import ceui.lisa.utils.Common;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import retrofit2.HttpException;

public abstract class ErrorCtrl<T> extends TryCatchObserver<T> {

    @Override
    public void subscribe(Disposable d) {

    }

    @Override
    public void error(Throwable e) {
        if (e instanceof HttpException) {
            try {
                HttpException httpException = (HttpException) e;
                //这个errorBody().string()只能获取一次，下一次就为空了
                String responseString = httpException.response().errorBody().string();
                if (!TextUtils.isEmpty(responseString) &&
                        responseString.contains("{") &&
                        responseString.contains("}") &&
                        responseString.contains(":")) {
                    if (responseString.contains("validation_errors") || httpException.code() == 500) {
                        if (responseString.contains("body\":{")) {
                            Error500Obj response = Shaft.sGson.fromJson(responseString, Error500Obj.class);
                            if (response != null && response.getBody() != null) {
                                if (!TextUtils.isEmpty(response.getBody().getValidation_errors().getMail_address())) {
                                    Common.showToast(response.getBody().getValidation_errors().getMail_address());
                                }
                            } else {
                                Common.showToast(e.toString());
                            }
                        } else {
                            Error500 response = Shaft.sGson.fromJson(responseString, Error500.class);
                            if (response != null) {
                                if (!TextUtils.isEmpty(response.getMessage())) {
                                    Common.showToast(response.getMessage());
                                }
                            } else {
                                Common.showToast(e.toString());
                            }
                        }
                    } else if(responseString.contains("invalid_grant")) {
                        ErrorResponse2 response = Shaft.sGson.fromJson(responseString, ErrorResponse2.class);
                        if (response != null) {
                            if (response.getErrors() != null && response.getErrors().getSystem() != null) {
                                if (!TextUtils.isEmpty(response.getErrors().getSystem().getMessage())) {
                                    Common.showToast(response.getErrors().getSystem().getMessage());
                                }
                            }
                        }
                    } else {
                        ErrorResponse response = Shaft.sGson.fromJson(responseString, ErrorResponse.class);
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
                                    } else if (response.getError().getUser_message_details() != null &&
                                            !TextUtils.isEmpty(response.getError().getUser_message_details().getProfile_image())) {
                                        Common.showToast(response.getError().getUser_message_details().getProfile_image(), true);
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
    public void complete() {

    }
}
