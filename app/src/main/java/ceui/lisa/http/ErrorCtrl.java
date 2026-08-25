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
import ceui.pixiv.chat.base.AppErrorExtKt;
import io.reactivex.disposables.Disposable;
import retrofit2.HttpException;

public abstract class ErrorCtrl<T> extends TryCatchObserver<T> {

    @Override
    public void subscribe(Disposable d) {

    }

    @Override
    public void error(Throwable e) {
        handleError(e);
    }

    /**
     * 非 Rx 入口：把请求异常解析成 pixiv 的业务错误文案（validation_errors / invalid_grant /
     * error.message 等）弹 toast，解析不出来则退到 {@link AppErrorExtKt#toUserMessage}。
     * 协程链路 catch 到异常后直接调这里，和 legacy Observer 链路的提示保持一致。
     */
    public static void handleError(Throwable e) {
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
                                } else if (!TextUtils.isEmpty(response.getBody().getValidation_errors().getPixiv_id())) {
                                    Common.showToast(response.getBody().getValidation_errors().getPixiv_id());
                                }
                            } else {
                                showMappedMessage(e);
                            }
                        } else {
                            Error500 response = Shaft.sGson.fromJson(responseString, Error500.class);
                            if (response != null) {
                                if (!TextUtils.isEmpty(response.getMessage())) {
                                    Common.showToast(response.getMessage());
                                }
                            } else {
                                showMappedMessage(e);
                            }
                        }
                    } else if(responseString.contains("invalid_grant") || responseString.contains("invalid_request")) {
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
                                    response.getBody().getValidation_errors() != null) {
                                if (!TextUtils.isEmpty(response.getBody().getValidation_errors().getMail_address())) {
                                    Common.showToast(response.getBody().getValidation_errors().getMail_address(), true);
                                } else if (!TextUtils.isEmpty(response.getBody().getValidation_errors().getPixiv_id())) {
                                    Common.showToast(response.getBody().getValidation_errors().getPixiv_id());
                                }
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
                            showMappedMessage(e);
                        }
                    }
                } else {
                    showMappedMessage(e);
                }
            } catch (IOException ex) {
                ex.printStackTrace();
                showMappedMessage(e);
            }
        } else {
            // 非 HTTP 错误(网络中断/超时/SSL/反序列化)以前落在这里被静默吞掉,
            // 现在统一映射成 AppError 再取本地化文案提示用户。
            showMappedMessage(e);
        }
    }

    /**
     * 把任意 Throwable 交给 AppError.toUserMessage 映射成本地化、对用户友好的文案后弹 toast,
     * 取代原先直接抛 e.toString()(会露出 "retrofit2.HttpException: HTTP 404" 之类原始串)
     * 或什么都不做的处理。
     */
    private static void showMappedMessage(Throwable e) {
        Common.showToast(AppErrorExtKt.toUserMessage(e, Shaft.getContext()));
    }

    @Override
    public void complete() {

    }
}
