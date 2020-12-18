package ceui.lisa.download;

import android.media.MediaScannerConnection;

import java.io.File;

import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;

public abstract class ImageSaver {

    public abstract File whichFile();

    public void execute() {
        File file = whichFile();
        if (file == null) {
            return;
        }

        String[] path = new String[1];
        String[] mime = new String[1];
        String filePath = file.getPath();
        Common.showLog("ImageSaver before " + filePath);
        path[0] = filePath;
        if (filePath.endsWith(".gif")) {
            mime[0] = "image/gif";
        } else if (filePath.endsWith(".jpeg") || filePath.endsWith(".jpg")) {
            mime[0] = "image/jpeg";
        } else {
            mime[0] = "image/png";
        }
        MediaScannerConnection.scanFile(
                Shaft.getContext(), path, mime, (path1, uri) -> { }
        );
    }

    /**
     *   {".3gp",    "video/3gpp"},
     *   {".apk",    "application/vnd.android.package-archive"},
     *   {".asf",    "video/x-ms-asf"},
     *   {".avi",    "video/x-msvideo"},
     *   {".bin",    "application/octet-stream"},
     *   {".bmp",    "image/bmp"},
     *   {".c",  "text/plain"},   
     *   {".class",  "application/octet-stream"},
     *   {".conf",   "text/plain"},
     *   {".cpp",    "text/plain"},
     *   {".doc",    "application/msword"},
     *   {".docx",   "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
     *   {".xls",    "application/vnd.ms-excel"},
     *   {".xlsx",   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
     *   {".exe",    "application/octet-stream"},
     *   {".gif",    "image/gif"},
     *   {".gtar",   "application/x-gtar"},
     *   {".gz", "application/x-gzip"},
     *   {".h",  "text/plain"},
     *   {".htm",    "text/html"},
     *   {".html",   "text/html"},
     *   {".jar",    "application/java-archive"},
     *   {".java",   "text/plain"},
     *   {".jpeg",   "image/jpeg"},
     *   {".jpg",    "image/jpeg"},
     *   {".js", "application/x-javascript"},
     *   {".log",    "text/plain"},
     *   {".m3u",    "audio/x-mpegurl"},
     *   {".m4a",    "audio/mp4a-latm"},
     *   {".m4b",    "audio/mp4a-latm"},
     *   {".m4p",    "audio/mp4a-latm"},
     *   {".m4u",    "video/vnd.mpegurl"},
     *   {".m4v",    "video/x-m4v"},
     *   {".mov",    "video/quicktime"},
     *   {".mp2",    "audio/x-mpeg"},
     *   {".mp3",    "audio/x-mpeg"},
     *   {".mp4",    "video/mp4"},
     *   {".mpc",    "application/vnd.mpohun.certificate"},
     *   {".mpe",    "video/mpeg"},
     *   {".mpeg",   "video/mpeg"},
     *   {".mpg",    "video/mpeg"},
     *   {".mpg4",   "video/mp4"},
     *   {".mpga",   "audio/mpeg"},
     *   {".msg",    "application/vnd.ms-outlook"},
     *   {".ogg",    "audio/ogg"},
     *   {".pdf",    "application/pdf"},
     *   {".png",    "image/png"},
     *   {".pps",    "application/vnd.ms-powerpoint"},
     *   {".ppt",    "application/vnd.ms-powerpoint"},
     *   {".pptx",   "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
     *   {".prop",   "text/plain"},
     *   {".rc", "text/plain"},
     *   {".rmvb",   "audio/x-pn-realaudio"},
     *   {".rtf",    "application/rtf"},
     *   {".sh", "text/plain"},
     *   {".tar",    "application/x-tar"},
     *   {".tgz",    "application/x-compressed"},
     *   {".txt",    "text/plain"},
     *   {".wav",    "audio/x-wav"},
     *   {".wma",    "audio/x-ms-wma"},
     *   {".wmv",    "audio/x-ms-wmv"},
     *   {".wps",    "application/vnd.ms-works"},
     *   {".xml",    "text/plain"},
     *   {".z",  "application/x-compress"},
     *   {".zip",    "application/x-zip-compressed"},
     */
}
