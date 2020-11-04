package ceui.lisa.download;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;
import ceui.lisa.core.SAFile;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class IllustDownload {

    public static void downloadIllust(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                DownloadItem item = new DownloadItem(illust);
                item.setUrl(getUrl(illust, 0));
                item.setFile(SAFile.getDocument(activity, illust, 0));
                item.setShowUrl(getShowUrl(illust, 0));
                Manager.get().addTask(item);
                Manager.get().start(activity);
            }
            Common.showToast(Shaft.getContext().getString(R.string.one_item_added));
        });
    }


    public static void downloadIllust(IllustsBean illust, int index, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllust(illust, activity);
            } else {
                DownloadItem item = new DownloadItem(illust);
                item.setUrl(getUrl(illust, index));
                item.setFile(SAFile.getDocument(activity, illust, index));
                item.setShowUrl(getShowUrl(illust, index));
                Manager.get().addTask(item);
                Manager.get().start(activity);
                Common.showToast(Shaft.getContext().getString(R.string.one_item_added));
            }
        });
    }


    public static void downloadAllIllust(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllust(illust, activity);
                return;
            }

            List<DownloadItem> tempList = new ArrayList<>();
            for (int i = 0; i < illust.getPage_count(); i++) {
                DownloadItem item = new DownloadItem(illust);
                item.setUrl(getUrl(illust, i));
                item.setFile(SAFile.getDocument(activity, illust, i));
                item.setShowUrl(getShowUrl(illust, i));
                tempList.add(item);
            }
            Manager.get().addTasks(tempList);
            Manager.get().start(activity);
            Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
        });
    }


    public static void downloadAllIllust(List<IllustsBean> beans, BaseActivity<?> activity) {
        check(activity, () -> {
            List<DownloadItem> tempList = new ArrayList<>();
            for (int i = 0; i < beans.size(); i++) {
                if (beans.get(i).isChecked()) {
                    final IllustsBean illust = beans.get(i);

                    if (illust.getPage_count() == 1) {
                        DownloadItem item = new DownloadItem(illust);
                        item.setUrl(getUrl(illust, 0));
                        item.setFile(SAFile.getDocument(activity, illust, 0));
                        item.setShowUrl(getShowUrl(illust, 0));
                        tempList.add(item);
                    } else {
                        for (int j = 0; j < illust.getPage_count(); j++) {
                            DownloadItem item = new DownloadItem(illust);
                            item.setUrl(getUrl(illust, j));
                            item.setFile(SAFile.getDocument(activity, illust, j));
                            item.setShowUrl(getShowUrl(illust, j));
                            tempList.add(item);
                        }
                    }
                }
            }
            Manager.get().addTasks(tempList);
            Manager.get().start(activity);
            Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
        });
    }

    public static void downloadGif(GifResponse response, IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            DownloadItem item = new DownloadItem(illust);
            item.setUrl(response.getUgoira_metadata().getZip_urls().getMedium());
            item.setFile(SAFile.getDocument(activity, illust, 1));
            Manager.get().addTask(item);
            Manager.get().start(activity);
            Common.showToast("图组ZIP已加入下载队列");
        });
    }

    public static String getUrl(IllustsBean illust, int index) {
        if (illust.getPage_count() == 1) {
            return "https://pixiv.cat/" + illust.getId() + "." + getMimeType(illust, index);
        } else {
            return "https://pixiv.cat/" + illust.getId() +
                    "-" + (index+1) + "." + getMimeType(illust, index);
        }
    }

    public static String getShowUrl(IllustsBean illust, int index) {
        if (illust.getPage_count() == 1) {
            return illust.getImage_urls().getMedium();
        } else {
            return illust.getMeta_pages().get(index).getImage_urls().getMedium();
        }
    }

    public static String getMimeType(IllustsBean illust, int index) {
        String url;
        if (illust.getPage_count() == 1) {
            url = illust.getMeta_single_page().getOriginal_image_url();
        } else {
            url = illust.getMeta_pages().get(index).getImage_urls().getOriginal();
        }

        String result = "png";
        if (url.contains(".")) {
            result = url.substring(url.lastIndexOf(".") + 1);
        }
        return result;
    }

    private static void check(BaseActivity<?> activity, FeedBack feedBack) {
        if (TextUtils.isEmpty(Shaft.sSettings.getRootPathUri())) {
            activity.setFeedBack(feedBack);
            new QMUIDialog.MessageDialogBuilder(activity)
                    .setTitle(activity.getResources().getString(R.string.string_143))
                    .setMessage(activity.getResources().getString(R.string.string_313))
                    .setSkinManager(QMUISkinManager.defaultInstance(activity))
                    .addAction(0, activity.getResources().getString(R.string.string_142),
                            QMUIDialogAction.ACTION_PROP_NEGATIVE,
                            (dialog, index) -> dialog.dismiss())
                    .addAction(0, activity.getResources().getString(R.string.string_312),
                            (dialog, index) -> {
                                activity.startActivityForResult(
                                        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), BaseActivity.ASK_URI);
                                dialog.dismiss();
                            })
                    .show();
        } else {
            if (feedBack != null) {
                feedBack.doSomething();
            }
        }
    }
}
