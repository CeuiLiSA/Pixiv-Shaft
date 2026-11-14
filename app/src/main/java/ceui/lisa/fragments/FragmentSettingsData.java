package ceui.lisa.fragments;

import static android.app.Activity.RESULT_OK;
import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.OperationCanceledException;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import androidx.annotation.NonNull;
import timber.log.Timber;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.blankj.utilcode.util.FileUtils;
import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;
import ceui.pixiv.witstudio.dialog.WitDialogView;
import ceui.pixiv.witstudio.theme.V3Palette;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.FragmentSettingsDataBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.interfaces.Callback;
import ceui.pixiv.api.model.AccountResponse;
import ceui.lisa.utils.BackupUtils;
import ceui.lisa.utils.BackupUtils.BackupEntity;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.Settings;
import ceui.pixiv.ui.settings.MoonSync;
import ceui.pixiv.ui.navigation.TemplateRoute;
import ceui.pixiv.webdav.WebDavLastRun;
import ceui.pixiv.webdav.WebDavPrefs;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.config.DownloadItems;
import ceui.pixiv.download.model.RelativePath;
import ceui.pixiv.cache.ImageCacheQuota;
import ceui.pixiv.cache.OriginalImageCacheQuota;
import ceui.pixiv.cache.OriginalImageCacheManager;
import ceui.pixiv.cache.ImageCacheQuotaState;
import ceui.pixiv.session.SessionManager;
import ceui.pixiv.ui.bulk.UgoiraEngine;
import ceui.pixiv.ui.upscale.AiResultStorage;
import ceui.pixiv.ui.upscale.AiTaskQueue;
import ceui.pixiv.ui.upscale.IllustAiStore;
import ceui.pixiv.ui.translate.MangaBatchTranslateCenter;
import ceui.pixiv.services.ServicesProvider;

/** 设置 · 备份与缓存 */
public class FragmentSettingsData extends SettingsPageFragment<FragmentSettingsDataBinding> {

    /** 设置备份的名字：临时文件名，同时是 Backup 桶模板里的 {title}。 */
    private static final String BACKUP_FILE_NAME = "Shaft-Backup.json";

