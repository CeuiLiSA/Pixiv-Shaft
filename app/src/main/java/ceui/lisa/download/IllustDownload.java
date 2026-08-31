package ceui.lisa.download;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.cache.Cache;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;

import ceui.lisa.file.LegacyFile;
import ceui.lisa.file.OutPut;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.core.JavaAsync;
import ceui.lisa.models.GifResponse;
import ceui.loxia.Illust;
import ceui.loxia.ImageUrls;
import ceui.loxia.MetaPage;
import ceui.loxia.ObjectPool;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.IllustCaptionExporter;
import ceui.pixiv.download.config.StorageChoice;
import ceui.pixiv.ui.bulk.UgoiraEngine;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.PixivOps;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IllustDownload {

    private static DownloadItem buildDownloadItem(Illust illust, int index) {
        return buildDownloadItem(illust, index, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    private static DownloadItem buildDownloadItem(Illust illust, int index, String imageResolution) {
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

    public static void downloadIllustFirstPage(Illust illust, BaseActivity<?> activity) {
        check(activity, () -> downloadIllustFirstPage(illust));
    }

    public static void downloadIllustFirstPageWithResolution(Illust illust, String imageResolution, BaseActivity<?> activity) {
        check(activity, () -> {
            // ugoira 没有静态「第一页」可下:buildDownloadItem 对 gif 返回 null,直接
            // Manager.addTask(null) 会在 safeAdd 里 null.getUuid() NPE。动图统一走
            // downloadGif(zip→帧→gif 编码 + 落库)。分辨率对 gif 无意义,忽略。
            if (illust.isGif()) {
                downloadGif(illust);
                return;
            }
            if (illust.getPage_count() == 1) {
                IllustCaptionExporter.export(illust);
                DownloadItem item = buildDownloadItem(illust, 0, imageResolution);
                Common.showToast('1' + Shaft.getContext().getString(R.string.has_been_added));
                Manager.get().addTask(item);
            }
        });
    }

    public static void downloadIllustFirstPage(Illust illust) {
        downloadIllustFirstPageWithResolution(illust, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    public static void downloadIllustFirstPageWithResolution(Illust illust, String imageResolution) {
        // 同上:gif 走 downloadGif,避免 buildDownloadItem 返 null → addTask(null) NPE。
        if (illust.isGif()) {
            downloadGif(illust);
            return;
        }
        if (illust.getPage_count() == 1) {
            IllustCaptionExporter.export(illust);
            DownloadItem item = buildDownloadItem(illust, 0, imageResolution);
            Common.showToast('1' + Shaft.getContext().getString(R.string.has_been_added));
            Manager.get().addTask(item);
        }
    }

    public static void downloadIllustCertainPage(Illust illust, int index, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                // index!=0 时不合理
                downloadIllustFirstPage(illust);
            } else {
                IllustCaptionExporter.export(illust);
                DownloadItem item = buildDownloadItem(illust, index);
                Common.showToast('1' + Shaft.getContext().getString(R.string.has_been_added));
                Manager.get().addTask(item);
            }
        });
    }

    public static void downloadIllustAllPages(Illust illust, BaseActivity<?> activity) {
        check(activity, () -> downloadIllustAllPages(illust));
    }

    public static void downloadIllustAllPagesWithResolution(Illust illust, String imageResolution, BaseActivity<?> activity) {
        check(activity, () -> {
            if (illust.getPage_count() == 1) {
                downloadIllustFirstPage(illust, activity);
            } else {
                IllustCaptionExporter.export(illust);
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

    public static void downloadIllustAllPages(Illust illust) {
        // issue #569: 精简/网页来源的 bean(如「按 Tag 筛选」列表项)没有 meta_pages/meta_single_page,
        // 直接下载多图只会拿到封面、原图也取不到。先回 v1/illust/detail 拉完整版再下;
        // 拉取失败则降级用现有数据(已加空值兜底,不会崩)。
        if (needsFullData(illust)) {
            ensureFullThenRun(illust, IllustDownload::doDownloadAllPages);
            return;
        }
        doDownloadAllPages(illust);
    }

    private static void doDownloadAllPages(Illust illust) {
        if (illust.isGif()){
            downloadGif(illust);
        } else if (illust.getPage_count() == 1) {
            downloadIllustFirstPage(illust);
        } else {
            IllustCaptionExporter.export(illust);
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
    private static boolean needsFullData(Illust illust) {
        if (illust == null) {
            return false;
        }
        if (illust.getPage_count() <= 1) {
            return illust.getMeta_single_page() == null
                    || TextUtils.isEmpty(illust.getMeta_single_page().getOriginal_image_url());
        }
        List<MetaPage> mp = illust.getMeta_pages();
        return mp == null || mp.size() < illust.getPage_count();
    }

    /**
     * 回 v1/illust/detail 拉完整版后用完整 bean 执行 action;失败/已删则用原 bean 降级执行
     * (action 应是不再触发本守卫的「裸」下载实现,避免无限重拉)。
     */
    private static void ensureFullThenRun(Illust illust, java.util.function.Consumer<Illust> action) {
        // 失败静默降级（不弹 toast，对齐旧实现覆盖 error 不调 super 的行为）。
        PixivOps.getIllustByID(illust.getId(), resp -> {
            Illust fresh = resp.getIllust();
            if (fresh != null && fresh.getId() != 0 && Boolean.TRUE.equals(fresh.getVisible())) {
                ObjectPool.INSTANCE.updateIllust(fresh);
                action.accept(fresh);
            } else {
                action.accept(illust);
            }
        }, e -> action.accept(illust));
    }


    // downloadCheckedIllustAllPages 已移除：旧的 FragmentMultiDownload 勾选下载入口已废弃，
    // 现在统一通过 download_queue v33 持久化队列（见 ceui.pixiv.ui.bulk.LegacyBatchEnqueue 与 ceui.pixiv.ui.bulk.bulkEnqueueIllusts）。

    public static DownloadItem downloadGif(GifResponse response, Illust illust) {
        return downloadGif(response, illust, false);
    }

    public static DownloadItem downloadGif(GifResponse response, Illust illust, boolean autoSave) {
        DownloadItem item = new DownloadItem(illust, 0);
        item.setAutoSave(autoSave);
        item.setUrl((response.getUgoira_metadata().getZip_urls().getMedium()));
        item.setShowUrl(getShowUrl(illust, 0));
        Manager.get().addTask(item);
        return item;
    }

    public static void downloadGif(Illust illustsBean){
        if(!illustsBean.isGif()){
            return;
        }
        // 播放引擎可能已把这张 ugoira 的帧序列落盘(用户在详情页看过)。命中就直接由帧出片
        // 拷进用户存储,跳过重下 zip / 重解压 / 重补帧 —— 也避开与引擎抢写同一批缓存文件的
        // 无锁竞争。格式随「动图保存格式」设置:mp4 时播放缓存里那份直接就能用(纯拷贝),
        // GIF 时现编。出片 + 拷贝都是阻塞的,一起放后台线程。未命中 / 失败都回退到原始
        // 「下 zip→编码→保存」链路,行为不变。
        JavaAsync.fireAndForget(() -> {
            UgoiraEngine.UgoiraExport export = null;
            try {
                // 设置成 mp4 时:帧不在盘上就把整条播放 pipeline 跑完再出片 —— 下面那条老链路
                // (getGifInfo → encodeGifV2)只会产 GIF,不这么做,没看过的动图会拿到 GIF。
                export = Shaft.sSettings.isUgoiraSaveAsMp4()
                        ? UgoiraEngine.INSTANCE.prepareAndExportForSave(illustsBean)
                        : UgoiraEngine.INSTANCE.exportForSave(illustsBean);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            if (export != null) {
                try {
                    OutPut.outPutUgoira(Shaft.getContext(), export.getFile(), illustsBean, export.isVideo());
                    Common.showLog("[UGOIRA] downloadGif 复用播放引擎帧序列 id=" + illustsBean.getId()
                            + " video=" + export.isVideo());
                    return;
                } catch (Throwable t) {
                    t.printStackTrace(); // 复用失败 → 落到下面完整链路
                } finally {
                    // 只删「为这次保存而生」的临时文件;mp4 那份是播放缓存,删了下次还得重压
                    if (export.getTemporary()) {
                        //noinspection ResultOfMethodCallIgnored
                        export.getFile().delete();
                    }
                }
            }
            PixivOperate.getGifInfo(illustsBean, gifResponse -> {
                Cache.get().saveModel(Params.ILLUST_ID + "_" + illustsBean.getId(), gifResponse);
                downloadGif(gifResponse, illustsBean, true);
            });
        });
    }

    /**
     * 备份导出专用的单线程 executor（替代 Rx {@code Schedulers.single()}）：同名临时文件是共享的，
     * 必须全局串行，见 {@link #downloadBackupFile(BaseActivity, String, Callback, Callback)}。
     */
    private static final ExecutorService BACKUP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shaft-backup-export");
        t.setDaemon(true);
        return t;
    });

    public static void downloadBackupFile(BaseActivity<?> activity, String displayName, String content, Callback<Uri> targetCallback){
        downloadBackupFile(activity, displayName, textFile -> {
            try (OutputStream outStream = new FileOutputStream(textFile)) {
                outStream.write(content.getBytes());
            } catch (IOException e) {
                // 抛回统一的落盘链路兜外层 catch,跳过 MediaStore 复制
                throw new RuntimeException(e);
            }
        }, targetCallback);
    }

    /**
     * 文件写出 + MediaStore 复制都在工作线程执行(点击回调里同步写大文件会
     * ANR,见 #981),targetCallback 回主线程,fileWriter 里可以放心读库/序列化。
     * 用单线程 executor 而不是共享 IO 池:同名临时文件是共享的,导出进行中用户再点一次
     * 备份,并发会两个任务同时 truncate + 交错写同一个文件,产出损坏的备份;
     * {@link #BACKUP_EXECUTOR} 全局单线程,天然串行。
     */
    public static void downloadBackupFile(BaseActivity<?> activity, String displayName, Callback<File> fileWriter, Callback<Uri> targetCallback){
        // 外层 try/catch 与回调处的 try/catch 都是在保持旧行为:旧实现里整个
        // feedback(含 getUriForFile 和 targetCallback)都跑在 check() 自带的
        // try/catch 里,异步化后不能让这些异常变成工作线程未捕获异常 / 主线程崩溃
        check(activity, () -> BACKUP_EXECUTOR.execute(() -> {
            try {
                File textFile = LegacyFile.textFile(activity, displayName);
                boolean written = false;
                try {
                    fileWriter.doSomething(textFile);
                    Common.showLog("downloadBackupFile displayName " + textFile.getName());
                    written = OutPut.outPutBackupFile(activity, textFile, displayName);
                } catch (Exception e) {
                    // 序列化 / 写临时文件本身失败:OutPut 没机会报错,这里补一条
                    e.printStackTrace();
                    Common.showToast(Shaft.getContext().getString(R.string.save_backup_failed,
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
                if (!written) {
                    // 没写进用户存储就不回调 targetCallback —— 否则调用方会 toast「备份成功」
                    // (真机复现:全部恢复默认后备份落到相册卷被 MediaStore 拒掉,却仍提示成功)
                    return;
                }
                Uri fileURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider", textFile);
                if (targetCallback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            targetCallback.doSomething(fileURI);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));
    }

    public static String getUrl(Illust illust, int index) {
        return getUrl(illust, index, Params.IMAGE_RESOLUTION_ORIGINAL);
    }

    public static String getUrl(Illust illust, int index, String imageResolution) {
        return getImageUrlByResolution(illust, index, imageResolution);
    }

    @Nullable
    private static String getImageUrlByResolution(Illust illust, int index, String imageResolution) {
        ImageUrls imageUrls = getImageUrls(illust, index);
        // A single-page API response normally carries the original URL in meta_single_page, but
        // restricted/deleted/web-derived records may contain the object with a null field. Do not
        // return that null early: fall through to image_urls and finally the best lower resolution.
        String metaOriginal = illust.getPage_count() == 1 && illust.getMeta_single_page() != null
                ? illust.getMeta_single_page().getOriginal_image_url()
                : null;
        String original = firstUsableUrl(
                metaOriginal,
                imageUrls != null ? imageUrls.getOriginal() : null
        );

        if (imageUrls == null) {
            return original;
        }
        switch (imageResolution) {
            case Params.IMAGE_RESOLUTION_ORIGINAL:
                return firstUsableUrl(original, imageUrls.getLarge(), imageUrls.getMedium(),
                        imageUrls.getSquare_medium(), imageUrls.getUrl(), imageUrls.getSmall());
            case Params.IMAGE_RESOLUTION_LARGE:
                return firstUsableUrl(imageUrls.getLarge(), imageUrls.getMedium(), original,
                        imageUrls.getSquare_medium(), imageUrls.getUrl(), imageUrls.getSmall());
            case Params.IMAGE_RESOLUTION_MEDIUM:
                return firstUsableUrl(imageUrls.getMedium(), imageUrls.getLarge(), original,
                        imageUrls.getSquare_medium(), imageUrls.getUrl(), imageUrls.getSmall());
            case Params.IMAGE_RESOLUTION_SQUARE_MEDIUM:
                return firstUsableUrl(imageUrls.getSquare_medium(), imageUrls.getMedium(),
                        imageUrls.getLarge(), original, imageUrls.getUrl(), imageUrls.getSmall());
            default:
                return firstUsableUrl(imageUrls.getUrl(), original, imageUrls.getLarge(),
                        imageUrls.getMedium(), imageUrls.getSquare_medium(), imageUrls.getSmall());
        }
    }

    @Nullable
    private static ImageUrls getImageUrls(Illust illust, int index) {
        if (illust.getPage_count() == 1) {
            return illust.getImage_urls();
        } else {
            List<MetaPage> mp = illust.getMeta_pages();
            if (mp == null || index < 0 || index >= mp.size()) {
                // 多图但无 meta_pages(精简/网页来源)→ 降级到封面 image_urls,避免 NPE(issue #569)
                return illust.getImage_urls();
            }
            MetaPage page = mp.get(index);
            return page != null && page.getImage_urls() != null
                    ? page.getImage_urls()
                    : illust.getImage_urls();
        }
    }

    @Nullable
    private static String firstUsableUrl(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    public static String getShowUrl(Illust illust, int index) {
        // 下载管理列表只显示 64dp 缩略图,square_medium (~360px) 比 medium (~540px)
        // 体积小一截,且本身就是方形裁切,跟下载卡片的方形 thumb 视觉吻合。
        // square_medium 缺时按 medium → large 兜底。
        ImageUrls urls;
        if (illust.getPage_count() == 1) {
            urls = illust.getImage_urls();
        } else {
            List<MetaPage> mp = illust.getMeta_pages();
            urls = (mp == null || index < 0 || index >= mp.size())
                    ? illust.getImage_urls()
                    : mp.get(index).getImage_urls();
        }
        if (urls == null) return null;
        if (!TextUtils.isEmpty(urls.getSquare_medium())) return urls.getSquare_medium();
        if (!TextUtils.isEmpty(urls.getMedium())) return urls.getMedium();
        return urls.getLarge();
    }

    /**
     * 下载前的 SAF 可用性闸门。只认 V3 下载配置（DownloadsRegistry）：实际写盘的
     * 两个 factory 早已只走下载 facade，遗留 Settings.downloadWay/rootPathUri 是
     * 另一套状态——还原异机备份会把它写成一棵本机无授权的死树，而 V3 侧校验后
     * 已回落本地存储，旧闸门就会在明明要写本地目录时错误弹「已授权的下载目录
     * 不存在」并拦下备份/下载（#984）。
     */
    public static void check(BaseActivity<?> activity, FeedBack feedBack) {
        StorageChoice storage = DownloadsRegistry.currentImagesStorage();
        if (storage instanceof StorageChoice.Saf) {
            DocumentFile root = null;
            try {
                root = DocumentFile.fromTreeUri(activity, ((StorageChoice.Saf) storage).getTreeUri());
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (root == null || !root.exists() || !root.isDirectory()) {
                activity.setFeedBack(feedBack);
                new WitDialog.MessageDialogBuilder(activity)
                        .setTitle(activity.getResources().getString(R.string.string_143))
                        .setMessage(activity.getResources().getString(R.string.string_365))
                        .addAction(0, activity.getResources().getString(R.string.string_142),
                                WitDialogAction.ACTION_PROP_NEGATIVE,
                                (dialog, index) -> dialog.dismiss())
                        .addAction(0, activity.getResources().getString(R.string.string_366),
                                (dialog, index) -> {
                                    dialog.dismiss();
                                    BaseActivity.launchSafTreePicker(activity);
                                })
                        .show();
                return;
            }
        }
        if (feedBack != null) {
            try {
                feedBack.doSomething();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
