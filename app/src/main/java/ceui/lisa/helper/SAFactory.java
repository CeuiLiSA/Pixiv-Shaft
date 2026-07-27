package ceui.lisa.helper;

import android.content.Context;
import android.net.Uri;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import ceui.lisa.core.DownloadItem;
import ceui.lisa.download.DownloadFileFactory;
import ceui.pixiv.download.Downloads;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.backend.StorageBackend;
import ceui.pixiv.download.config.DownloadItems;
import ceui.pixiv.download.config.OverwritePolicy;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * Legacy factory kept for binary compatibility with {@code Manager.downloadOne}.
 * Routes through the new download facade — SAF / MediaStore / cache dispatch
 * is picked up automatically from the active {@code DownloadConfig}, not from
 * the old {@code downloadWay} setting.
 *
 * Invariants: see {@link Android10DownloadFactory22}.
 */
public class SAFactory implements DownloadFileFactory {

    private final Downloads.Plan mPlan;
    private Uri mUri;
    private Function0<Unit> mOnFinish = () -> Unit.INSTANCE;
    private Function0<Unit> mOnAbort = () -> Unit.INSTANCE;
    /** 终态保护：finish 或 abandon 任一发生后，再次调用都是 no-op。 */
    private boolean mSettled = false;

    public SAFactory(@NotNull Context context, DownloadItem item) {
        ceui.pixiv.download.model.DownloadItem newItem = item.getIllust().isGif()
                ? DownloadItems.ugoiraZip(item.getIllust())
                : DownloadItems.illustPage(item.getIllust(), item.getIndex());
        mPlan = DownloadsRegistry.getDownloads().plan(newItem);
    }

    @Nullable
    @Override
    public Uri query() {
        return mUri;
    }

    @NotNull
    @Override
    public Uri insert() {
        if (mUri != null) return mUri;
        if (mPlan.getSkip()) {
            throw new IllegalStateException(
                    "Facade plan is marked skip for " + mPlan.getPath() + " — Manager must not call insert()");
        }
        StorageBackend.WriteHandle handle = mPlan.open();
        try {
            handle.getStream().close();
        } catch (Exception ignored) {
        }
        mUri = handle.getUri();
        mOnFinish = handle.getOnFinish();
        mOnAbort = handle.getOnAbort();
        return mUri;
    }

    @NotNull
    @Override
    public Uri getFileUri() {
        return mUri != null ? mUri : insert();
    }

    // 按 facade 解析出的 backend 判定 content:// 语义（探 scheme 不触发 open，零副作用）。
    @Override
    public boolean targetIsContent() {
        return mPlan.getBackend().isContentScheme();
    }

    @Override
    public void finishWrite() {
        if (mSettled) return;
        mSettled = true;
        try {
            mOnFinish.invoke();
        } catch (Exception e) {
            android.util.Log.w("SAFactory", "finishWrite failed: " + e.getMessage());
        }
    }

    @Override
    public void abandonWrite() {
        if (mSettled) return;
        mSettled = true;
        // mUri == null 说明 insert 还没成功，没东西可清理。
        if (mUri == null) return;
        try {
            mOnAbort.invoke();
        } catch (Exception e) {
            // 清理是 best-effort —— 失败也别遮蔽真正的下载错误。
            android.util.Log.w("SAFactory", "abandonWrite failed: " + e.getMessage());
        }
    }

    public boolean isSkip() {
        return mPlan.getSkip();
    }

    /**
     * 该 bucket 解析后的覆盖策略是不是「已存在则跳过」。
     *
     * {@code Manager.downloadOne} 用它决定要不要在 facade 说"不跳过"之后再问一次下载
     * 记录（{@link ceui.pixiv.download.RecordedPageProbe}）—— facade 只按当前模板算出的
     * 路径查文件系统，认不出用别的命名规则存下来的旧文件（issue #953）。
     * Rename 是用户明确要新文件、Replace 本来就要覆写，都不该被记录短路。
     */
    public boolean isSkipPolicy() {
        return mPlan.getOverwrite() == OverwritePolicy.Skip;
    }
}
