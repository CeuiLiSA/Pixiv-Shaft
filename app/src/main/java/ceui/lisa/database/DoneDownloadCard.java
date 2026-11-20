package ceui.lisa.database;

/** 已完成作品分页的轻量投影；完整元数据只按代表文件名读取一次。 */
public final class DoneDownloadCard {
    public String cardKey;
    public String fileName;
    public int pageCount;
    public long downloadTime;
}
