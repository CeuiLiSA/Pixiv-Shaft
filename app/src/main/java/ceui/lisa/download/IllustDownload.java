package ceui.lisa.download;

import android.net.Uri;
import android.text.TextUtils;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.cache.Cache;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;

import ceui.lisa.file.LegacyFile;
import ceui.lisa.file.OutPut;
import ceui.lisa.file.SAFile;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustSearchResponse;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.ImageUrlsBean;
import ceui.lisa.models.MetaPagesBean;
import ceui.lisa.models.NovelBean;
import ceui.loxia.ObjectPool;
import ceui.pixiv.ui.bulk.UgoiraEngine;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.models.NovelSeriesItem;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.download.config.DownloadItems;
import ceui.pixiv.download.model.RelativePath;

public class IllustDownload {

    private static DownloadItem buildDownloadItem(IllustsBean illust, int index) {
        return buildDownloadItem(illust, index, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    private static DownloadItem buildDownloadItem(IllustsBean illust, int index, String imageResolution) {
        if (illust.isGif()) {
            return null;
        } else if (illust.getPage_count() == 1) {
            DownloadItem item = new DownloadItem(illust, 0);
            item.setUrl(getUrl(illust, 0, imageResolution));
            item.setShowUrl(getShowUrl(illust, 0));
            return item;
        } else {
            DownloadItem item = new DownloadItem(illust, index);
            item.setUrl(getUrl(illust, index, imageResolution));
            item.setShowUrl(getShowUrl(illust, index));
            return item;
        }
    }

    public static void downloadIllustFirstPage(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> downloadIllustFirstPage(illust));
    }

    public static void downloadIllustFirstPageWithResolution(IllustsBean illust, String imageResolution, BaseActivity<?> activity) {
        check(activity, () -> {
            // ugoira 没有静态「第一页」可下:buildDownloadItem 对 gif 返回 null,直接
            // Manager.addTask(null) 会在 safeAdd 里 null.getUuid() NPE。动图统一走
            // downloadGif(zip→帧→gif 编码 + 落库)。分辨率对 gif 无意义,忽略。
            if (illust.isGif()) {
                downloadGif(illust);
                return;
            }
            if (illust.getPage_count() == 1) {
                DownloadItem item = buildDownloadItem(illust, 0, imageResolution);
                Common.showToast('1' + Shaft.getContext().getString(R.string.has_been_added));
                Manager.get().addTask(item);
            }
        });
    }

    public static void downloadIllustFirstPage(IllustsBean illust) {
        downloadIllustFirstPageWithResolution(illust, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    public static void downloadIllustFirstPageWithResolution(IllustsBean illust, String imageResolution) {
        // 同上:gif 走 downloadGif,避免 buildDownloadItem 返 null → addTask(null) NPE。
        if (illust.isGif()) {
            downloadGif(illust);
            return;
        }
        if (illust.getPage_count() == 1) {
            DownloadItem item = buildDownloadItem(illust, 0, imageResolution);
            Common.showToast('1' + Shaft.getContext().getString(R.string.has_been_added));
            Manager.get().addTask(item);
        }
    }

    public static void downloadIllustCertainPage(IllustsBean illust, int index, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                // index!=0 时不合理
                downloadIllustFirstPage(illust);
            } else {
                DownloadItem item = buildDownloadItem(illust, index);
                Common.showToast('1' + Shaft.getContext().getString(R.string.has_been_added));
                Manager.get().addTask(item);
            }
        });
    }

    public static void downloadIllustAllPages(IllustsBean illust, BaseActivity<?> activity) {
        check(activity, () -> downloadIllustAllPages(illust));
    }

