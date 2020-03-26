package ceui.lisa.helper;

import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Target;

import ceui.lisa.download.FileCreator;
import ceui.lisa.interfaces.Callback;

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