    /**
     * 「清除批量下载关联数据」是否正在跑。
     *
     * 只在主线程读写。旧实现没有这道闸，连点几次会叠出多条 wipe 线程 —— 每条都在收尾做
     * `wal_checkpoint` + `VACUUM`（独占锁），叠在一起会把库连接池锁死，全 App 读库的界面
     * 一起空白，只能重启恢复。
     */
    private boolean bulkWipeRunning;
    private CancellationSignal bulkSizeSignal;
    private CancellationSignal manageDialogSignal;
    private ceui.pixiv.witstudio.dialog.WitTipDialog manageLoadingDialog;
    private boolean aiResultsBusy;
    private int aiStatsGeneration;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_data;
    }

    @NonNull
    @Override
    protected FragmentSettingsDataBinding bindView(@NonNull View root) {
        return FragmentSettingsDataBinding.bind(root);
    }

    @Override
    protected void initData() {
        // 备份与还原
        baseBind.backupRela.setOnClickListener(v -> {
            WitDialog.CheckBoxMessageDialogBuilder builder = new WitDialog.CheckBoxMessageDialogBuilder(getActivity());
            builder
                    .setTitle(getString(R.string.string_420))
                    .setMessage(getString(R.string.string_423))
                    .addAction(getString(R.string.string_187), new WitDialogAction.ActionListener() {
                        @Override
                        public void onClick(WitDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    .addAction(R.string.sure, new WitDialogAction.ActionListener() {
                        @Override
                        public void onClick(WitDialog dialog, int index) {
                            final boolean backupViewHistory = builder.isChecked();
                            // 走 fileWriter 流式导出:读库 + 序列化 + 落盘全在 IO 线程逐批直写文件,
                            // 不再在主线程把整张历史表 toJson 成巨型 String(大历史库 OOM/ANR,#981)。
                            IllustDownload.downloadBackupFile((BaseActivity<?>) mActivity, BACKUP_FILE_NAME, (Callback<File>) textFile -> {
                                try {
                                    BackupUtils.writeBackupToFile(mContext, backupViewHistory, textFile);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }, new Callback<Uri>() {
                                @Override
                                public void doSomething(Uri t) {
                                    // mContext.getString:回调是异步回主线程的,fragment 可能已 detach
                                    Common.showToast(mContext.getString(R.string.backup_success) + backupTargetFolder());
                                }
                            });
                            dialog.dismiss();
                        }
                    })
                    .create()
                    .show();
        });

        baseBind.restoreRela.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);//必须
            intent.setType("*/*");//必须
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Uri backupFileUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download");
                intent.putExtra(EXTRA_INITIAL_URI, backupFileUri);
            }
            startActivityForResult(intent, Params.REQUEST_CODE_CHOOSE);
        });

        // WebDAV 备份 —— 备份到用户自己的网盘 / NAS，多设备之间同步设置、屏蔽与浏览记录
        baseBind.webdavRela.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEBDAV_SETTINGS.key);
            startActivity(intent);
        });

        // 上传配置到云端 (moonAPI)
        baseBind.moonUploadRela.setOnClickListener(v -> {
            long uid = SessionManager.INSTANCE.getLoggedInUid();
            if (uid <= 0L) {
                Common.showToast(getString(R.string.moon_login_required));
                return;
            }
            Integer appliedVer = Shaft.sSettings.getMoonAppliedVersions()
                    .get(String.valueOf(uid));
            String currentVer = (appliedVer != null && appliedVer > 0)
                    ? getString(R.string.moon_upload_current_version, appliedVer)
                    : "";
            new WitDialog.MessageDialogBuilder(getActivity())
                    .setTitle(R.string.moon_upload_title)
                    .setMessage(getString(R.string.moon_upload_message) + currentVer)
                    .addAction(getString(R.string.string_187), new WitDialogAction.ActionListener() {
                        @Override
                        public void onClick(WitDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    .addAction(R.string.sure, new WitDialogAction.ActionListener() {
                        @Override
                        public void onClick(WitDialog dialog, int index) {
                            dialog.dismiss();
                            MoonSync.uploadToCloud(mActivity, uid);
                        }
                    })
                    .create()
                    .show();
        });

        // 从云端同步配置 (moonAPI)
        baseBind.moonSyncRela.setOnClickListener(v -> {
            long uid = SessionManager.INSTANCE.getLoggedInUid();
            if (uid <= 0L) {
                Common.showToast(getString(R.string.moon_login_required));
                return;
            }
            MoonSync.manualSyncFromCloud(mActivity, uid);
        });

        // 缓存
        loadCacheSizeAsync(baseBind.imageCacheSize, LegacyFile.imageCacheFolder(mContext));
        baseBind.clearImageCache.setOnClickListener(v -> {
            FileUtils.deleteAllInDir(LegacyFile.imageCacheFolder(mContext));
            Common.showToast(getString(R.string.success_clearImageCache));
            loadCacheSizeAsync(baseBind.imageCacheSize, LegacyFile.imageCacheFolder(mContext));
        });

        loadCacheSizeAsync(baseBind.originalCacheSize, LegacyFile.originalImageCacheFolder(mContext));
        baseBind.clearOriginalCache.setOnClickListener(v -> {
            final File originalCache = LegacyFile.originalImageCacheFolder(mContext);
            final TextView sizeText = baseBind.originalCacheSize;
            sizeText.setText("…");
            new Thread(() -> {
                OriginalImageCacheManager.clearAll(mContext);
                sizeText.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    Common.showToast(getString(R.string.success_clear_original_cache), 2);
                    loadCacheSizeAsync(sizeText, originalCache);
                });
            }, "orig-cache-clear").start();
        });

        loadCacheSizeAsync(baseBind.gifCacheSize, LegacyFile.gifCacheFolder(mContext));
        baseBind.clearGifCache.setOnClickListener(v -> {
            // 删除走子线程:gif 缓存一条动图就是 zip + 上百张解压帧 + 成品 gif 三份,
            // 重度用户上 GB,在点击回调里同步 deleteAllInDir 会直接 ANR。
            final File gifCache = LegacyFile.gifCacheFolder(mContext);
            final TextView sizeText = baseBind.gifCacheSize;
            sizeText.setText("…");
            new Thread(() -> {
                FileUtils.deleteAllInDir(gifCache);
                // 播放引擎的内存表还指着刚被删掉的文件。不清的话回详情页会命中
                // peekReadyInMemory → Glide 加载失败 → 用户看到一个莫名其妙的「重试」。
                UgoiraEngine.invalidateAll();
                sizeText.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    Common.showToast(getString(R.string.success_clearGifCache), 2);
                    loadCacheSizeAsync(sizeText, gifCache);
                });
            }, "gif-cache-clear").start();
        });

        // 清除批量下载关联数据 —— illust_download_table / download_queue 两张表
        // 的 illustGson 列是重度用户存储占用的大头 (单条 10–30 KB,几万行就上 GB);
        // 加这一行让用户能把"下载管理"reset 回初始状态。落盘的图不会被删,
        // 已下载的内容仍能在系统相册看到。
        loadBulkDownloadCacheSizeAsync(baseBind.bulkDownloadCacheSize);
        baseBind.clearBulkDownloadCache.setOnClickListener(v -> showManageBulkDownloadDialog());
        baseBind.clearAiResults.setOnClickListener(v -> showManageAiResultsDialog());

        // 图片缓存「预期上限」（issue #1120）。只写设置：Glide 的 maxSize 只在初始化时读一次，
        // 改完必须重启 App 才生效（与图片加速代理同款限制），所以提示重启而不是「设置成功」。
        refreshImageCacheQuotaLabel();
        baseBind.imageCacheQuotaRela.setOnClickListener(v -> showImageCacheQuotaDialog());
        refreshOriginalCacheQuotaLabel();
        baseBind.originalCacheQuotaRela.setOnClickListener(v -> showOriginalCacheQuotaDialog());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshWebDavLabel();
        loadAiResultsSizeAsync();
    }

    /** WebDAV 入口行的状态文字：有备份记录时显示上次结果，否则显示功能简介。 */
    private void refreshWebDavLabel() {
        if (baseBind == null) return;
        WebDavLastRun run = WebDavPrefs.INSTANCE.lastRun();
        if (run == null) {
            baseBind.webdavDesc.setText(R.string.webdav_settings_entry_desc);
            return;
        }
        String when = android.text.format.DateUtils.formatDateTime(mContext, run.getTimeMs(),
                android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME);
        baseBind.webdavDesc.setText(run.getSuccess()
                ? getString(R.string.webdav_entry_last_success, when)
                : getString(R.string.webdav_entry_last_failed, when));
    }

    /**
     * 备份成功提示里要展示的目录路径。备份文件经 OutPut.outPutBackupFile 走
     * [Bucket.Backup] 模板,最终落点由当前存储配置决定:非 SAF(图库/下载)是
     * Download + 模板目录;SAF 则落在用户所选 tree 下的模板目录。SAF 分支能解析出
     * 真实文件系统路径就显示路径,否则退回文件夹显示名,绝不拼一个不存在的
     * /storage/… 误导用户(#940)。
     */
    private String backupTargetFolder() {
        String relDir = backupTemplateFolder();
        // 用 DownloadsRegistry.isSaf()(读实时配置)而不是 Shaft.sSettings.getDownloadWay():
        // 后者切换到图库/下载时不会被回写,是过时字段。SAF 是全局一刀切,
        // 图片存储是 SAF ⟺ 备份桶也是 SAF。
        if (!DownloadsRegistry.isSaf()) {
            String downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
            return TextUtils.isEmpty(relDir) ? downloads : downloads + "/" + relDir;
        }
        String rootUri = Shaft.sSettings.getRootPathUri();
        if (TextUtils.isEmpty(rootUri)) {
            return Settings.FILE_PATH_BACKUP; // isSaf 为真时 rootPathUri 一定已落库,纯兜底
        }
        Uri treeUri = Uri.parse(rootUri);
        String fsPath = safTreeToFsPath(treeUri);
        if (fsPath != null) {
            return TextUtils.isEmpty(relDir) ? fsPath : fsPath + "/" + relDir;
        }
        // 云端/非外部存储 provider 解析不出真实路径 —— 显示文件夹名而不是假路径
        DocumentFile tree = DocumentFile.fromTreeUri(mContext, treeUri);
        String name = tree != null ? tree.getName() : null;
        if (TextUtils.isEmpty(name)) {
            return TextUtils.isEmpty(relDir) ? "" : relDir;
        }
        return TextUtils.isEmpty(relDir) ? name : name + "/" + relDir;
    }

    private String backupTemplateFolder() {
        try {
            RelativePath rel = DownloadItems.backupDestination(BACKUP_FILE_NAME);
            return TextUtils.join("/", rel.getDirectory());
        } catch (Throwable t) {
            return "ShaftBackups";
        }
    }

    /**
     * 把 ExternalStorageProvider 的 tree URI 翻成真实文件系统路径。逻辑与
     * SafBackend.resolveFsPath 一致(那边处理 document URI,这里处理 tree URI):
     * 只有 com.android.externalstorage.documents 暴露可翻译的 "volume:relative"
     * docId,其它 provider(Drive / Downloads 等)一律返回 null,交给上层走文件夹名兜底。
     */
    private static String safTreeToFsPath(Uri treeUri) {
        if (!"com.android.externalstorage.documents".equals(treeUri.getAuthority())) {
            return null;
        }
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            String[] parts = docId.split(":", 2);
            if (parts.length != 2) {
                return null;
            }
            String volume = parts[0];
            String relative = parts[1];
            String root = "primary".equalsIgnoreCase(volume)
                    ? Environment.getExternalStorageDirectory().getAbsolutePath()
                    : "/storage/" + volume;
            return relative.isEmpty() ? root : root + "/" + relative;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Params.REQUEST_CODE_CHOOSE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            // 解析 + 入库挪去工作线程,并直接流式读 Uri —— 不再在主线程把整个备份文件
            // 读成 byte[] 再复制成 String 后全量解析(大备份 OOM/ANR,#981)。
            final android.content.Context appContext = mContext.getApplicationContext();
            final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            new Thread(() -> {
                BackupEntity restored = null;
                try (java.io.InputStream inputStream = appContext.getContentResolver().openInputStream(uri)) {
                    if (inputStream != null) {
                        restored = BackupUtils.restoreBackupEntity(appContext, inputStream);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                final BackupEntity result = restored;
                main.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (result != null) {
                        // 备份已替换 Settings；同步额度及其重启前未生效状态。
                        refreshImageCacheQuotaLabel();
                        refreshOriginalCacheQuotaLabel();
                        Common.showToast(getString(R.string.restore_success));
                        if (!SessionManager.INSTANCE.isLoggedIn()) {
                            maybePromptRestoreAccount(result.getUserEntityList());
                        }
                    } else {
                        Common.showToast(getString(R.string.restore_failed));
                    }
                });
            }, "backup-restore").start();
        }
    }

    /**
     * 无账号登录状态下，还原的备份中若带有登录账号，弹窗询问是否立即恢复登录。
     * 选择「是」则切到备份中最近登录的账号并重启进主页瀑布流；选择「稍后自行切换」
     * 则账号已保留在本地账号列表，可稍后自行切换。
     */
    private void maybePromptRestoreAccount(List<UserEntity> accounts) {
        final AccountResponse target = pickLatestBackupAccount(accounts);
        if (target == null) {
            return;
        }
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) {
            return;
        }
        new WitDialog.MessageDialogBuilder(getActivity())
                .setTitle(R.string.restore_found_account_title)
                .setMessage(R.string.restore_found_account_message)
                .addAction(R.string.restore_switch_later, new WitDialogAction.ActionListener() {
                    @Override
                    public void onClick(WitDialog dialog, int index) {
                        dialog.dismiss();
                    }
                })
                .addAction(0, R.string.restore_switch_now, WitDialogAction.ACTION_PROP_POSITIVE, new WitDialogAction.ActionListener() {
                    @Override
                    public void onClick(WitDialog dialog, int index) {
                        dialog.dismiss();
                        target.getUser().set_login(true);
                        Local.saveUser(target);
                        Common.restart();
                    }
                })
                .create()
                .show();
    }

    /** 取备份中最近一次登录的账号；token 不完整（非真实登录账号）时返回 null。 */
    private AccountResponse pickLatestBackupAccount(List<UserEntity> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        UserEntity best = null;
        for (UserEntity entity : accounts) {
            if (best == null || entity.getLoginTime() > best.getLoginTime()) {
                best = entity;
            }
        }
        if (best == null) {
            return null;
        }
        try {
            AccountResponse userModel = Shaft.sGson.fromJson(best.getUserGson(), AccountResponse.class);
            if (userModel != null && userModel.getUser() != null
                    && !TextUtils.isEmpty(userModel.getAccess_token())
                    && !TextUtils.isEmpty(userModel.getRefresh_token())) {
                return userModel;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 原图缓存上限即调即生效（见 {@link OriginalImageCacheManager#trimAsync}），没有「未生效」状态。 */
    private void refreshOriginalCacheQuotaLabel() {
        int current = Shaft.sSettings.getOriginalImageCacheMaxMb();
        String label = originalCacheQuotaLabel(mContext, current);
        baseBind.originalCacheQuota.setText(label);
    }

    private static String originalCacheQuotaLabel(Context context, int limitMb) {
        if (limitMb < 1024) {
            return context.getString(R.string.setting_image_cache_quota_value, limitMb);
        }
        String gb = new DecimalFormat("0.##").format(limitMb / 1024d);
        return context.getString(R.string.setting_image_cache_quota_value_gb, gb);
    }

    private void showOriginalCacheQuotaDialog() {
        OriginalImageCacheQuotaDialogBuilder builder = new OriginalImageCacheQuotaDialogBuilder(mActivity);
        builder.setTitle(R.string.setting_original_cache_quota);
        builder.addAction(R.string.string_cancel, (dialog, which) -> dialog.dismiss());
        builder.addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_POSITIVE, (dialog, which) -> {
            SeekBar slider = builder.slider;
            if (slider == null) {
                dialog.dismiss();
                return;
            }
            int chosen = builder.sliderTouched
                    ? OriginalImageCacheQuota.limitMbForProgress(
                            slider.getProgress(), OriginalImageCacheQuota.SLIDER_STEPS)
                    : builder.initialLimitMb;
            if (chosen != Shaft.sSettings.getOriginalImageCacheMaxMb()) {
                Shaft.sSettings.setOriginalImageCacheMaxMb(chosen);
                Local.setSettings(Shaft.sSettings);
                refreshOriginalCacheQuotaLabel();
                OriginalImageCacheManager.trimAsync(mContext, chosen);
            }
            dialog.dismiss();
        });
        builder.show();
    }

    /**
     * 右侧显示的当前「预期上限」：不足 1 GB 写 MB，正好 1 GB 写 GB；
     * 改过但还没重启时加「未生效」后缀。
     */
    private void refreshImageCacheQuotaLabel() {
        int current = Shaft.sSettings.getImageCacheMaxMb();
        String label = imageCacheQuotaLabel(mContext, current);
        if (ImageCacheQuotaState.isPendingRestart(current)) {
            label = getString(R.string.setting_image_cache_quota_pending, label);
        }
        baseBind.imageCacheQuota.setText(label);
    }

    private static String imageCacheQuotaLabel(Context context, int limitMb) {
        if (limitMb < ImageCacheQuota.MAX_LIMIT_MB) {
            return context.getString(R.string.setting_image_cache_quota_value, limitMb);
        }
        String gb = new DecimalFormat("0.##").format(limitMb / 1024d);
        return context.getString(R.string.setting_image_cache_quota_value_gb, gb);
    }

    /**
     * 「预期上限」入口：WitDialog 承载一条可拖动滑条，量程 100 MB–1 GB，没有「不限」档。
     *
     * 确认后只写设置，这里不做任何淘汰 —— 值要等下次 Glide 初始化才被读走，重启后也要等
     * 第一次写缓存才会按 LRU 收，这正是选项叫「预期」而不是「上限」的原因。提示重启只在
     * 新值确实和本次生效值不同时才弹，见下面的 isPendingRestart。
     */
    private void showImageCacheQuotaDialog() {
        ImageCacheQuotaDialogBuilder builder = new ImageCacheQuotaDialogBuilder(mActivity);
        builder.setTitle(R.string.setting_image_cache_quota);
        builder.addAction(R.string.string_cancel, (dialog, which) -> dialog.dismiss());
        builder.addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_POSITIVE, (dialog, which) -> {
            SeekBar slider = builder.slider;
            if (slider == null) {
                dialog.dismiss();
                return;
            }
            int chosen = builder.sliderTouched
                    ? ImageCacheQuota.limitMbForProgress(
                            slider.getProgress(), ImageCacheQuota.SLIDER_STEPS)
                    : builder.initialLimitMb;
            if (chosen != Shaft.sSettings.getImageCacheMaxMb()) {
                Shaft.sSettings.setImageCacheMaxMb(chosen);
                Local.setSettings(Shaft.sSettings);
                // 只有「值真的和本次生效的不一样」才需要重启。把 300 改回 250 时正在跑的就是
                // 250，这时候弹「重启后生效」是错的 —— 与右侧「（未生效）」后缀同一套判断。
                if (ImageCacheQuotaState.isPendingRestart(chosen)) {
                    Common.showToast(getString(R.string.please_restart_app), 2);
                }
                refreshImageCacheQuotaLabel();
            }
            dialog.dismiss();
        });
        builder.show();
    }

    private void loadAiResultsSizeAsync() {
        if (baseBind == null || aiResultsBusy) return;
        final int generation = ++aiStatsGeneration;
        final TextView target = baseBind.aiResultsSize;
        final Context app = mContext.getApplicationContext();
        target.setText("…");
        new Thread(() -> {
            String label;
            try {
                label = Formatter.formatShortFileSize(app, AiResultStorage.scan(app).getTotalBytes());
            } catch (Exception e) {
                Timber.w(e, "AI result size scan failed");
                label = app.getString(R.string.ai_results_stats_failed);
            }
            final String result = label;
            target.post(() -> {
                if (isAdded() && baseBind != null && generation == aiStatsGeneration) {
                    target.setText(result);
                }
            });
        }, "ai-results-size").start();
    }

    private void showManageAiResultsDialog() {
        if (aiResultsBusy || getActivity() == null || getActivity().isFinishing()) return;
        aiResultsBusy = true;
        final Context app = mContext.getApplicationContext();
        new Thread(() -> {
            AiResultStorage.Stats stats = null;
            try {
                stats = AiResultStorage.scan(app);
            } catch (Exception e) {
                Timber.w(e, "AI result stats failed");
            }
            final AiResultStorage.Stats result = stats;
            new Handler(Looper.getMainLooper()).post(() -> {
                aiResultsBusy = false;
                if (!isAdded() || baseBind == null) return;
                if (result == null) {
                    Common.showToast(R.string.ai_results_stats_failed);
                } else {
                    showManageAiResultsDialogWithStats(result);
                }
            });
        }, "ai-results-stats").start();
    }

    private void showManageAiResultsDialogWithStats(AiResultStorage.Stats stats) {
        WitDialog.MultiCheckableDialogBuilder builder =
                new WitDialog.MultiCheckableDialogBuilder(requireContext());
        builder.setTitle(R.string.ai_results_manage);
        builder.setCollapsibleHint(getString(R.string.ai_results_dialog_hint));
        builder.setHintExpanded(true);
        builder.addItem(getString(R.string.ai_results_upscale), (dialog, index) -> {});
        builder.addItem(getString(R.string.ai_results_rembg), (dialog, index) -> {});
        builder.addItem(getString(R.string.ai_results_manga), (dialog, index) -> {});
        builder.setCheckedItems(new int[0]);
        builder.addAction(R.string.cancel, (dialog, index) -> dialog.dismiss());
        builder.addAction(0, R.string.ai_results_clear, WitDialogAction.ACTION_PROP_NEGATIVE, (dialog, index) -> {
            boolean[] selected = new boolean[3];
            for (int checked : builder.getCheckedItemIndexes()) selected[checked] = true;
            if (!selected[0] && !selected[1] && !selected[2]) {
                Common.showToast(R.string.ai_results_none_selected);
                return;
            }
            dialog.dismiss();
            clearAiResults(selected);
        });
        WitDialog dialog = builder.create();
        builder.setItemStatus(0, getString(R.string.ai_results_item_stats, stats.getUpscale().getCount(),
                Formatter.formatShortFileSize(mContext, stats.getUpscale().getBytes())), null);
        builder.setItemStatus(1, getString(R.string.ai_results_item_stats, stats.getRembg().getCount(),
                Formatter.formatShortFileSize(mContext, stats.getRembg().getBytes())), null);
        builder.setItemStatus(2, getString(R.string.ai_results_item_stats, stats.getManga().getCount(),
                Formatter.formatShortFileSize(mContext, stats.getManga().getBytes())), null);
        dialog.show();
    }

    private void clearAiResults(boolean[] selected) {
        if (aiResultsBusy) return;
        final boolean needAiGate = selected[0] || selected[1];
        final boolean needMangaGate = selected[2];
        if (needAiGate && !AiTaskQueue.INSTANCE.beginResultCleanup()) {
            Common.showToast(R.string.ai_results_busy);
            return;
        }
        final Context app = mContext.getApplicationContext();
        final MangaBatchTranslateCenter center =
                ((ServicesProvider) app).getMangaBatchTranslateCenter();
        if (needMangaGate && !center.beginResultCleanup()) {
            if (needAiGate) AiTaskQueue.INSTANCE.endResultCleanup();
            Common.showToast(R.string.ai_results_busy);
            return;
        }
        aiResultsBusy = true;
        ++aiStatsGeneration;
        baseBind.aiResultsSize.setText("…");
        new Thread(() -> {
            boolean complete;
            try {
                complete = AiResultStorage.clear(app, selected[0], selected[1], selected[2]);
            } catch (Exception e) {
                Timber.w(e, "AI result cleanup failed");
                complete = false;
            }
            final boolean success = complete;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (needMangaGate) center.pruneMissingResults();
                    if (needAiGate) IllustAiStore.INSTANCE.pruneMissingResults();
                } finally {
                    if (needMangaGate) center.endResultCleanup();
                    if (needAiGate) AiTaskQueue.INSTANCE.endResultCleanup();
                    aiResultsBusy = false;
                }
                if (isAdded() && baseBind != null) {
                    Common.showToast(success ? R.string.ai_results_clear_success : R.string.ai_results_clear_failed);
                    loadAiResultsSizeAsync();
                }
            });
        }, "ai-results-clear").start();
    }

    private void loadCacheSizeAsync(TextView target, File folder) {
        target.setText("…");
        new Thread(() -> {
            String size = FileUtils.getSize(folder);
            target.post(() -> {
                if (isAdded()) {
                    target.setText(size);
                }
            });
        }, "cache-size-calc").start();
    }

    /**
     * "清除批量下载关联数据"那一行的大小估算 —— 采用双端极速采样估算，
     * 避免大表触发全盘扫描。数值前加“约”。
     */
    private void loadBulkDownloadCacheSizeAsync(TextView target) {
        if (target == null) return;
        if (bulkSizeSignal != null) {
            bulkSizeSignal.cancel();
        }
        bulkSizeSignal = new CancellationSignal();
        final CancellationSignal signal = bulkSizeSignal;
        target.setText("…");
        new Thread(() -> {
            long bytes;
            try {
                bytes = ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner
                        .estimateReclaimableBytes(mContext.getApplicationContext(), signal);
            } catch (OperationCanceledException ignored) {
                return;
            } catch (Throwable t) {
                if (signal.isCanceled()) return;
                bytes = 0L;
            }
            if (signal.isCanceled()) return;
            final String size;
            if (bytes <= 0L) {
                size = android.text.format.Formatter.formatShortFileSize(mContext, 0L);
            } else {
                String formatted = android.text.format.Formatter.formatShortFileSize(mContext, bytes);
                size = getString(R.string.approximate_size_format, formatted);
            }
            target.post(() -> {
                if (isAdded() && !signal.isCanceled()) {
                    target.setText(size);
                }
            });
        }, "bulk-dl-size-calc").start();
    }

    /**
     * 打开「管理下载关联数据」弹窗：先展示轻量加载指示，并在后台精确统计下载记录条数/体积、队列残留和 stage 大小。
     * 若弹窗在统计期间被取消或关闭，立即中断并掐掉在飞的 SQLite 查询事务。
     * 统计完成后以 WitDialog 展示存储概览，供用户选择「深度瘦身（推荐）」或「清空全部」。
     */
    private void showManageBulkDownloadDialog() {
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        if (bulkWipeRunning) return;
        if (manageDialogSignal != null) {
            manageDialogSignal.cancel();
        }
        if (manageLoadingDialog != null) {
            try {
                manageLoadingDialog.dismiss();
            } catch (Throwable ignored) {
            }
            manageLoadingDialog = null;
        }

        manageDialogSignal = new CancellationSignal();
        final CancellationSignal signal = manageDialogSignal;
        final Context context = getActivity();

        final ceui.pixiv.witstudio.dialog.WitTipDialog loadingDialog =
                new ceui.pixiv.witstudio.dialog.WitTipDialog.Builder(getActivity())
                        .setTipWord(getString(R.string.download_cache_calculating))
                        .create(true);
        loadingDialog.setCanceledOnTouchOutside(true);
        manageLoadingDialog = loadingDialog;

        final java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        loadingDialog.setOnCancelListener(d -> signal.cancel());
        loadingDialog.setOnDismissListener(d -> {
            if (!completed.get()) {
                signal.cancel();
            }
        });
        loadingDialog.show();

        new Thread(() -> {
            final ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner.StorageStats stats;
            final int active;
            try {
                stats = ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner
                        .computeStorageStats(context.getApplicationContext(), signal);
                active = ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner
                        .activeQueueCount(context.getApplicationContext(), signal);
            } catch (OperationCanceledException ignored) {
                return;
            } catch (Throwable t) {
                if (signal.isCanceled()) return;
                Timber.e(t, "showManageBulkDownloadDialog stats failed");
                return;
            } finally {
                if (signal.isCanceled()) {
                    completed.set(true);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            try {
                                loadingDialog.dismiss();
                            } catch (Throwable ignored) {
                            }
                            if (manageLoadingDialog == loadingDialog) {
                                manageLoadingDialog = null;
                            }
                        });
                    }
                }
            }
            if (signal.isCanceled()) return;
            if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
            getActivity().runOnUiThread(() -> {
                completed.set(true);
                try {
                    loadingDialog.dismiss();
                } catch (Throwable ignored) {
                }
                if (manageLoadingDialog == loadingDialog) {
                    manageLoadingDialog = null;
                }
                if (!isAdded() || signal.isCanceled()) return;
                showManageBulkDownloadDialogWithStats(stats, active);
            });
        }, "bulk-dl-stats").start();
    }

    private void showManageBulkDownloadDialogWithStats(ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner.StorageStats stats, int active) {
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        String recordsSize = android.text.format.Formatter.formatShortFileSize(mContext, stats.getDownloadRecordsBytes());
        String queueSize = android.text.format.Formatter.formatShortFileSize(mContext, stats.getQueuePayloadBytes());
        String stagingSize = android.text.format.Formatter.formatShortFileSize(mContext, stats.getStagingBytes());
        String message = getString(R.string.download_cache_manage_message,
                stats.getDownloadRecordsCount(), recordsSize,
                stats.getQueueSuccessCount(), queueSize,
                stagingSize);

        new WitDialog.MessageDialogBuilder(getActivity())
                .setTitle(R.string.manage_download_cache_dialog_title)
                .setMessage(message)
                .addAction(R.string.cancel, (d, idx) -> d.dismiss())
                .addAction(0, R.string.download_cache_action_wipe, WitDialogAction.ACTION_PROP_NEGATIVE, (d, idx) -> {
                    d.dismiss();
                    if (active > 0) {
                        Common.showToast(getString(R.string.clear_bulk_download_cache_queue_not_empty), 2);
                        return;
                    }
                    showClearBulkDownloadConfirmDialog();
                })
                .addAction(0, R.string.download_cache_action_slim, WitDialogAction.ACTION_PROP_POSITIVE, (d, idx) -> {
                    d.dismiss();
                    if (active > 0) {
                        Common.showToast(getString(R.string.clear_bulk_download_cache_queue_not_empty), 2);
                        return;
                    }
                    runBulkDownloadCacheDeepSlim();
                })
                .create()
                .show();
    }

    /**
     * 执行深度瘦身：清理无用字段与队列残留载荷，流式压缩历史多页与冗余 JSON，并执行 VACUUM 回收磁盘物理碎片。
     */
    private void runBulkDownloadCacheDeepSlim() {
        if (bulkWipeRunning) return;
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        bulkWipeRunning = true;

        final android.content.Context appCtx = mContext.getApplicationContext();
        final ceui.pixiv.witstudio.dialog.WitTipDialog progress =
                new ceui.pixiv.witstudio.dialog.WitTipDialog.Builder(getActivity())
                        .setTipWord(getString(R.string.download_cache_slim_progress))
                        .create(false);
        progress.show();

        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

        new Thread(() -> {
            ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner.SlimResult result = null;
            try {
                result = ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner.deepSlim(appCtx);
            } catch (Throwable t) {
                android.util.Log.w("FragmentSettingsData", "bulk download deep slim failed", t);
            }
            final ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner.SlimResult finalResult = result;
            main.post(() -> {
                bulkWipeRunning = false;
                try { progress.dismiss(); } catch (Throwable ignored) {}
                if (isAdded() && baseBind != null) {
                    if (finalResult != null && finalResult.getComplete()) {
                        String savedStr = android.text.format.Formatter.formatShortFileSize(mContext, finalResult.getBytesSaved());
                        Common.showToast(getString(R.string.download_cache_slim_success, finalResult.getRowsSlimmed(), savedStr));
                    } else {
                        Common.showToast(getString(R.string.clear_bulk_download_cache_failed), 2);
                    }
                    loadBulkDownloadCacheSizeAsync(baseBind.bulkDownloadCacheSize);
                }
            });
        }, "bulk-dl-slim").start();
    }

    private void showClearBulkDownloadConfirmDialog() {
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        new WitDialog.MessageDialogBuilder(getActivity())
                .setTitle(R.string.download_cache_action_wipe)
                .setMessage(R.string.clear_bulk_download_cache_message)
                .addAction(R.string.cancel, (d, idx) -> d.dismiss())
                .addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_NEGATIVE, (d, idx) -> {
                    d.dismiss();
                    runBulkDownloadCacheWipe();
                })
                .create()
                .show();
    }

    /**
     * 真正执行 wipe。
     */
    private void runBulkDownloadCacheWipe() {
        if (bulkWipeRunning) return;
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        bulkWipeRunning = true;

        final android.content.Context appCtx = mContext.getApplicationContext();
        final ceui.pixiv.witstudio.dialog.WitTipDialog progress =
                new ceui.pixiv.witstudio.dialog.WitTipDialog.Builder(getActivity())
                        .setTipWord(getString(R.string.clear_bulk_download_cache_progress))
                        .create(false);
        progress.show();

        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

        new Thread(() -> {
            boolean cleaned;
            try {
                cleaned = ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner.wipe(appCtx);
            } catch (Throwable t) {
                android.util.Log.w("FragmentSettingsData", "bulk download wipe failed", t);
                cleaned = false;
            }
            final boolean success = cleaned;
            main.post(() -> {
                bulkWipeRunning = false;
                try { progress.dismiss(); } catch (Throwable ignored) {}
                if (!isAdded() || baseBind == null) return;
                Common.showToast(getString(success
                        ? R.string.success_clear_bulk_download_cache
                        : R.string.clear_bulk_download_cache_failed), 2);
                loadBulkDownloadCacheSizeAsync(baseBind.bulkDownloadCacheSize);
            });
        }, "bulk-dl-wipe").start();
    }

    /** WitDialog 的自定义内容：标题下的大数值 + V3 滑条 + 两端说明。结构对齐自动快照配额弹窗。 */
    private static final class OriginalImageCacheQuotaDialogBuilder extends WitDialog.CustomDialogBuilder {

        private SeekBar slider;
        private TextView valueText;
        private int initialLimitMb;
        private boolean sliderTouched;

        private OriginalImageCacheQuotaDialogBuilder(Context context) {
            super(context);
        }

        @Override
        protected View onCreateContent(WitDialog dialog, WitDialogView parent, Context context) {
            View content = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_original_cache_quota, parent, false);
            slider = content.findViewById(R.id.dialog_original_cache_quota_slider);
            valueText = content.findViewById(R.id.dialog_original_cache_quota_value);
            valueText.setTextColor(V3Palette.from(context).getTextAccent());

            int current = Shaft.sSettings.getOriginalImageCacheMaxMb();
            initialLimitMb = current;
            slider.setMax(OriginalImageCacheQuota.SLIDER_STEPS);
            slider.setProgress(OriginalImageCacheQuota.progressForLimitMb(
                    current, OriginalImageCacheQuota.SLIDER_STEPS));
            valueText.setText(originalCacheQuotaLabel(context, current));

            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        sliderTouched = true;
                    }
                    valueText.setText(originalCacheQuotaLabel(context,
                            OriginalImageCacheQuota.limitMbForProgress(
                                    progress, OriginalImageCacheQuota.SLIDER_STEPS)));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            return content;
        }
    }

    private static final class ImageCacheQuotaDialogBuilder extends WitDialog.CustomDialogBuilder {

        private SeekBar slider;
        private TextView valueText;
        private int initialLimitMb;
        private boolean sliderTouched;

        private ImageCacheQuotaDialogBuilder(Context context) {
            super(context);
        }

        @Override
        protected View onCreateContent(WitDialog dialog, WitDialogView parent, Context context) {
            View content = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_image_cache_quota, parent, false);
            slider = content.findViewById(R.id.dialog_image_cache_quota_slider);
            valueText = content.findViewById(R.id.dialog_image_cache_quota_value);
            valueText.setTextColor(V3Palette.from(context).getTextAccent());

            int current = Shaft.sSettings.getImageCacheMaxMb();
            initialLimitMb = current;
            slider.setMax(ImageCacheQuota.SLIDER_STEPS);
            slider.setProgress(ImageCacheQuota.progressForLimitMb(
                    current, ImageCacheQuota.SLIDER_STEPS));
            valueText.setText(imageCacheQuotaLabel(context, current));

            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        sliderTouched = true;
                    }
                    valueText.setText(imageCacheQuotaLabel(context,
                            ImageCacheQuota.limitMbForProgress(
                                    progress, ImageCacheQuota.SLIDER_STEPS)));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            return content;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelStorageStatsTasks();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelStorageStatsTasks();
    }

    private void cancelStorageStatsTasks() {
        if (bulkSizeSignal != null) {
            bulkSizeSignal.cancel();
            bulkSizeSignal = null;
        }
        if (manageDialogSignal != null) {
            manageDialogSignal.cancel();
            manageDialogSignal = null;
        }
        if (manageLoadingDialog != null) {
            try {
                manageLoadingDialog.dismiss();
            } catch (Throwable ignored) {
            }
            manageLoadingDialog = null;
        }
    }
}
