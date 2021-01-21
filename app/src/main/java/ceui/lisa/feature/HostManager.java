package ceui.lisa.feature;


import android.text.TextUtils;

import com.tencent.mmkv.MMKV;

import ceui.lisa.activities.Shaft;
import ceui.lisa.core.UrlFactory;
import ceui.lisa.http.CloudFlareDNSResponse;
import ceui.lisa.http.CloudFlareDNSService;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import retrofit2.Call;
import retrofit2.Callback;

public class HostManager {

    private String host;

    private HostManager() {
    }

    public static HostManager get() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final HostManager INSTANCE = new HostManager();
    }

    public String getHost() {
        return host;
    }

    public void init() {
        randomHost();
        updateHost();
    }

    private void randomHost() {
        String[] already = new String[]{
                "210.140.92.145",
                "210.140.92.144",
                "210.140.92.139",
                "210.140.92.138",
                "210.140.92.146",
                "210.140.92.147",
                "210.140.92.142"
        };
        MMKV mmkv = Shaft.getMMKV();
        host = mmkv.decodeString(Params.CLOUD_DNS);
        if (TextUtils.isEmpty(host)) {
            int position = Common.flatRandom(already.length);
            host = already[position];
        }
    }

    private void updateHost() {
        CloudFlareDNSService.Companion.invoke().query(UrlFactory.HOST_OLD, "application/dns-json", "A")
                .enqueue(new Callback<CloudFlareDNSResponse>() {
                    @Override
                    public void onResponse(Call<CloudFlareDNSResponse> call, retrofit2.Response<CloudFlareDNSResponse> response) {
                        try {
                            CloudFlareDNSResponse cloudFlareDNSResponse = response.body();
                            if (cloudFlareDNSResponse != null) {
                                if (!Common.isEmpty(cloudFlareDNSResponse.getAnswer())) {
                                    int position = Common.flatRandom(cloudFlareDNSResponse.getAnswer().size());
                                    host = cloudFlareDNSResponse.getAnswer().get(position).getData();
                                    Shaft.getMMKV().encode(Params.CLOUD_DNS, host);
                                } else {
                                    randomHost();
                                }
                            } else {
                                randomHost();
                            }
                        } catch (Exception e) {
                            randomHost();
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onFailure(Call<CloudFlareDNSResponse> call, Throwable t) {
                        Common.showLog("CloudFlareDNSService onFailure " + t.toString());
                        randomHost();
                    }
                });
    }
}
