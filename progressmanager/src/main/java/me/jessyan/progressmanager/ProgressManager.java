/*
 * Copyright 2017 JessYan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.jessyan.progressmanager;

import android.app.Activity;
import android.app.Fragment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import me.jessyan.progressmanager.body.ProgressInfo;
import me.jessyan.progressmanager.body.ProgressRequestBody;
import me.jessyan.progressmanager.body.ProgressResponseBody;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http2.Header;

/**
 * ================================================
 * ProgressManager 一行代码即可监听 App 中所有网络链接的上传以及下载进度,包括 Glide(需要将下载引擎切换为 Okhttp)的图片加载进度,
 * 基于 Okhttp 的 {@link Interceptor},所以使用前请确保你使用 Okhttp 或 Retrofit 进行网络请求
 * 实现原理类似 EventBus,你可在 App 中的任何地方,将多个监听器,以 {@code url} 地址作为标识符,注册到本管理器
 * 当此 {@code url} 地址存在下载或者上传的动作时,管理器会主动调用所有使用此 {@code url} 地址注册过的监听器,达到多个模块的同步更新
 * <p>
 * Created by JessYan on 02/06/2017 18:37
 * <a href="mailto:jess.yan.effort@gmail.com">Contact me</a>
 * <a href="https://github.com/JessYanCoding">Follow me</a>
 * ================================================
 */
public final class ProgressManager {
    /**
     * 因为 {@link WeakHashMap} 将 {@code key} 作为弱键 (弱引用的键), 所以当 java 虚拟机 GC 时会将某个不在被引用的 {@code key} 回收并加入 {@link ReferenceQueue}
     * 在下一次操作 {@link WeakHashMap} 时,会比对 {@link ReferenceQueue} 中的 {@code key}
     * 将 {@link WeakHashMap} 中对应的 {@code key} 连同 {@code value} 一起移除,从而免去了手动 {@link Map#remove(Object)}
     * <p>
     * 建议你们在日常使用中,将这个 {@code key} 和对应的 {@link Activity}/{@link Fragment} 生命周期同步,也就使用全局变量持有
     * 最重要的是使用 String mUrl = new String("url");, 而不是 String mUrl = "url";
     * 为什么这样做? 因为如果直接使用 String mUrl = "url", 这个 {@code url} 字符串会被加入全局字符串常量池, 池中的字符串将不会被回收
     * 既然 {@code key} 没被回收, 那 {@link WeakHashMap} 中的值也不会被移除
     */
    private final Map<String, List<ProgressListener>> mRequestListeners = new WeakHashMap<>();
    private final Map<String, List<ProgressListener>> mResponseListeners = new WeakHashMap<>();
    private final Handler mHandler; //所有监听器在 Handler 中被执行,所以可以保证所有监听器在主线程中被执行
    private final Interceptor mInterceptor;
    private int mRefreshTime = DEFAULT_REFRESH_TIME; //进度刷新时间(单位ms),避免高频率调用

    private static volatile ProgressManager mProgressManager;

    private static final String OKHTTP_PACKAGE_NAME = "okhttp3.OkHttpClient";
    private static final boolean DEPENDENCY_OKHTTP;
    private static final int DEFAULT_REFRESH_TIME = 150;
    private static final String IDENTIFICATION_NUMBER = "?JessYan=";
    private static final String IDENTIFICATION_HEADER = "JessYan";
    private static final String LOCATION_HEADER = "Location";


    static {
        boolean hasDependency;
        try {
            Class.forName(OKHTTP_PACKAGE_NAME);
            hasDependency = true;
        } catch (ClassNotFoundException e) {
            hasDependency = false;
        }
        DEPENDENCY_OKHTTP = hasDependency;
    }


