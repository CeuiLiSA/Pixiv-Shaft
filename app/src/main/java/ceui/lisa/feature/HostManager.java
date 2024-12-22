package ceui.lisa.feature;


import android.net.Uri;
import android.text.TextUtils;

import ceui.lisa.activities.Shaft;
import ceui.lisa.http.CloudFlareDNSResponse;
import ceui.lisa.http.CloudFlareDNSService;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import retrofit2.Call;
import retrofit2.Callback;

public class HostManager {

    //For example:https://i.pximg.net/img-original/img/2024/02/28/05/42/23/116457142_p0.jpg
    public static final String HOST_OLD = "i.pximg.net";
//    public static final String HOST_OLD = "app-api.pixiv.net";
    public static final String HOST_NEW = "i.pixiv.re";
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
            host = "210.140.139.132";
            updateHost();
        } else {
            host = randomHost();
            updateHost();
        }
    }

    /**
     *  Get IP address refers to "imgaz.pixiv.net"
     * @return Random IP address refers to "imgaz.pixiv.net" in String type
     */

    private String randomHost() {
        String[] already = new String[]{
                "210.140.139.129",
                "210.140.139.130",
                "210.140.139.131",
                "210.140.139.132",
                "210.140.139.133",
                "210.140.139.134",
                "210.140.139.135",
                "210.140.139.136",
                "210.140.139.137",
                "210.140.139.138"
        };
        return already[Common.flatRandom(already.length)];
    }

    private void updateHost() {
        CloudFlareDNSService.Companion.invoke(CloudFlareDNSService.Companion.getCLOUDFLARE_DOH_POINT()).query(HOST_OLD, "A")
                .enqueue(new Callback<CloudFlareDNSResponse>() {
                    @Override
                    public void onResponse(Call<CloudFlareDNSResponse> call, retrofit2.Response<CloudFlareDNSResponse> response) {
                        boolean success = updateHostByResponse(response);
                        if(!success){
                            CloudFlareDNSService.Companion.invoke(CloudFlareDNSService.Companion.getDNSSB_DOH_POINT()).query(HOST_OLD, "A")
                                    .enqueue(new Callback<CloudFlareDNSResponse>() {
                                        @Override
                                        public void onResponse(Call<CloudFlareDNSResponse> call2, retrofit2.Response<CloudFlareDNSResponse> response2) {
                                            updateHostByResponse(response2);
                                        }

                                        @Override
                                        public void onFailure(Call<CloudFlareDNSResponse> call2, Throwable t2) {
                                            Common.showLog("CloudFlareDNSService onFailure " + t2.toString());
                                        }
                                    });
                        }
                    }

                    @Override
                    public void onFailure(Call<CloudFlareDNSResponse> call, Throwable t) {
                        Common.showLog("CloudFlareDNSService onFailure " + t.toString());
                    }
                });
    }

    private boolean updateHostByResponse(retrofit2.Response<CloudFlareDNSResponse> response) {
        try {
            CloudFlareDNSResponse cloudFlareDNSResponse = response.body();
            if (cloudFlareDNSResponse != null) {
                if (!Common.isEmpty(cloudFlareDNSResponse.getAnswer())) {
                    int position = Common.flatRandom(cloudFlareDNSResponse.getAnswer().size());
                    host = cloudFlareDNSResponse.getAnswer().get(position).getData();
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public String replaceUrl(String before) {
        boolean showDetail = Dev.show_url_detail;
        if(showDetail) Common.showLog("HostManager before " + before);
        if (Shaft.sSettings.isUsePixivCat() && !TextUtils.isEmpty(before) && before.contains(HOST_OLD)) {
            String finalUrl = before.replace(HOST_OLD, HOST_NEW);
            if(showDetail) Common.showLog("HostManager after0 " + finalUrl);
            return finalUrl;
        } else if (Shaft.sSettings.isAutoFuckChina() && before.contains(HOST_OLD)) { //此处修改为只替换i.pximg.net地址，s.pximg.net不替换
            String result = resizeUrl(before);
            if(showDetail) Common.showLog("HostManager after1 " + result);
            return result;
        } else {
            if(showDetail) Common.showLog("HostManager after1 " + before);
            return before;
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

    public PKCEItem getPkce() {
        if (pkceItem == null) {
            try {
                final String verify = PkceUtil.generateCodeVerifier();
                final String challenge = PkceUtil.generateCodeChallenge(verify);
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
