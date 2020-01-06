package ceui.lisa.gif.core;

public class GifState {

    public static final int ST1 = 1; // 未下载zip包
    public static final int ST2 = 2; // 下载了zip包未解压
    public static final int ST3 = 3; // 解压了zip包
    public static final int ST4 = 4; // 已生成了GIF文件
}