    private ProgressManager() {
        this.mHandler = new Handler(Looper.getMainLooper());
        this.mInterceptor = new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                return wrapResponseBody(chain.proceed(wrapRequestBody(chain.request())));
            }
        };
    }


    public static final ProgressManager getInstance() {
        if (mProgressManager == null) {
            if (!DEPENDENCY_OKHTTP) { //使用本管理器必须依赖 Okhttp
                throw new IllegalStateException("Must be dependency Okhttp");
            }
            synchronized (ProgressManager.class) {
                if (mProgressManager == null) {
                    mProgressManager = new ProgressManager();
                }
            }
        }
        return mProgressManager;
    }


    /**
     * 设置 {@link ProgressListener#onProgress(ProgressInfo)} 每次被调用的间隔时间
     *
     * @param refreshTime 间隔时间,单位毫秒
     */
    public void setRefreshTime(int refreshTime) {
        if (refreshTime < 0) throw new IllegalArgumentException("refreshTime must be >= 0");
        this.mRefreshTime = refreshTime;
    }

    /**
     * 将需要被监听上传进度的 {@code url} 注册到管理器,此操作请在页面初始化时进行,切勿多次注册同一个(内容相同)监听器
     *
     * @param url      {@code url} 作为标识符
     * @param listener 当此 {@code url} 地址存在上传的动作时,此监听器将被调用
     */
    public void addRequestListener(String url, ProgressListener listener) {
        checkNotNull(url, "url cannot be null");
        checkNotNull(listener, "listener cannot be null");
        List<ProgressListener> progressListeners;
        synchronized (ProgressManager.class) {
            progressListeners = mRequestListeners.get(url);
            if (progressListeners == null) {
                progressListeners = new LinkedList<>();
                mRequestListeners.put(url, progressListeners);
            }
        }
        progressListeners.add(listener);
    }

    /**
     * 将需要被监听下载进度的 {@code url} 注册到管理器,此操作请在页面初始化时进行,切勿多次注册同一个(内容相同)监听器
     *
     * @param url      {@code url} 作为标识符
     * @param listener 当此 {@code url} 地址存在下载的动作时,此监听器将被调用
     */
    public void addResponseListener(String url, ProgressListener listener) {
        checkNotNull(url, "url cannot be null");
        checkNotNull(listener, "listener cannot be null");
        List<ProgressListener> progressListeners;
        synchronized (ProgressManager.class) {
            progressListeners = mResponseListeners.get(url);
            if (progressListeners == null) {
                progressListeners = new LinkedList<>();
                mResponseListeners.put(url, progressListeners);
            } else {
                Log.d("dsaasdasw2", "url is exist");
            }
        }
        progressListeners.add(listener);
    }


    /**
     * 当在 {@link ProgressRequestBody} 和 {@link ProgressResponseBody} 内部处理二进制流时发生错误
     * 会主动调用 {@link ProgressListener#onError(long, Exception)},但是有些错误并不是在它们内部发生的
     * 但同样会引起网络请求的失败,所以向外面提供{@link ProgressManager#notifyOnErorr},当外部发生错误时
     * 手动调用此方法,以通知所有的监听器
     *
     * @param url {@code url} 作为标识符
     * @param e   错误
     */
    public void notifyOnErorr(String url, Exception e) {
        checkNotNull(url, "url cannot be null");
        forEachListenersOnError(mRequestListeners, url, e);
        forEachListenersOnError(mResponseListeners, url, e);
    }

    /**
     * 将 {@link okhttp3.OkHttpClient.Builder} 传入,配置一些本管理器需要的参数
     *
     * @param builder
     * @return
     */
    public OkHttpClient.Builder with(OkHttpClient.Builder builder) {
        checkNotNull(builder, "builder cannot be null");
        return builder
                .addNetworkInterceptor(mInterceptor);
    }

    /**
     * 将 {@link Request} 传入,配置一些本框架需要的参数,常用于自定义 {@link Interceptor}
     * 如已使用 {@link ProgressManager#with(OkHttpClient.Builder)},就不会用到此方法
     *
     * @param request 原始的 {@link Request}
     * @return
     */
    public Request wrapRequestBody(Request request) {
        if (request == null)
            return request;

        String key = request.url().toString();
        request = pruneIdentification(key, request);

        if (request.body() == null)
            return request;
        if (mRequestListeners.containsKey(key)) {
            List<ProgressListener> listeners = mRequestListeners.get(key);
            return request.newBuilder()
                    .method(request.method(), new ProgressRequestBody(mHandler, request.body(), listeners, mRefreshTime))
                    .build();
        }
        return request;
    }

    /**
     * 如果 {@code url} 中含有 {@link #addDiffResponseListenerOnSameUrl(String, String, ProgressListener)}
     * 或 {@link #addDiffRequestListenerOnSameUrl(String, String, ProgressListener)} 加入的标识符,则将加入标识符
     * 从 {code url} 中删除掉,继续使用 {@code originUrl} 进行请求
     *
     * @param url     {@code url} 地址
     * @param request 原始 {@link Request}
     * @return 返回可能被修改过的 {@link Request}
     */
    private Request pruneIdentification(String url, Request request) {
        boolean needPrune = url.contains(IDENTIFICATION_NUMBER);
        if (!needPrune)
            return request;
        return request.newBuilder()
                .url(url.substring(0, url.indexOf(IDENTIFICATION_NUMBER))) //删除掉标识符
                .header(IDENTIFICATION_HEADER, url) //将有标识符的 url 加入 header中, 便于wrapResponseBody(Response) 做处理
                .build();
    }

    /**
     * 将 {@link Response} 传入,配置一些本框架需要的参数,常用于自定义 {@link Interceptor}
     * 如已使用 {@link ProgressManager#with(OkHttpClient.Builder)},就不会用到此方法
     *
     * @param response 原始的 {@link Response}
     * @return
     */
    public Response wrapResponseBody(Response response) {
        if (response == null)
            return response;

        String key = response.request().url().toString();
        Log.d("ProgressInfo", key);
        if (!TextUtils.isEmpty(response.request().header(IDENTIFICATION_HEADER))) { //从 header 中拿出有标识符的 url
            key = response.request().header(IDENTIFICATION_HEADER);
        }

        if (response.isRedirect()) {
            resolveRedirect(mRequestListeners, response, key);
            String location = resolveRedirect(mResponseListeners, response, key);
            response = modifyLocation(response, location);
            return response;
        }

        if (response.body() == null)
            return response;

        if (mResponseListeners.containsKey(key)) {
            List<ProgressListener> listeners = mResponseListeners.get(key);
            return response.newBuilder()
                    .body(new ProgressResponseBody(mHandler, response.body(), listeners, mRefreshTime))
                    .build();
        }
        return response;
    }

    /**
     * 查看 location 是否被加入了标识符, 如果是, 则放入 {@link Header} 中重新定义 {@link Response}
     *
     * @param response 原始的 {@link Response}
     * @param location {@code location} 重定向地址
     * @return 返回可能被修改过的 {@link Response}
     */
    private Response modifyLocation(Response response, String location) {
        if (!TextUtils.isEmpty(location) && location.contains(IDENTIFICATION_NUMBER)) { //将被加入标识符的新的 location 放入 header 中
            response = response.newBuilder()
                    .header(LOCATION_HEADER, location)
                    .build();
        }
        return response;
    }

    /**
     * 当出现需要使用同一个 {@code url} 根据 Post 请求参数的不同而下载不同资源的情况
     * 请使用 {@link #addDiffResponseListenerOnSameUrl(String, ProgressListener)} 代替 {@link #addResponseListener}
     * {@link #addDiffResponseListenerOnSameUrl(String, ProgressListener)} 会返回一个加入了时间戳的新的 {@code url}
     * 请使用这个新的 {@code url} 去代替 {@code originUrl} 进行下载的请求即可 (当实际请求时依然使用 {@code originUrl} 进行网络请求)
     * <p>
     * {@link #addDiffResponseListenerOnSameUrl(String, ProgressListener)} 与 {@link #addDiffResponseListenerOnSameUrl(String, String, ProgressListener)}
     * 的区别在于:
     * {@link #addDiffResponseListenerOnSameUrl(String, String, ProgressListener)} 可以使用不同的 {@code key} 来自定义新的 {@code url}
     * {@link #addDiffResponseListenerOnSameUrl(String, ProgressListener)} 是直接使用时间戳来生成新的 {@code url}
     * <p>
     *
     * @param originUrl {@code originUrl} 作为基础并结合时间戳用于生成新的 {@code url} 作为标识符
     * @param listener  当加入了时间戳的新的 {@code url} 地址存在下载的动作时,此监听器将被调用
     * @return 加入了时间戳的新的 {@code url}
     */
    public String addDiffResponseListenerOnSameUrl(String originUrl, ProgressListener listener) {
        return addDiffResponseListenerOnSameUrl(originUrl, String.valueOf(SystemClock.elapsedRealtime() + listener.hashCode()), listener);
    }

    /**
     * 当出现需要使用同一个 {@code url} 根据 Post 请求参数的不同而下载不同资源的情况
     * 请使用 {@link #addDiffResponseListenerOnSameUrl(String, String, ProgressListener)} 代替 {@link #addResponseListener}
     * 请使用 {@link #addDiffResponseListenerOnSameUrl(String, String, ProgressListener)} 会返回一个 {@code originUrl} 结合 {@code key} 生成的新的 {@code url}
     * 请使用这个新的 {@code url} 去代替 {@code originUrl} 进行下载的请求即可 (当实际请求时依然使用 {@code originUrl} 进行网络请求)
     * <p>
     * {@link #addDiffResponseListenerOnSameUrl(String, ProgressListener)} 与 {@link #addDiffResponseListenerOnSameUrl(String, String, ProgressListener)}
     * 的区别在于:
     * {@link #addDiffResponseListenerOnSameUrl(String, String, ProgressListener)} 可以使用不同的 {@code key} 来自定义新的 {@code url}
     * {@link #addDiffResponseListenerOnSameUrl(String, ProgressListener)} 是直接使用时间戳来生成新的 {@code url}
     * <p>
     * Example usage:
     * <pre>
     * String newUrl = ProgressManager.getInstance().addDiffResponseListenerOnSameUrl(DOWNLOAD_URL, "id", getDownloadListener());
     * new Thread(new Runnable() {
     *
     *   public void run() {
     *     try {
     *        Request request = new Request.Builder()
     *        .url(newUrl) // 请一定使用 addDiffResponseListenerOnSameUrl 返回的 newUrl 做请求
     *        .build();
     *
     *        Response response = mOkHttpClient.newCall(request).execute();
     *      } catch (IOException e) {
     *       e.printStackTrace();
     *       //当外部发生错误时,使用此方法可以通知所有监听器的 onError 方法,这里也要使用 newUrl
     *       ProgressManager.getInstance().notifyOnErorr(newUrl, e);
     *     }
     *   }
     * }).start();
     * </pre>
     *
     * @param originUrl {@code originUrl} 作为基础并结合 {@code key} 用于生成新的 {@code url} 作为标识符
     * @param key       {@code originUrl} 作为基础并结合 {@code key} 用于生成新的 {@code url} 作为标识符
     * @param listener  当 {@code originUrl} 结合 {@code key} 生成的新的 {@code url} 地址存在下载的动作时,此监听器将被调用
     * @return {@code originUrl} 结合 {@code key} 生成的新的 {@code url}
     */
    public String addDiffResponseListenerOnSameUrl(String originUrl, String key, ProgressListener listener) {
        String newUrl = originUrl + IDENTIFICATION_NUMBER + key;
        addResponseListener(newUrl, listener);
        return newUrl;
    }

    /**
     * 当出现需要使用同一个 {@code url} 根据 Post 请求参数的不同而上传不同资源的情况
     * 请使用 {@link #addDiffRequestListenerOnSameUrl(String, ProgressListener)} 代替 {@link #addRequestListener}
     * {@link #addDiffRequestListenerOnSameUrl(String, ProgressListener)} 会返回一个加入了时间戳的新的 {@code url}
     * 请使用这个新的 {@code url} 去代替 {@code originUrl} 进行上传的请求即可 (当实际请求时依然使用 {@code originUrl} 进行网络请求)
     * <p>
     * {@link #addDiffRequestListenerOnSameUrl(String, ProgressListener)} 与 {@link #addDiffRequestListenerOnSameUrl(String, String, ProgressListener)}
     * 的区别在于:
     * {@link #addDiffRequestListenerOnSameUrl(String, String, ProgressListener)} 可以使用不同的 {@code key} 来自定义新的 {@code url}
     * {@link #addDiffRequestListenerOnSameUrl(String, ProgressListener)} 是直接使用时间戳来生成新的 {@code url}
     * <p>
     *
     * @param originUrl {@code originUrl} 作为基础并结合时间戳用于生成新的 {@code url} 作为标识符
     * @param listener  当加入了时间戳的新的 {@code url} 地址存在上传的动作时,此监听器将被调用
     * @return 加入了时间戳的新的 {@code url}
     */
    public String addDiffRequestListenerOnSameUrl(String originUrl, ProgressListener listener) {
        return addDiffRequestListenerOnSameUrl(originUrl, String.valueOf(SystemClock.elapsedRealtime() + listener.hashCode()), listener);
    }


    /**
     * 当出现需要使用同一个 {@code url} 根据 Post 请求参数的不同而上传不同资源的情况
     * 请使用 {@link #addDiffRequestListenerOnSameUrl(String, ProgressListener)} 代替 {@link #addRequestListener}
     * {@link #addDiffRequestListenerOnSameUrl(String, ProgressListener)} 会返回一个 {@code originUrl} 结合 {@code key} 生成的新的 {@code url}
     * 请使用这个新的 {@code url} 去代替 {@code originUrl} 进行上传的请求即可 (当实际请求时依然使用 {@code originUrl} 进行网络请求)
     * <p>
     * {@link #addDiffRequestListenerOnSameUrl(String, ProgressListener)} 与 {@link #addDiffRequestListenerOnSameUrl(String, String, ProgressListener)}
     * 的区别在于:
     * {@link #addDiffRequestListenerOnSameUrl(String, String, ProgressListener)} 可以使用不同的 {@code key} 来自定义新的 {@code url}
     * {@link #addDiffRequestListenerOnSameUrl(String, ProgressListener)} 是直接使用时间戳来生成新的 {@code url}
     * <p>
     * Example usage:
     * <pre>
     * String newUrl = ProgressManager.getInstance().addDiffRequestListenerOnSameUrl(UPLOAD_URL, "id", getUploadListener());
     * new Thread(new Runnable() {
     *
     *    public void run() {
     *    try {
     *       File file = new File(getCacheDir(), "cache");
     *
     *       Request request = new Request.Builder()
     *       .url(newUrl) // 请一定使用 addDiffRequestListenerOnSameUrl 返回的 newUrl 做请求
     *       .post(RequestBody.create(MediaType.parse("multipart/form-data"), file))
     *       .build();
     *
     *       Response response = mOkHttpClient.newCall(request).execute();
     *     } catch (IOException e) {
     *      e.printStackTrace();
     *      //当外部发生错误时,使用此方法可以通知所有监听器的 onError 方法,这里也要使用 newUrl
     *      ProgressManager.getInstance().notifyOnErorr(newUrl, e);
     *    }
     *  }
     * }).start();
     * </pre>
     *
     * @param originUrl {@code originUrl} 作为基础并结合 {@code key} 用于生成新的 {@code url} 作为标识符
     * @param key       {@code originUrl} 作为基础并结合 {@code key} 用于生成新的 {@code url} 作为标识符
     * @param listener  当 {@code originUrl} 结合 {@code key} 生成的新的 {@code url} 地址存在上传的动作时,此监听器将被调用
     * @return {@code originUrl} 结合 {@code key} 生成的新的 {@code url}
     */
    public String addDiffRequestListenerOnSameUrl(String originUrl, String key, ProgressListener listener) {
        String newUrl = originUrl + IDENTIFICATION_NUMBER + key;
        addRequestListener(newUrl, listener);
        return newUrl;
    }

    /**
     * 解决请求地址重定向后的兼容问题
     *
     * @param map      {@link #mRequestListeners} 或者 {@link #mResponseListeners}
     * @param response 原始的 {@link Response}
     * @param url      {@code url} 地址
     */
    private String resolveRedirect(Map<String, List<ProgressListener>> map, Response response, String url) {
        String location = null;
        List<ProgressListener> progressListeners = map.get(url); //查看此重定向 url ,是否已经注册过监听器
        if (progressListeners != null && progressListeners.size() > 0) {
            location = response.header(LOCATION_HEADER);// 重定向地址
            if (!TextUtils.isEmpty(location)) {
                if (url.contains(IDENTIFICATION_NUMBER) && !location.contains(IDENTIFICATION_NUMBER)) { //如果 url 有标识符,那也将标识符加入用于重定向的 location
                    location += url.substring(url.indexOf(IDENTIFICATION_NUMBER), url.length());
                }
                if (!map.containsKey(location)) {
                    map.put(location, progressListeners); //将需要重定向地址的监听器,提供给重定向地址,保证重定向后也可以监听进度
                } else {
                    List<ProgressListener> locationListener = map.get(location);
                    for (ProgressListener listener : progressListeners) {
                        if (!locationListener.contains(listener)) {
                            locationListener.add(listener);
                        }
                    }
                }
            }
        }
        return location;
    }


    private void forEachListenersOnError(Map<String, List<ProgressListener>> map, String url, Exception e) {
        if (map.containsKey(url)) {
            List<ProgressListener> progressListeners = map.get(url);
            ProgressListener[] array = progressListeners.toArray(new ProgressListener[progressListeners.size()]);
            for (int i = 0; i < array.length; i++) {
                array[i].onError(-1, e);
            }
        }
    }

    static <T> T checkNotNull(T object, String message) {
        if (object == null) {
            throw new NullPointerException(message);
        }
        return object;
    }

    /**
     * 将需要被监听下载进度的 {@code url} 解除注册，解决内存泄漏问题，当确认不会重新加载时可以调用
     * @param url   {@code url} 作为标识符
     * @param listener  需要移除的监听器
     */
    public void removeResponseListener(String url, ProgressListener listener){
        checkNotNull(url, "url cannot be null");
        checkNotNull(listener, "listener cannot be null");
        List<ProgressListener> progressListeners;
        synchronized (ProgressManager.class) {
            progressListeners = mResponseListeners.get(url);
            if (progressListeners != null) {
                progressListeners.remove(listener);
            }
        }
    }
}
