package ceui.lisa.fragments;

import static android.app.Activity.RESULT_OK;
import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.blankj.utilcode.util.FileUtils;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.io.File;
import java.io.IOException;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.FragmentSettingsDataBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.BackupUtils;
import ceui.lisa.utils.BackupUtils.BackupEntity;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.Settings;
import ceui.loxia.MoonSync;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.session.SessionManager;
import ceui.pixiv.ui.bulk.UgoiraEngine;

/** 设置 · 备份与缓存 */
public class FragmentSettingsData extends SettingsPageFragment<FragmentSettingsDataBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_data;
    }

    @Override
    protected void initData() {
        // 备份与还原
        baseBind.backupRela.setOnClickListener(v -> {
            QMUIDialog.CheckBoxMessageDialogBuilder builder = new QMUIDialog.CheckBoxMessageDialogBuilder(getActivity());
            builder
                    .setTitle(getString(R.string.string_420))
                    .setMessage(getString(R.string.string_423))
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addAction(getString(R.string.string_187), new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    .addAction(R.string.sure, new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            final boolean backupViewHistory = builder.isChecked();
                            // 走 fileWriter 流式导出:读库 + 序列化 + 落盘全在 IO 线程逐批直写文件,
                            // 不再在主线程把整张历史表 toJson 成巨型 String(大历史库 OOM/ANR,#981)。
                            IllustDownload.downloadBackupFile((BaseActivity<?>) mActivity, "Shaft-Backup.json", (Callback<File>) textFile -> {
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
                Uri backupFileUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:" + "Download%2fShaftBackups%2fShaft-Backup.json");
                intent.putExtra(EXTRA_INITIAL_URI, backupFileUri);
            }
            startActivityForResult(intent, Params.REQUEST_CODE_CHOOSE);
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
            new QMUIDialog.MessageDialogBuilder(getActivity())
                    .setTitle(R.string.moon_upload_title)
                    .setMessage(getString(R.string.moon_upload_message) + currentVer)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addAction(getString(R.string.string_187), new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    .addAction(R.string.sure, new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
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
        baseBind.clearBulkDownloadCache.setOnClickListener(v -> showClearBulkDownloadConfirmDialog());
    }

    /**
     * 备份成功提示里要展示的目录路径。备份文件经 OutPut.outPutBackupFile 走
     * Bucket.Backup + "ShaftBackups/…",最终落点由当前存储配置决定,不再固定在
     * Download:非 SAF(图库/下载)是 Download/ShaftBackups(Settings.FILE_PATH_BACKUP);
     * SAF 则落在用户所选 tree 下的 ShaftBackups。SAF 分支能解析出真实文件系统路径
     * 就显示路径,否则退回文件夹显示名,绝不拼一个不存在的 /storage/… 误导用户(#940)。
     */
    private String backupTargetFolder() {
        // 用 DownloadsRegistry.isSaf()(读实时配置)而不是 Shaft.sSettings.getDownloadWay():
        // 后者切换到图库/下载时不会被回写,是过时字段。SAF 是全局一刀切,
        // 图片存储是 SAF ⟺ 备份桶也是 SAF。
        if (!DownloadsRegistry.isSaf()) {
            return Settings.FILE_PATH_BACKUP;
        }
        String rootUri = Shaft.sSettings.getRootPathUri();
        if (TextUtils.isEmpty(rootUri)) {
            return Settings.FILE_PATH_BACKUP; // isSaf 为真时 rootPathUri 一定已落库,纯兜底
        }
        Uri treeUri = Uri.parse(rootUri);
        String fsPath = safTreeToFsPath(treeUri);
        if (fsPath != null) {
            return fsPath + "/ShaftBackups";
        }
        // 云端/非外部存储 provider 解析不出真实路径 —— 显示文件夹名而不是假路径
        DocumentFile tree = DocumentFile.fromTreeUri(mContext, treeUri);
        String name = tree != null ? tree.getName() : null;
        return TextUtils.isEmpty(name) ? "" : name + "/ShaftBackups";
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
        final UserModel target = pickLatestBackupAccount(accounts);
        if (target == null) {
            return;
        }
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) {
            return;
        }
        new QMUIDialog.MessageDialogBuilder(getActivity())
                .setTitle(R.string.restore_found_account_title)
                .setMessage(R.string.restore_found_account_message)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                .addAction(R.string.restore_switch_later, new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        dialog.dismiss();
                    }
                })
                .addAction(0, R.string.restore_switch_now, QMUIDialogAction.ACTION_PROP_POSITIVE, new QMUIDialogAction.ActionListener() {
                    @Override
                    public void onClick(QMUIDialog dialog, int index) {
                        dialog.dismiss();
                        target.getUser().setIs_login(true);
                        Local.saveUser(target);
                        Common.restart();
                    }
                })
                .create()
                .show();
    }

    /** 取备份中最近一次登录的账号；token 不完整（非真实登录账号）时返回 null。 */
    private UserModel pickLatestBackupAccount(List<UserEntity> accounts) {
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
            UserModel userModel = Shaft.sGson.fromJson(best.getUserGson(), UserModel.class);
            if (userModel != null && userModel.getUser() != null
                    && !TextUtils.isEmpty(userModel.getRawAccessToken())
                    && !TextUtils.isEmpty(userModel.getRefresh_token())) {
                return userModel;
            }
        } catch (Exception ignored) {
        }
        return null;
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
     * "清除批量下载关联数据"那一行的大小估算 —— 算 illust_download_table /
     * download_queue 里 illustGson 列的字节 + staging_dl/ 实际占用。SQL 计数走子线程,
     * 大表 (Mio 用户 50k 行) 仍是毫秒级。
     */
    private void loadBulkDownloadCacheSizeAsync(TextView target) {
        target.setText("…");
        new Thread(() -> {
            long bytes;
            try {
                bytes = ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner
                        .computeReclaimableBytes(mContext.getApplicationContext());
            } catch (Throwable t) {
                bytes = 0L;
            }
            final String size = android.text.format.Formatter.formatShortFileSize(mContext, bytes);
            target.post(() -> {
                if (isAdded()) {
                    target.setText(size);
                }
            });
        }, "bulk-dl-size-calc").start();
    }

    /**
     * 跟 DoneListV3Fragment.showClearDoneConfirmDialog 一样的 destructive 二次确认 ——
     * 文案明确说"已下好的图不会被删",避免用户因恐慌而不敢清理。
     */
    private void showClearBulkDownloadConfirmDialog() {
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        new QMUIDialog.MessageDialogBuilder(getActivity())
                .setTitle(R.string.clear_bulk_download_cache)
                .setMessage(R.string.clear_bulk_download_cache_message)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                .addAction(R.string.cancel, (d, idx) -> d.dismiss())
                .addAction(0, R.string.sure, QMUIDialogAction.ACTION_PROP_NEGATIVE, (d, idx) -> {
                    d.dismiss();
                    runBulkDownloadCacheWipe();
                })
                .create()
                .show();
    }

    /**
     * 真正执行 wipe。VACUUM 1.5GB DB 在低端机上能跑 30s+,期间挂个 progress dialog
     * 不让用户重复点;wipe 内部把队列 / Manager 都 stop 了,再次写库的入口已经关掉。
     */
    private void runBulkDownloadCacheWipe() {
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        final android.content.Context appCtx = mContext.getApplicationContext();
        final ceui.pixiv.witstudio.dialog.WitTipDialog progress =
                new ceui.pixiv.witstudio.dialog.WitTipDialog.Builder(getActivity())
                        .setTipWord(getString(R.string.clear_bulk_download_cache_progress))
                        .create();
        progress.setCancelable(false);
        progress.show();

        // 用 Main looper 的 Handler 而不是绑某个 View 的 post —— 旋屏时 baseBind 会被
        // DataBinding 重建,绑 view 的 post 可能根本不跑,留下孤儿 progress dialog
        // 抓着旧 activity 触发 WindowLeaked。Handler 是 activity-agnostic,稳。
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

        new Thread(() -> {
            try {
                ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner.wipe(appCtx);
            } catch (Throwable t) {
                android.util.Log.w("FragmentSettingsData", "bulk download wipe failed", t);
            }
            main.post(() -> {
                // 先无条件 dismiss —— 即使 fragment 已被销毁,旧 activity 上挂着的
                // 这个 dialog 也必须解掉,否则 WindowLeaked。
                try { progress.dismiss(); } catch (Throwable ignored) {}
                if (isAdded() && baseBind != null) {
                    Common.showToast(getString(R.string.success_clear_bulk_download_cache));
                    loadBulkDownloadCacheSizeAsync(baseBind.bulkDownloadCacheSize);
                }
            });
        }, "bulk-dl-wipe").start();
    }
}
