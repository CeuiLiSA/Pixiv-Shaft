package ceui.lisa.database;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.StringReader;

/**
 * 从 illust_download_table 的 illustGson blob 抽取顶层 {@code "id"}（作品自身 id）。
 *
 * illustGson 是 {@code ceui.pixiv.api.model.Illust} / Novel 的 Gson 序列化，顶层直接就有
 * {@code "id":<number>}。这里用流式 {@link JsonReader} 只读到顶层的 id 就停，**不下钻**
 * 嵌套对象（image_urls / user / meta_pages 各自也有自己的 id），所以拿到的永远是作品
 * 本身的 id —— 比旧的 {@code illustGson LIKE '%"id":X%'} 更精确（LIKE 会误命中 caption
 * 文本里的 "id": 或嵌套对象的 id）。
 *
 * 服务于 v38 的 illustId 索引列：既在插入时算好（{@link DownloadDao#insertDownload}），
 * 也给存量回填用（{@code DownloadIdBackfill}）。解析失败 / 无顶层 id 返回 -1
 * （Pixiv 的 id 恒为正，-1 不会撞真实作品，也能让回填把这行标记为已处理不再重试）。
 */
public final class DownloadIdExtractor {

    private DownloadIdExtractor() {}

    public static long extractIllustId(String illustGson) {
        if (illustGson == null || illustGson.isEmpty()) {
            return -1L;
        }
        try (JsonReader reader = new JsonReader(new StringReader(illustGson))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("id".equals(name)) {
                    if (reader.peek() == JsonToken.NUMBER) {
                        return reader.nextLong();
                    }
                    reader.skipValue();
                    return -1L;
                }
                reader.skipValue();
            }
        } catch (Exception e) {
            return -1L;
        }
        return -1L;
    }
}
