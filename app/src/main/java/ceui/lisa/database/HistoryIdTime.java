package ceui.lisa.database;

/**
 * {@code illust_table} 的 (illustID, time, type) 投影,给云端历史 LWW 物化回写(#989)判断
 * 「本地这条是不是更新、是不是同类」用。不取 illustJson —— 全行 SELECT 会把每行大 JSON
 * 一起塞进 CursorWindow(同 {@link DownloadDao#getAllViewHistoryIds()} 的教训)。
 */
public class HistoryIdTime {
    public int illustID;
    public long time;
    public int type;
}
