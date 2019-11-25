package ceui.lisa.interfaces;

import android.content.Context;
import android.content.Intent;

import java.util.List;

import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.DataChannel;

public interface MultiDownload {

    Context getContext();

    List<IllustsBean> getIllustList();

    default void startDownload() {
        DataChannel dataChannel = DataChannel.get();
        dataChannel.setDownloadList(getIllustList());
        Intent intent = new Intent(getContext(), TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "批量下载");
        getContext().startActivity(intent);
    }
}
