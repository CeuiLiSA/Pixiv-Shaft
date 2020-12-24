package ceui.lisa.file;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.blankj.utilcode.util.PathUtils;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

import ceui.lisa.core.DownloadItem;
import ceui.lisa.download.FileCreator;
import ceui.lisa.utils.Common;
import okhttp3.Response;
import rxhttp.wrapper.callback.UriFactory;

public class Android10DownloadFactory extends UriFactory {

    private DownloadItem mDownloadItem;
    private Uri mUri;

    public Android10DownloadFactory(@NotNull Context context, DownloadItem downloadItem) {
        super(context);
        mDownloadItem = downloadItem;
    }

    public Uri getUri() {
        return mUri;
    }

    public void setUri(Uri uri) {
        mUri = uri;
    }


    public Uri getInsertUri() {
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }


    @NotNull
    @Override
    public Uri insert(@NotNull Response response) throws IOException {
        String displayName = FileCreator.createIllustFile(mDownloadItem.getIllust(),
                mDownloadItem.getIndex()).getName();



        String selection = MediaStore.Images.Media.DISPLAY_NAME + " = '" + displayName + "'";



        // content://media/external/images/media/73404
        Cursor cursor = getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                null, selection, null, null);
        if (cursor != null && cursor.getCount() > 0) {
            cursor.moveToFirst();
            String aaa = "_data = " + cursor.getLong(0);
            Common.showLog("已存在的文件 uri " + aaa + " " + cursor.getCount());
        }


        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, response.body().contentType().toString());
        if (Common.isAndroidQ()) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ShaftImages");
        } else {
            File parentFile = new File(PathUtils.getExternalPicturesPath() + "/ShaftImages");
            if (!parentFile.exists()) {
                parentFile.mkdir();
            }
            File imageFile = new File(parentFile, displayName);
            values.put(MediaStore.MediaColumns.DATA, imageFile.getPath());
        }
        mUri = getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        return mUri;
    }
}
