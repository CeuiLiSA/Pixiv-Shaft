package ceui.lisa.interfaces;

import android.content.Context;

import java.util.List;

import ceui.lisa.models.IllustsBean;
import ceui.pixiv.ui.bulk.LegacyBatchEnqueue;

/**
 * 旧入口（列表长按 / popup "批量下载"）。原本会跳到 FragmentMultiDownload 的勾选页，
 * 现在直接把当前可见的列表全部入新持久化下载队列（download_queue v33）。
 */
public interface MultiDownload {

    Context getContext();

    List<IllustsBean> getIllustList();

    default void startDownload() {
        LegacyBatchEnqueue.INSTANCE.enqueueAndToast(getContext(), getIllustList());
    }
}
