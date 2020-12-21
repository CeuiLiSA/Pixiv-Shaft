package ceui.lisa.download;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;
import ceui.lisa.core.SAFile;
import ceui.lisa.core.UrlFactory;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

public class IllustDownload {

    public static void downloadIllust(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                DownloadItem item = new DownloadItem(illust, 0);
                item.setUrl(getUrl(illust, 0));
                item.setShowUrl(getShowUrl(illust, 0));
                Common.showToast(1 + "个任务已经加入下载队列");
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
                Common.showToast(1 + "个任务已经加入下载队列");
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
                Common.showToast(tempList.size() + "个任务已经加入下载队列");
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
            Common.showToast(tempList.size() + "个任务已经加入下载队列");
            Manager.get().addTasks(tempList, activity);
        });
    }

    public static void downloadGif(GifResponse response, IllustsBean illust, BaseActivity<?> activity) {
        DownloadItem item = new DownloadItem(illust, 0);
        item.setUrl(response.getUgoira_metadata().getZip_urls().getMedium());
        item.setShowUrl(UrlFactory.invoke(illust.getImage_urls().getMedium()));
        Manager.get().addTask(item, activity);
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

    public static void saveGif(File fromGif, IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> {
            DocumentFile file = SAFile.getGifDocument(activity, illust);
            if (file != null) {
                if (copyFile(fromGif, file.getUri(), activity)) {
                    Common.showToast("GIF保存成功");
                }
            }
        });
    }

    public static boolean copyFile(File src, Uri des, Context context) {
        if (!src.exists()) {
            Log.e("cppyFile", "file not exist:" + src.getAbsolutePath());
            return false;
        }
        BufferedInputStream bis = null;
        BufferedOutputStream bos = null;
        try {
            bis = new BufferedInputStream(new FileInputStream(src));
            bos = new BufferedOutputStream(context.getContentResolver().openOutputStream(des));
            byte[] buffer = new byte[4 * 1024];
            int count;
            while ((count = bis.read(buffer, 0, buffer.length)) != -1) {
                if (count > 0) {
                    bos.write(buffer, 0, count);
                }
            }
            bos.flush();
            return true;
        } catch (Exception e) {
            Log.e("copyFile", "exception:", e);
        } finally {
            if (bis != null) {
                try {
                    bis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }

    public static String getUrl(IllustsBean illust, int index) {
        if (illust.getPage_count() == 1) {
            return UrlFactory.invoke(illust.getMeta_single_page().getOriginal_image_url());
        } else {
            return UrlFactory.invoke(illust.getMeta_pages().get(index).getImage_urls().getOriginal());
        }
    }

    public static String getShowUrl(IllustsBean illust, int index) {
        if (illust.getPage_count() == 1) {
            return illust.getImage_urls().getMedium();
        } else {
            return illust.getMeta_pages().get(index).getImage_urls().getMedium();
        }
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
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                            Uri start;
                                            if (!TextUtils.isEmpty(Shaft.sSettings.getRootPathUri())) {
                                                start = Uri.parse(Shaft.sSettings.getRootPathUri());
                                            } else {
                                                start = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download");
                                            }
                                            intent.putExtra(EXTRA_INITIAL_URI, start);
                                            activity.startActivityForResult(intent, BaseActivity.ASK_URI);
                                        }
                                    }).start();
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
