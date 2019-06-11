package ceui.lisa.download;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;

import java.io.File;

public class SingleMediaScanner implements MediaScannerConnection.MediaScannerConnectionClient {

    public interface ScanListener{
        public void onScanFinish();
    }

    private MediaScannerConnection mMs;
    private String mFilePath;
    private ScanListener listener;
    private final String TAG = "SingleMediaScanner";

    public SingleMediaScanner(Context context, File f, ScanListener l) {
        listener = l;
        mFilePath = f.getAbsolutePath();
        mMs = new MediaScannerConnection(context, this);
        mMs.connect();
    }

    public SingleMediaScanner(Context context, String filePath,ScanListener l) {
        listener = l;
        mFilePath = filePath;
        mMs = new MediaScannerConnection(context, this);
        mMs.connect();
    }

    @Override
    public void onMediaScannerConnected() {
        mMs.scanFile(mFilePath, null);
    }

    @Override
    public void onScanCompleted(String path, Uri uri) {
        mMs.disconnect();
        listener.onScanFinish();
    }

}