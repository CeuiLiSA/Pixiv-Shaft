package ceui.lisa.download;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;
import ceui.lisa.core.SAFile;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class IllustDownload {

    public static void downloadIllust(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                DownloadItem item = new DownloadItem(illust, 0);
                item.setUrl(getUrl(illust, 0));
                item.setShowUrl(getShowUrl(illust, 0));
                Manager.get().addTask(item, activity);
            }
        });
    }


    public static void downloadIllust(IllustsBean illust, int index, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllust(illust, activity);
            } else {
                DownloadItem item = new DownloadItem(illust, index);
                item.setUrl(getUrl(illust, index));
                item.setShowUrl(getShowUrl(illust, index));
                Manager.get().addTask(item, activity);
            }
        });
    }


    public static void downloadAllIllust(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllust(illust, activity);
            } else {
                List<DownloadItem> tempList = new ArrayList<>();
                for (int i = 0; i < illust.getPage_count(); i++) {
                    DownloadItem item = new DownloadItem(illust, i);
                    item.setUrl(getUrl(illust, i));
                    item.setShowUrl(getShowUrl(illust, i));
                    tempList.add(item);
                }
                Manager.get().addTasks(tempList, activity);
            }
        });
    }


    public static void downloadAllIllust(List<IllustsBean> beans, BaseActivity<?> activity) {
        check(activity, () -> {
            List<DownloadItem> tempList = new ArrayList<>();
            for (int i = 0; i < beans.size(); i++) {
                if (beans.get(i).isChecked()) {
                    final IllustsBean illust = beans.get(i);

                    if (illust.getPage_count() == 1) {
                        DownloadItem item = new DownloadItem(illust, 0);
                        item.setUrl(getUrl(illust, 0));
                        item.setShowUrl(getShowUrl(illust, 0));
                        tempList.add(item);
                    } else {
                        for (int j = 0; j < illust.getPage_count(); j++) {
                            DownloadItem item = new DownloadItem(illust, j);
                            item.setUrl(getUrl(illust, j));
                            item.setShowUrl(getShowUrl(illust, j));
                            tempList.add(item);
                        }
                    }
                }
            }
            Manager.get().addTasks(tempList, activity);
        });
    }

    public static void downloadGif(GifResponse response, DocumentFile file, IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            DownloadItem item = new DownloadItem(illust, 0);
            item.setUrl(response.getUgoira_metadata().getZip_urls().getMedium());
            item.setShowUrl(illust.getImage_urls().getMedium());
            Manager.get().addTask(item, activity);
        });
    }

    public static void downloadNovel(BaseActivity<?> activity, String displayName, String content, Callback<Uri> targetCallback) {
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                DocumentFile documentFile = SAFile.findNovelFile(activity, displayName);
                if (documentFile != null && documentFile.length() > 100) {
                    Common.showLog("writeToTxt 已下载，不用新建");
                } else {
                    documentFile = SAFile.createNovelFile(activity, displayName);
                    Common.showLog("writeToTxt 需要新建");
                    try {
                        OutputStream outStream = activity.getContentResolver().openOutputStream(documentFile.getUri());
                        outStream.write(content.getBytes());
                        outStream.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (targetCallback != null) {
                    targetCallback.doSomething(documentFile.getUri());
                }
            }
        });
    }

    public static String getUrl(IllustsBean illust, int index) {
        if (illust.getPage_count() == 1) {
            return "https://pixiv.cat/" + illust.getId() + "." + getMimeType(illust, index);
        } else {
            return "https://pixiv.cat/" + illust.getId() +
                    "-" + (index + 1) + "." + getMimeType(illust, index);
        }
//        return "http://update.9158.com/miaolive/Miaolive.apk";
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

    public static void check(BaseActivity<?> activity, FeedBack feedBack) {
        if (Common.isAndroidQ() && TextUtils.isEmpty(Shaft.sSettings.getRootPathUri())) {
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
                                try {
                                    activity.startActivityForResult(
                                            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), BaseActivity.ASK_URI);
                                } catch (Exception e) {
                                    Common.showToast(e.toString());
                                    e.printStackTrace();
                                }
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
