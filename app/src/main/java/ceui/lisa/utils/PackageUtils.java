package ceui.lisa.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.List;

@SuppressLint("QueryPermissionsNeeded")
public class PackageUtils {

    private PackageUtils(){}

    // 新浪微博是否安装
    public static boolean isSinaWeiboInstalled(Context context) {
        return isPackageInstalled(context, "com.sina.weibo");
    }

    // 包名对应的App是否安装
    public static boolean isPackageInstalled(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        if (packageManager == null)
            return false;
        List<PackageInfo> packageInfoList = packageManager.getInstalledPackages(0);
        for(PackageInfo info : packageInfoList) {
            if (info.packageName.equals(packageName))
                return true;
        }
        return false;
    }
}
