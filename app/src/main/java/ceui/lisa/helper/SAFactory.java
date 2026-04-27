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
        return mUri;
    }

    @NotNull
    @Override
    public Uri getFileUri() {
        return mUri != null ? mUri : insert();
    }

    @Override
    public void finishWrite() {
        try {
            mOnFinish.invoke();
        } catch (Exception e) {
            android.util.Log.w("SAFactory", "finishWrite failed: " + e.getMessage());
        }
    }

    public boolean isSkip() {
        return mPlan.getSkip();
    }
}
