package ceui.lisa.helper;

import android.content.Context;
import android.net.Uri;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import androidx.documentfile.provider.DocumentFile;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.download.DownloadFileFactory;
import ceui.lisa.file.SAFile;

public class SAFactory implements DownloadFileFactory {

    private final Context mContext;
    private final DownloadItem mItem;
    private Uri mUri;

    public SAFactory(@NotNull Context context, DownloadItem item) {
        this.mContext = context;
        this.mItem = item;
        DocumentFile file = SAFile.getDocument(mContext, mItem.getIllust(), mItem.getIndex(), mItem.shouldStartNewDownload());
        mUri = file.getUri();
    }

    @Nullable
    @Override
    public Uri query() {
        return mUri;
    }

    @NotNull
    @Override
    public Uri insert() {
        return mUri;
    }

    @NotNull
    @Override
    public Uri getFileUri() {
        return mUri;
    }

    public void setUri(Uri uri) {
        mUri = uri;
    }
}
