package ceui.lisa.helper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import ceui.lisa.download.FileCreator;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;

public class TextWriter {

    public static void writeToTxt(String fileName, String content, Callback<File> targetCallback){
        File file = FileCreator.createLogFile(fileName);
        if(!file.exists()){
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Common.showLog("file path " + file.getPath());

        final FileOutputStream outStream;
        try {
            outStream = new FileOutputStream(file);
            outStream.write(content.getBytes());
            outStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(targetCallback != null) {
            targetCallback.doSomething(file);
        }
    }

    public static void writeToTxt(String fileName, String content) {
        writeToTxt(fileName, content, null);
    }
}
