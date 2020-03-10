package ceui.lisa;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;

public class FileLineCounter {

    private String mFolderPath;
    private int mLineCount, fileCount;
    private static final String DIVIDER = "**********************************************";

    public FileLineCounter(String mFolderPath) {
        this.mFolderPath = mFolderPath;
    }

    public void run() {
        System.out.println("开始文件检索：");
        traverseFolder(mFolderPath);
        System.out.println("一共有" + fileCount + "个文件，" + mLineCount + "行代码");
    }


    private void traverseFolder(String path) {
        File file = new File(path);
        if (file.exists()) {
            File[] files = file.listFiles();
            if (null == files || files.length == 0) {
                System.out.println("文件夹是空的!");
            } else {
                for (File file2 : files) {
                    if (file2.isDirectory()) {
                        traverseFolder(file2.getAbsolutePath());
                    } else {
                        System.out.println(DIVIDER);
                        fileCount++;
                        System.out.println("* 第" + fileCount + "个文件");
                        System.out.println("* 文件名:" + file2.getAbsolutePath());
                        countLine(file2);
                    }
                }
            }
        } else {
            System.out.println("文件/文件夹不存在!");
        }
    }

    private void countLine(File file) {
        try {
            if (file.exists()) {
                FileReader fr = new FileReader(file);
                LineNumberReader lnr = new LineNumberReader(fr);
                int linenumber = 0;
                while (lnr.readLine() != null) {
                    linenumber++;
                }
                System.out.println("* 单个文件行数 " + linenumber);
                System.out.println(DIVIDER);
                System.out.println();
                mLineCount = mLineCount + linenumber;
                lnr.close();
            } else {
                System.out.println("文件不存在!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
