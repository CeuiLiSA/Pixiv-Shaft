package ceui.lisa.helper;

import android.os.Environment;

import com.blankj.utilcode.util.PathUtils;

import java.io.File;

import ceui.lisa.activities.Shaft;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.models.IllustsBean;

public class FileStorageHelper {

    public static String getIllustFileFullNameUnderQ(DownloadItem downloadItem){
        return getIllustAbsolutePath(downloadItem.getIllust()) + File.separator + downloadItem.getName();
    }

    public static String getIllustFileRelativeNameQ(DownloadItem downloadItem) {
        return getIllustRelativePathQ(downloadItem.getIllust()) + File.separator + downloadItem.getName();
    }

    public static String getIllustFileSAFFullName(String id, IllustsBean illustsBean, String fileName){
        return id + File.separator + getShaftIllustDir(illustsBean) + getAuthorPathPart(illustsBean) + File.separator + fileName;
    }

    public static String getIllustAbsolutePath(IllustsBean illustsBean){
        return PathUtils.getExternalPicturesPath() + File.separator + getShaftIllustDir(illustsBean) + getAuthorPathPart(illustsBean);
    }

    public static String getIllustAbsolutePath(IllustsBean illustsBean, boolean isR18){
        return PathUtils.getExternalPicturesPath() + File.separator + getShaftIllustDir(isR18) + getAuthorPathPart(illustsBean);
    }

    public static String getIllustRelativePathQ(IllustsBean illustsBean) {
        return Environment.DIRECTORY_PICTURES + File.separator + getShaftIllustDir(illustsBean) + getAuthorPathPart(illustsBean);
    }

    public static String getNovelRelativePathQ() {
        return Environment.DIRECTORY_DOWNLOADS + File.separator + "ShaftNovels";
    }

    public static String getShaftIllustDir(IllustsBean illustsBean) {
        return isSaveToR18Dir(illustsBean) ? "ShaftImages-R18" : "ShaftImages";
    }

    private static String getShaftIllustDir(boolean isR18) {
        return isR18 ? "ShaftImages-R18" : "ShaftImages";
    }

    private static Boolean isSaveToR18Dir(IllustsBean illustsBean){
        return illustsBean.isR18File() && Shaft.sSettings.isR18DivideSave();
    }

    private static String getAuthorPathPart(IllustsBean illustsBean) {
        String name = getAuthorDirectoryName(illustsBean);
        return name.length() > 0 ? File.separator + name : name;
    }

    public static String getAuthorDirectoryName(IllustsBean illustsBean){
        return Shaft.sSettings.isSaveForSeparateAuthor() ? String.valueOf(illustsBean.getUser().getId()) : "";
    }
}
