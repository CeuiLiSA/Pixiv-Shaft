package ceui.lisa.http;

import android.text.TextUtils;

import java.io.IOException;

import ceui.lisa.activities.Shaft;
import ceui.lisa.models.Error500;
import ceui.lisa.models.Error500Obj;
import ceui.lisa.models.ErrorResponse;
import ceui.lisa.models.ErrorResponse2;
import ceui.lisa.utils.Common;
import ceui.pixiv.chat.base.AppErrorExtKt;
import okhttp3.Response;
import okhttp3.ResponseBody;
import retrofit2.HttpException;
import timber.log.Timber;

/**
 * pixiv 请求失败的统一提示：解析 app-api 的业务错误体（validation_errors / invalid_grant /
 * error.message 等）弹 toast，解析不出来则退到 {@link AppErrorExtKt#toUserMessage}。
 * 协程链路 catch 到异常后直接调 {@link #handleError}。
 *
 * <p>最先分流的是 Cloudflare 拦截：那类 403 的正文是一整张 374 KB 的 HTML 拦截页，
 * 拿它走下面这套 JSON 解析既解不出任何东西，还会让用户看到一句毫无信息量的通用文案。
 * 判定见 {@link CfBlockDetector}，引导弹窗见 {@link CfBlockGuide}。
 */
public final class ErrorCtrl {

    private ErrorCtrl() {
    }

    public static void handleError(Throwable e) {
        try {
            // errorBody().string() 只能读一次（Retrofit 的正文是一次性流）。这里读一次，
            // CF 分类与后面的业务错误解析共用这一份 —— 谁都不许再自己读一遍。
            final String responseString = readErrorBodyOnce(e);
            if (handleCfBlockIfAny(e, responseString)) return;
            parseAndToast(e, responseString);
        } catch (RuntimeException ex) {
            // 旧 TryCatchObserver.onError 整体兜住解析异常（5xx 返回 HTML 错误页时 Gson 会抛
            // JsonSyntaxException、errors.system 缺失会 NPE）。壳删了，容错落到这里：
            // 解析失败就退到通用映射文案，绝不能让一个提示把协程崩掉。
            Timber.w(ex, "ErrorCtrl parse failed");
            showMappedMessage(e);
        }
    }

    /**
     * Cloudflare 拦截的 403 单独走一条路：toast 换成方向明确的本地化短句（不再把拦截页
     * 或它的解析残留抛给用户），并触发那次进程内唯一的引导弹窗。
     *
     * <p><b>正文必须从 {@code errorBody()} 传进来，不能在 raw 响应上 {@code peekBody()}</b>：
     * Retrofit 的 {@code parseResponse} 会把 raw 响应的 body 换成
     * {@code NoContentResponseBody} —— 它报的 {@code contentLength()} 仍是**原始长度**
     * （长度会骗人），但真去读会抛 {@code IllegalStateException}，正文通路于是**静默**失效。
     * 详见 {@link CfBlockDetector} 的类注释。
     *
     * @return true 表示这次异常已被 CF 分支处理完，调用方不要再走业务错误解析
     */
    private static boolean handleCfBlockIfAny(Throwable e, String responseString) {
        final Response raw = CfBlockDetector.rawResponseOf(e);
        if (raw == null) return false;
        if (!CfBlockDetector.isCfBlock(raw.code(), raw.headers(), responseString)) return false;

        final boolean proxyMode = CfBlockGuide.isProxyMode(raw);
        Common.showToast(CfBlockGuide.shortMessage(proxyMode));
        CfBlockGuide.maybeGuide(raw);
        return true;
    }

    /**
     * 把 {@code errorBody()} 读成字符串。<b>整个流程只读这一次。</b>
     *
     * <p>失败一律返回 null（等价于「没有正文」）：分类退回头通路，业务解析退到通用文案。
     */
    private static String readErrorBodyOnce(Throwable e) {
        if (!(e instanceof HttpException)) return null;
        try {
            retrofit2.Response<?> response = ((HttpException) e).response();
            ResponseBody body = response != null ? response.errorBody() : null;
            return body != null ? body.string() : null;
        } catch (IOException | RuntimeException ex) {
            Timber.w(ex, "ErrorCtrl: 读取错误正文失败");
            return null;
        }
    }

    private static void parseAndToast(Throwable e, String responseString) {
        if (e instanceof HttpException) {
            try {
                HttpException httpException = (HttpException) e;
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
            } catch (RuntimeException ex) {
                // Gson 解不出（HTML 错误页 / 字段缺失）时退到通用映射文案。
                Timber.w(ex, "ErrorCtrl parse failed");
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

}
