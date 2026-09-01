package ceui.lisa.fragments;

import android.view.View;

import androidx.documentfile.provider.DocumentFile;
import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentSafBinding;
import ceui.lisa.file.SAFile;
import ceui.pixiv.api.model.Illust;
import ceui.lisa.utils.Params;

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
                BaseActivity.launchSafTreePicker(mActivity);
            }
        });
        baseBind.create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Illust illustsBean = Shaft.sGson.fromJson(Params.EXAMPLE_ILLUST, Illust.class);
                    ceui.pixiv.download.backend.StorageBackend.WriteHandle handle =
                            ceui.pixiv.download.DownloadsRegistry.getDownloads().open(
                                    ceui.pixiv.download.config.DownloadItems.illustPage(illustsBean, 0));
                    if (handle != null) {
                        try { handle.getStream().close(); } catch (Exception ignored) {}
                        // Intentionally do NOT call onFinish: the probe writes 0
                        // bytes, and committing would expose an empty image row
                        // to the gallery. Any orphan pending row is cleaned up
                        // by the system after the standard pending TTL.
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    com.hjq.toast.Toaster.show(getString(R.string.saf_write_failed,
                            t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                }
            }
        });
    }
}
