package ceui.lisa.download;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;
import ceui.lisa.core.SAFile;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class IllustDownload {

    public static void downloadIllust(IllustsBean illust, Context context) {
        if (illust == null) {
            Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
            return;
        }

        if (illust.getPage_count() != 1) {
            Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
            return;
        }

        File file = FileCreator.createIllustFile(illust);
        if (file.exists()) {
            Common.showToast(Shaft.getContext().getString(R.string.image_alredy_exist));
            return;
        }

        if (illust.getPage_count() == 1) {
            DownloadItem item = new DownloadItem(illust);
            item.setUrl(illust.getMeta_single_page().getOriginal_image_url());
            item.setUri(SAFile.getDocument(context, illust, 0).getUri());
            item.setName(file.getName());
            Manager.get().addTask(item);
            Manager.get().start(context);
        }
        Common.showToast(Shaft.getContext().getString(R.string.one_item_added));
    }


    public static void downloadIllust(IllustsBean illust, int index, Context context) {
        if (illust == null) {
            Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
            return;
        }

        File file = FileCreator.createIllustFile(illust, index);
        if (file.exists()) {
            Common.showToast(Shaft.getContext().getString(R.string.image_alredy_exist));
            return;
        }

        if (illust.getPage_count() == 1) {
            downloadIllust(illust, context);
        } else {
            DownloadItem item = new DownloadItem(illust);
            item.setUrl(illust.getMeta_pages().get(index).getImage_urls().getOriginal());
            item.setUri(SAFile.getDocument(context, illust, index).getUri());
            item.setName(file.getName());
            Manager.get().addTask(item);
            Manager.get().start(context);
            Common.showToast(Shaft.getContext().getString(R.string.one_item_added));
        }
    }


    public static void downloadAllIllust(IllustsBean illust, Context context) {
        if (illust == null) {
            Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
            return;
        }

        if (illust.getPage_count() == 1) {
            downloadIllust(illust, context);
            return;
        }


        List<DownloadItem> tempList = new ArrayList<>();

        for (int i = 0; i < illust.getPage_count(); i++) {
            File file = FileCreator.createIllustFile(illust, i);
            if (!file.exists()) {
                DownloadItem item = new DownloadItem(illust);
                item.setUrl("https://pixiv.cat/" + illust.getId() + "-" + (i+1) + ".jpg");
                item.setUri(SAFile.getDocument(context, illust, i).getUri());
                item.setName(file.getName());
                tempList.add(item);
            }
        }

        Manager.get().addTasks(tempList);
        Manager.get().start(context);
        Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
    }


    public static void downloadAllIllust(List<IllustsBean> beans, Context context) {
        if (Common.isEmpty(beans)) {
            Common.showToast(Shaft.getContext().getString(R.string.cannot_download));
            return;
        }

        if (beans.size() == 1) {
            downloadAllIllust(beans.get(0), context);
            return;
        }


        List<DownloadItem> tempList = new ArrayList<>();

        for (int i = 0; i < beans.size(); i++) {
            if (beans.get(i).isChecked()) {
                final IllustsBean illust = beans.get(i);

                if (illust.getPage_count() == 1) {
                    File file = FileCreator.createIllustFile(illust);
                    if (!file.exists()) {
                        DownloadItem item = new DownloadItem(illust);
                        item.setUrl(illust.getMeta_single_page().getOriginal_image_url());
                        item.setUri(SAFile.getDocument(context, illust, 0).getUri());
                        item.setName(file.getName());
                        tempList.add(item);
                    }
                } else {
                    for (int j = 0; j < illust.getPage_count(); j++) {

                        File file = FileCreator.createIllustFile(illust, j);
                        if (!file.exists()) {
                            DownloadItem item = new DownloadItem(illust);
                            item.setUrl(illust.getMeta_pages().get(j).getImage_urls().getOriginal());
                            item.setUri(SAFile.getDocument(context, illust, j).getUri());
                            item.setName(file.getName());
                            tempList.add(item);
                        }
                    }
                }
            }
        }
        Manager.get().addTasks(tempList);
        Manager.get().start(context);
        Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
    }

    public static void downloadGif(GifResponse response, IllustsBean illust, Context context) {
        File file = FileCreator.createGifZipFile(illust);
        DownloadItem item = new DownloadItem(illust);
        item.setUrl(response.getUgoira_metadata().getZip_urls().getMedium());
        item.setUri(SAFile.getDocument(context, illust, 1).getUri());
        item.setName(file.getName());
        Manager.get().addTask(item);
        Manager.get().start(context);
        Common.showToast("图组ZIP已加入下载队列");
    }
}
