package ceui.lisa.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;

import androidx.documentfile.provider.DocumentFile;
import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentSafBinding;
import ceui.lisa.file.SAFile;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Params;

import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

public class FragmentSAF extends BaseFragment<FragmentSafBinding> {

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_saf;
    }

    @Override
    protected void initView() {
        baseBind.request.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                if (!TextUtils.isEmpty(Shaft.sSettings.getRootPathUri()) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Uri start = Uri.parse(Shaft.sSettings.getRootPathUri());
                    intent.putExtra(EXTRA_INITIAL_URI, start);
                }
                mActivity.startActivityForResult(intent, BaseActivity.ASK_URI);
            }
        });
        baseBind.create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    IllustsBean illustsBean = Shaft.sGson.fromJson(Params.EXAMPLE_ILLUST, IllustsBean.class);
                    ceui.pixiv.download.backend.StorageBackend.WriteHandle handle =
                            ceui.pixiv.download.DownloadsRegistry.getDownloads().open(
                                    ceui.pixiv.download.config.DownloadItems.illustPage(illustsBean, 0));
                    if (handle != null) {
                        try { handle.getStream().close(); } catch (Exception ignored) {}
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    com.hjq.toast.ToastUtils.show(getString(R.string.saf_write_failed,
                            t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                }
            }
        });
    }
}
