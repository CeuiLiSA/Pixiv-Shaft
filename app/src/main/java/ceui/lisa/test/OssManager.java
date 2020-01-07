package ceui.lisa.test;

import com.alibaba.sdk.android.oss.ClientConfiguration;
import com.alibaba.sdk.android.oss.OSS;
import com.alibaba.sdk.android.oss.OSSClient;
import com.alibaba.sdk.android.oss.common.auth.OSSCredentialProvider;
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider;

import ceui.lisa.activities.Shaft;

public class OssManager {

    private OSS mOSS;

    private OssManager(){
        ClientConfiguration conf = new ClientConfiguration();
        conf.setConnectionTimeout(15 * 1000); // 连接超时，默认15秒
        conf.setSocketTimeout(15 * 1000); // socket超时，默认15秒
        conf.setMaxConcurrentRequest(5); // 最大并发请求书，默认5个
        conf.setMaxErrorRetry(2); // 失败后最大重试次数，

        OSSCredentialProvider credentialProvider = new OSSStsTokenCredentialProvider(
                "LTAI4FjJAKPpwFVG7ekam18B",
                "tT4QfvMEUfCpLRVGyUgvTrO6NVegdG",
                "");
        mOSS = new OSSClient(Shaft.getContext(), "oss-cn-shanghai.aliyuncs.com", credentialProvider, conf);
    }

    public static OSS get(){
        return Holder.sOssManager.mOSS;
    }

    private static class Holder{
        private static OssManager sOssManager = new OssManager();
    }
}
