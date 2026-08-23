package ceui.lisa.database;

/**
 * "这一页下过没 + 文件在哪"的投影，只有三列。
 *
 * <p>存在的唯一理由是**别把 illustGson 读出来**。那一列存的是整个 Illust 的 JSON，
 * 单行几 KB，30000+ 行的库总量 2GB 级 —— v38 加 illustId 索引列就是为了不再碰它
 * （见 {@link DownloadDao#hasDownloadRecordByIllustIdIndexed}）。而
 * {@code SELECT *} 会把它一起捞上来：一个 172P 的作品在 {@code IllustAdapter} 里
 * 展开一次就要白读近 1MB blob，feed 里每张卡片都建一个 adapter，代价是乘出来的。
 *
 * <p>调用方只需要 filePath（拿去 Uri.parse）和 page（回填到页码 → Uri 的映射），
 * fileName 留着方便日志定位。
 */
public final class DownloadedPage {

    public String fileName;

    public String filePath;

    public int page;
}
