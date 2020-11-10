package ceui.lisa.helper;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import ceui.lisa.core.SAFile;
import ceui.lisa.download.FileCreator;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Common;

public class TextWriter {

    public static void writeToTxt(String displayName, String content, Context context, Callback<Uri> targetCallback){
        DocumentFile documentFile = SAFile.findNovelFile(context, displayName);
        if (documentFile != null && documentFile.length() > 100) {
            Common.showLog("writeToTxt 已下载，不用新建");
        } else {
            documentFile = SAFile.createNovelFile(context, displayName);
            Common.showLog("writeToTxt 需要新建");
            try {
                OutputStream outStream = context.getContentResolver().openOutputStream(documentFile.getUri());
                outStream.write(content.getBytes());
                outStream.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if(targetCallback != null) {
            targetCallback.doSomething(documentFile.getUri());
        }
    }

    public static void writeToTxt(String displayName, String content, Context context) {
        writeToTxt(displayName, content, context, null);
    }
}
