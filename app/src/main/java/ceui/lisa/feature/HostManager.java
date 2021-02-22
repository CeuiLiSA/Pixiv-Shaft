package ceui.lisa.feature;


import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import ceui.lisa.activities.Shaft;
import ceui.lisa.http.CloudFlareDNSResponse;
import ceui.lisa.http.CloudFlareDNSService;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import retrofit2.Call;
import retrofit2.Callback;

public class HostManager {

    public static final String HOST_OLD = "i.pximg.net";
//    public static final String HOST_OLD = "app-api.pixiv.net";
    public static final String HOST_NEW = "i.pixiv.cat";
    private static final String HTTP_HEAD = "http://";
    private PKCEItem pkceItem;

    private String host;

    private HostManager() {
    }

    public static HostManager get() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final HostManager INSTANCE = new HostManager();
    }

    public void init() {
        if (Dev.isDev) {
            host = "210.140.92.139";
        } else {
            host = randomHost();
            updateHost();
        }
    }

    private String randomHost() {
        String[] already = new String[]{
                "210.140.92.145",
                "210.140.92.141",
                "210.140.92.138",
                "210.140.92.143",
                "210.140.92.146",
                "210.140.92.142",
                "210.140.92.147",
                "210.140.92.139",
                "210.140.92.140",
                "210.140.92.144"
        };
        return already[Common.flatRandom(already.length)];
    }

    private void updateHost() {
        CloudFlareDNSService.Companion.invoke().query(HOST_OLD, "application/dns-json", "A")
                .enqueue(new Callback<CloudFlareDNSResponse>() {
                    @Override
                    public void onResponse(Call<CloudFlareDNSResponse> call, retrofit2.Response<CloudFlareDNSResponse> response) {
                        try {
                            CloudFlareDNSResponse cloudFlareDNSResponse = response.body();
                            if (cloudFlareDNSResponse != null) {
                                if (!Common.isEmpty(cloudFlareDNSResponse.getAnswer())) {
                                    int position = Common.flatRandom(cloudFlareDNSResponse.getAnswer().size());
                                    host = cloudFlareDNSResponse.getAnswer().get(position).getData();
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onFailure(Call<CloudFlareDNSResponse> call, Throwable t) {
                        Common.showLog("CloudFlareDNSService onFailure " + t.toString());
                    }
                });
    }

    public String replaceUrl(String before) {
        if (Dev.show_url_detail) {
            Common.showLog("HostManager before " + before);
            if (Shaft.sSettings.isUsePixivCat() && !TextUtils.isEmpty(before) && before.contains(HOST_OLD)) {
                String finalUrl = before.replace(HOST_OLD, HOST_NEW);
                Common.showLog("HostManager after0 " + finalUrl);
                return finalUrl;
            } else {
                String result = resizeUrl(before);
                Common.showLog("HostManager after1 " + result);
                return result;
            }
        } else {
            if (Shaft.sSettings.isUsePixivCat() && !TextUtils.isEmpty(before) && before.contains(HOST_OLD)) {
                return before.replace(HOST_OLD, HOST_NEW);
            } else {
                return resizeUrl(before);
            }
        }
    }

    private String resizeUrl(String url) {
        if (TextUtils.isEmpty(host)) {
            host = randomHost();
        }
        try {
            Uri uri = Uri.parse(url);
            return HTTP_HEAD + host + uri.getPath();
        } catch (Exception e) {
            e.printStackTrace();
            return HTTP_HEAD + host + url.substring(19);
        }
    }

    public PKCEItem getPkceItem() {
        if (pkceItem == null) {
            try {
                final String verify = PkceUtil.generateCodeVerifier();
                final String challenge = PkceUtil.generateCodeChallange(verify);
                pkceItem = new PKCEItem(verify, challenge);
            } catch (Exception e) {
                e.printStackTrace();
                pkceItem = new PKCEItem(
                        "-29P7XEuFCNdG-1aiYZ9tTeYrABWRHxS9ZVNr6yrdcI",
                        "usItTkssolVsmIbxrf0o-O_FsdvZFANVPCf9jP4jP_0");
            }
        }
        return pkceItem;
    }
}
