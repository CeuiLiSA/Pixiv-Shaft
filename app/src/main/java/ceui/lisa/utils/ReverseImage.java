package ceui.lisa.utils;

import java.io.File;

import ceui.lisa.http.IqdbApi;
import ceui.lisa.http.Retro;
import ceui.lisa.http.SauceNaoApi;
import ceui.lisa.http.TinEyeApi;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

//TinEye会报Internal Server Error,原因暂时不明
//没找到给出的Api,都使用了网页版
//暂时想法是使用webview.load(response),可能有更好的解决方案
public class ReverseImage {
    private static final String IQDB_BASE_URL = "https://iqdb.org/";
    private static final String SAUCENAO_BASE_URL = "https://saucenao.com/";
    private static final String TINEYE_BASE_URL = "https://www.tineye.com/";

    public static void reverse(File file, ReverseProvider reverseProvider, Callback callback) {

        RequestBody requestBody = RequestBody.create(MediaType.parse("multipart/form-data"), file);
        MultipartBody.Part formData = MultipartBody.Part.createFormData("file", file.getName(), requestBody);

        Object o = Retro.create(reverseProvider.base_url, reverseProvider.apiClass);

        //    enum不能使用泛型,刚好Retrofit的Api Interface 不能继承,搞不了花里胡哨了
        Observable<ResponseBody> observable;
        System.out.println(reverseProvider.name());
        switch (reverseProvider.name()) {
            case "Iqdb":
                observable = ((IqdbApi) o).query(formData);
                break;
            case "SauceNao":
                observable = ((SauceNaoApi) o).query(formData);
                break;
            case "TinEye":
                observable = ((TinEyeApi) o).query(formData);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + reverseProvider.name());
        }

        observable.subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<ResponseBody>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(ResponseBody responseBody) {
                        callback.onNext(responseBody);
                    }

                    @Override
                    public void onError(Throwable e) {
                        callback.onError(e);
                    }

                    @Override
                    public void onComplete() {

                    }
                });

    }


    public interface Callback {
        void onNext(ResponseBody responseBody);
        void onError(Throwable e);
    }


    public enum ReverseProvider {
        Iqdb(IQDB_BASE_URL, IqdbApi.class), SauceNao(SAUCENAO_BASE_URL, SauceNaoApi.class), TinEye(TINEYE_BASE_URL, TinEyeApi.class);

        private Class<?> apiClass;
        private final String base_url;

        ReverseProvider(String baseUrl, Class<?> apiClass) {
            this.base_url = baseUrl;
            this.apiClass = apiClass;
        }
    }
}
