package ceui.lisa.helper;

import android.os.Environment;

import com.blankj.utilcode.util.PathUtils;

import java.io.File;

import ceui.lisa.activities.Shaft;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserBean;
import ceui.lisa.utils.Common;

public class FileStorageHelper {

    private static final char sep = File.separatorChar;

    public static String getIllustFileFullNameUnderQ(DownloadItem downloadItem){
        return getIllustAbsolutePath(downloadItem.getIllust()) + sep + downloadItem.getName();
    }

    public static String getIllustFileRelativeNameQ(DownloadItem downloadItem) {
        return getIllustRelativePathQ(downloadItem.getIllust()) + sep + downloadItem.getName();
    }

    public static String getIllustFileSAFFullName(String id, IllustsBean illustsBean, String fileName){
        return id + getShaftIllustPathPartWithInnerR18Folder(illustsBean) + getAuthorPathPart(illustsBean) + File.separator + fileName;
    }

    public static String getIllustAbsolutePath(IllustsBean illustsBean){
        return PathUtils.getExternalPicturesPath() + sep + getShaftIllustDirWithInnerR18Folder(isSaveToR18Dir(illustsBean)) + getAuthorPathPart(illustsBean);
    }

    public static String getIllustAbsolutePath(IllustsBean illustsBean, boolean isR18){
        return PathUtils.getExternalPicturesPath() + sep + getShaftIllustDirWithInnerR18Folder(isR18) + getAuthorPathPart(illustsBean);
    }

    public static String getIllustRelativePathQ(IllustsBean illustsBean) {
        return Environment.DIRECTORY_PICTURES + sep + getShaftIllustDirWithInnerR18Folder(isSaveToR18Dir(illustsBean)) + getAuthorPathPart(illustsBean);
    }

    public static String getNovelRelativePathQ() {
        return Environment.DIRECTORY_DOWNLOADS + sep + "ShaftNovels";
    }

    public static String getShaftIllustR18DirNameWithInnerR18Folder(IllustsBean illustsBean) {
        return isSaveToR18Dir(illustsBean) ? "ShaftImages-R18" : "";
    }

    public static String getShaftIllustPathPartWithInnerR18Folder(IllustsBean illustsBean) {
        return getShaftIllustPathPartWithInnerR18Folder(isSaveToR18Dir(illustsBean));
    }

    public static String getShaftIllustPathPartWithInnerR18Folder(boolean isR18) {
        return isR18 ? sep + "ShaftImages-R18" : "";
    }

    public static String getShaftIllustDirWithInnerR18Folder(boolean isR18) {
        return "ShaftImages" + getShaftIllustPathPartWithInnerR18Folder(isR18);
    }

    public static String getShaftIllustDir(IllustsBean illustsBean) {
        return getShaftIllustDir(isSaveToR18Dir(illustsBean));
    }

    private static String getShaftIllustDir(boolean isR18) {
        return isR18 ? "ShaftImages-R18" : "ShaftImages";
    }

    private static boolean isSaveToR18Dir(IllustsBean illustsBean){
        return illustsBean.isR18File() && Shaft.sSettings.isR18DivideSave();
    }

    private static String getAuthorPathPart(IllustsBean illustsBean) {
        String name = getAuthorDirectoryName(illustsBean.getUser());
        return name.length() > 0 ? sep + name : name;
    }

    public static String getAuthorDirectoryName(UserBean userBean){
        return Shaft.sSettings.isSaveForSeparateAuthor() ? getCleanAuthorDirectoryName(userBean) : "";
    }

    private static String getCleanAuthorDirectoryName(UserBean userBean){
        return Common.removeFSReservedChars(userBean.getName() + "_" + userBean.getId());
    }
}