    public static void downloadIllustAllPagesWithResolution(IllustsBean illust, String imageResolution, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllustFirstPage(illust, activity);
            } else {
                List<DownloadItem> tempList = new ArrayList<>();
                for (int i = 0; i < illust.getPage_count(); i++) {
                    DownloadItem item = buildDownloadItem(illust, i, imageResolution);
                    tempList.add(item);
                }
                Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
                Manager.get().addTasks(tempList);
            }
        });
    }

    public static void downloadIllustAllPages(IllustsBean illust) {
        // issue #569: 精简/网页来源的 bean(如「按 Tag 筛选」列表项)没有 meta_pages/meta_single_page,
        // 直接下载多图只会拿到封面、原图也取不到。先回 v1/illust/detail 拉完整版再下;
        // 拉取失败则降级用现有数据(已加空值兜底,不会崩)。
        if (needsFullData(illust)) {
            ensureFullThenRun(illust, IllustDownload::doDownloadAllPages);
            return;
        }
        doDownloadAllPages(illust);
    }

    private static void doDownloadAllPages(IllustsBean illust) {
        if (illust.isGif()){
            downloadGif(illust);
        } else if (illust.getPage_count() == 1) {
            downloadIllustFirstPage(illust);
        } else {
            List<DownloadItem> tempList = new ArrayList<>();
            for (int i = 0; i < illust.getPage_count(); i++) {
                DownloadItem item = buildDownloadItem(illust, i);
                tempList.add(item);
            }
            Common.showToast(tempList.size() + Shaft.getContext().getString(R.string.has_been_added));
            Manager.get().addTasks(tempList);
        }
    }

    /** 详情/下载所需的分页信息是否缺失(精简来源的 bean 会缺,需回 API 补全)。 */
    private static boolean needsFullData(IllustsBean illust) {
        if (illust == null) {
            return false;
        }
        if (illust.getPage_count() <= 1) {
            return illust.getMeta_single_page() == null
                    || TextUtils.isEmpty(illust.getMeta_single_page().getOriginal_image_url());
        }
        List<MetaPagesBean> mp = illust.getMeta_pages();
        return mp == null || mp.size() < illust.getPage_count();
    }

    /**
     * 回 v1/illust/detail 拉完整版后用完整 bean 执行 action;失败/已删则用原 bean 降级执行
     * (action 应是不再触发本守卫的「裸」下载实现,避免无限重拉)。
     */
    private static void ensureFullThenRun(IllustsBean illust, java.util.function.Consumer<IllustsBean> action) {
        Retro.getAppApi().getIllustByID(illust.getId())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<IllustSearchResponse>() {
                    @Override
                    public void success(IllustSearchResponse resp) {
                        IllustsBean fresh = resp.getIllust();
                        if (fresh != null && fresh.getId() != 0 && fresh.isVisible()) {
                            ObjectPool.INSTANCE.updateIllust(fresh);
                            action.accept(fresh);
                        } else {
                            action.accept(illust);
                        }
                    }

                    @Override
                    public void error(Throwable e) {
                        action.accept(illust);
                    }
                });
    }


    // downloadCheckedIllustAllPages 已移除：旧的 FragmentMultiDownload 勾选下载入口已废弃，
    // 现在统一通过 download_queue v33 持久化队列（见 ceui.pixiv.ui.bulk.LegacyBatchEnqueue 与 ceui.pixiv.ui.bulk.bulkEnqueueIllusts）。

    public static DownloadItem downloadGif(GifResponse response, IllustsBean illust) {
        return downloadGif(response, illust, false);
    }

    public static DownloadItem downloadGif(GifResponse response, IllustsBean illust, boolean autoSave) {
        DownloadItem item = new DownloadItem(illust, 0);
        item.setAutoSave(autoSave);
        item.setUrl((response.getUgoira_metadata().getZip_urls().getMedium()));
        item.setShowUrl(getShowUrl(illust, 0));
        Manager.get().addTask(item);
        return item;
    }

    public static void downloadGif(IllustsBean illustsBean){
        if(!illustsBean.isGif()){
            return;
        }
        // 播放引擎可能已把这张 ugoira 的帧序列落盘(用户在详情页看过)。命中就直接由帧编出
        // GIF 拷进用户存储,跳过重下 zip / 重解压 / 重补帧 —— 也避开与引擎抢写同一批缓存文件
        // 的无锁竞争。播放链路本身不再产出 GIF(改播 JPEG 帧序列,GIF 喂不动补帧的帧率),所以
        // 这里是现编;编码 + outPutGif 都是阻塞的,一起放后台线程。未命中 / 失败都回退到原始
        // 「下 zip→编码→保存」链路,行为不变。
        Schedulers.io().scheduleDirect(() -> {
            File cached = null;
            try {
                cached = UgoiraEngine.INSTANCE.encodeTempGifFromFrames(illustsBean);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            if (cached != null) {
                try {
                    OutPut.outPutGif(Shaft.getContext(), cached, illustsBean);
                    Common.showLog("[UGOIRA] downloadGif 复用播放引擎帧序列 id=" + illustsBean.getId());
                    return;
                } catch (Throwable t) {
                    t.printStackTrace(); // 复用失败 → 落到下面完整链路
                } finally {
                    // 临时 GIF 只为这次拷贝而生,拷完就删(成功/失败都删)
                    //noinspection ResultOfMethodCallIgnored
                    cached.delete();
                }
            }
            PixivOperate.getGifInfo(illustsBean, new ErrorCtrl<GifResponse>() {
                @Override
                public void next(GifResponse gifResponse) {
                    Cache.get().saveModel(Params.ILLUST_ID + "_" + illustsBean.getId(), gifResponse);
                    downloadGif(gifResponse, illustsBean, true);
                }
            });
        });
    }

    public static void downloadNovel(BaseActivity<?> activity, NovelSeriesItem novelSeriesItem, String content, Callback<Uri> targetCallback) {
        // 文件名仍按系列合集惯例（NovelSeries_<id>_Chapter_1~N_<title>.txt），
        // 但目录从用户当前的 Novel 命名预设里取——和 Kotlin 端的
        // MergeDownloadNovelSeriesTask、CrossSeriesDownloadTask 走同一规则。
        String mergeName = FileCreator.deleteSpecialWords("NovelSeries_" + novelSeriesItem.getId() + "_Chapter_1~" + novelSeriesItem.getContent_count() + "_" + novelSeriesItem.getTitle() + ".txt");
        RelativePath path = DownloadItems.novelMergeDestinationForSeriesItem(novelSeriesItem, mergeName);
        downloadNovel(activity, path, content, targetCallback);
    }

    public static String truncateTitle(String title, int maxLength) {
        if (title == null)  return " ";
        if (title.length() <= maxLength)  return title;
        if (maxLength < 3) return title.substring(0, maxLength);

        int available = maxLength - 3;
        int front = available / 2;
        int rear = available - front;

        return title.substring(0, front) + "..." + title.substring(title.length() - rear);
    }


    public static String getNovelText( String title , NovelBean novelBean, NovelDetail novelDetail) {
        String content = title +"\n\n"+
                "RawTitle:"+novelBean.getTitle()+"\n"+
                "Date:"+novelBean.getCreate_date().substring(0, 10)+" "+ "Length:"+novelBean.getText_length()+"\n"+
                "Name:"+novelBean.getUser().getName()+"(https://www.pixiv.net/users/"+novelBean.getUser().getId()+ ")\n" +
                "Source:"+"https://www.pixiv.net/novel/show.php?id="+novelBean.getId()+"\n"+
                "Tags:"+Arrays.toString(novelBean.getTagNames())+"\n"+
                "Caption:\n"+novelBean.getCaption().replaceAll("<br />", "\n")+
                "\n>---------------------<\n";
        content=content+ novelDetail.getNovel_text()+"\n\n";
        return content;
    }


    public static void downloadNovel(BaseActivity<?> activity, NovelBean novelBean, NovelDetail novelDetail, Callback<Uri> targetCallback) {
        // 文件路径完全交给用户当前的 Novel 命名预设——和 Kotlin 端的
        // DownloadNovelTask、ReaderV3 导出走同一规则；旧版写死的
        // "Novel_<id>_<title>.txt" 已经废弃。Reader 标题里加系列前缀的视觉
        // 习惯保留在 getNovelText 里（正文头部仍带系列名），文件名不再二次拼接。
        RelativePath path = DownloadItems.novelDestinationFromBean(novelBean);
        String content = getNovelText(buildContentTitle(novelBean), novelBean, novelDetail);
        downloadNovel(activity, path, content, targetCallback);
    }

    /**
     * 仅用于内容头部展示的标题——保留旧版「系列名_章节名」格式；不参与
     * 文件命名（文件命名走预设模板，模板自己有 {title} 占位符）。
     */
    private static String buildContentTitle(NovelBean novelBean) {
        String title = novelBean.getTitle();
        if (novelBean.getSeries() != null && novelBean.getSeries().getTitle() != null) {
            title = novelBean.getSeries().getTitle() + "_" + title;
        }
        return truncateTitle(title, 58);
    }

    /**
     * Inner save: write [content] to a temp file (for FileProvider sharing /
     * "open with" intents) and copy it into MediaStore at [path], which is
     * the user's Novel-bucket path rendered through the active naming
     * preset. The temp filename keeps using [RelativePath.getFilename] —
     * not because MediaStore needs it (MediaStore reads from `path`), but
     * to keep FileProvider URIs human-readable for the share callback.
     */
    public static void downloadNovel(BaseActivity<?> activity, RelativePath path, String content, Callback<Uri> targetCallback) {
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = LegacyFile.textFile(activity, path.getFilename());
                try {
                    OutputStream outStream = new FileOutputStream(textFile);
                    outStream.write(content.getBytes());
                    outStream.close();
                    Common.showLog("downloadNovel path " + path.joinTo("/"));
                    OutPut.outPutNovel(activity, textFile, path);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static void downloadFile(BaseActivity<?> activity, String displayName, String content, Callback<Uri> targetCallback) {
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = LegacyFile.textFile(activity, displayName);
                try {
                    OutputStream outStream = new FileOutputStream(textFile);
                    outStream.write(content.getBytes());
                    outStream.close();
                    Common.showLog("downloadFile displayName " + textFile.getName());
                    OutPut.outPutFile(activity, textFile, textFile.getName());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static void downloadBackupFile(BaseActivity<?> activity, String displayName, String content, Callback<Uri> targetCallback){
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = LegacyFile.textFile(activity, displayName);
                try {
                    OutputStream outStream = new FileOutputStream(textFile);
                    outStream.write(content.getBytes());
                    outStream.close();
                    Common.showLog("downloadBackupFile displayName " + textFile.getName());
                    OutPut.outPutBackupFile(activity, textFile, textFile.getName());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static void downloadBackupFile(BaseActivity<?> activity, String displayName, Callback<File> fileWriter, Callback<Uri> targetCallback){
        check(activity, new FeedBack() {
            @Override
            public void doSomething() {
                File textFile = LegacyFile.textFile(activity, displayName);
                try {
                    fileWriter.doSomething(textFile);
                    Common.showLog("downloadBackupFile displayName " + textFile.getName());
                    OutPut.outPutBackupFile(activity, textFile, textFile.getName());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    targetCallback.doSomething(fileURI);
                }
            }
        });
    }

    public static String getUrl(IllustsBean illust, int index) {
        return getUrl(illust, index, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    public static String getUrl(IllustsBean illust, int index, String imageResolution) {
        return (getImageUrlByResolution(illust, index, imageResolution));
    }

    private static String getImageUrlByResolution(IllustsBean illust, int index, String imageResolution) {
        ImageUrlsBean imageUrlsBean = getImageUrlsBean(illust, index, imageResolution);
        switch (imageResolution) {
            case Params.IMAGE_RESOLUTION_ORIGINAL:
                return imageUrlsBean.getOriginal();
            case Params.IMAGE_RESOLUTION_LARGE:
                return imageUrlsBean.getLarge();
            case Params.IMAGE_RESOLUTION_MEDIUM:
                return imageUrlsBean.getMedium();
            case Params.IMAGE_RESOLUTION_SQUARE_MEDIUM:
                return imageUrlsBean.getSquare_medium();
            default:
                return imageUrlsBean.getMaxImage();
        }
    }

    private static ImageUrlsBean getImageUrlsBean(IllustsBean illust, int index, String imageResolution) {
        if (illust.getPage_count() == 1) {
            if (imageResolution.equals(Params.IMAGE_RESOLUTION_ORIGINAL)) {
                // 精简/网页来源缺 meta_single_page → 降级到 image_urls,避免 NPE(issue #569)。
                // 正常情况下载前会先 ensureFullThenRun 拉完整版,这里只是最后兜底。
                return illust.getMeta_single_page() != null ? illust.getMeta_single_page() : illust.getImage_urls();
            }
            return illust.getImage_urls();
        } else {
            List<MetaPagesBean> mp = illust.getMeta_pages();
            if (mp == null || index < 0 || index >= mp.size()) {
                // 多图但无 meta_pages(精简/网页来源)→ 降级到封面 image_urls,避免 NPE(issue #569)
                return illust.getImage_urls();
            }
            return mp.get(index).getImage_urls();
        }
    }

    public static String getShowUrl(IllustsBean illust, int index) {
        // 下载管理列表只显示 64dp 缩略图,square_medium (~360px) 比 medium (~540px)
        // 体积小一截,且本身就是方形裁切,跟下载卡片的方形 thumb 视觉吻合。
        // square_medium 缺时按 medium → large 兜底。
        ImageUrlsBean urls;
        if (illust.getPage_count() == 1) {
            urls = illust.getImage_urls();
        } else {
            List<MetaPagesBean> mp = illust.getMeta_pages();
            urls = (mp == null || index < 0 || index >= mp.size())
                    ? illust.getImage_urls()
                    : mp.get(index).getImage_urls();
        }
        if (urls == null) return null;
        if (!TextUtils.isEmpty(urls.getSquare_medium())) return urls.getSquare_medium();
        if (!TextUtils.isEmpty(urls.getMedium())) return urls.getMedium();
        return urls.getLarge();
    }

    public static void check(BaseActivity<?> activity, FeedBack feedBack) {
        if (Shaft.sSettings.getDownloadWay() == 1) {
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
                                    dialog.dismiss();
                                    BaseActivity.launchSafTreePicker(activity);
                                })
                        .show();
            } else {
                DocumentFile root = SAFile.rootFolder(activity);
                if (root == null || !root.exists() || !root.isDirectory()) {
                    activity.setFeedBack(feedBack);
                    new QMUIDialog.MessageDialogBuilder(activity)
                            .setTitle(activity.getResources().getString(R.string.string_143))
                            .setMessage(activity.getResources().getString(R.string.string_365))
                            .setSkinManager(QMUISkinManager.defaultInstance(activity))
                            .addAction(0, activity.getResources().getString(R.string.string_142),
                                    QMUIDialogAction.ACTION_PROP_NEGATIVE,
                                    (dialog, index) -> dialog.dismiss())
                            .addAction(0, activity.getResources().getString(R.string.string_366),
                                    (dialog, index) -> {
                                        dialog.dismiss();
                                        BaseActivity.launchSafTreePicker(activity);
                                    })
                            .show();
                } else {
                    if (feedBack != null) {
                        try {
                            feedBack.doSomething();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } else {
            if (feedBack != null) {
                try {
                    feedBack.doSomething();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
