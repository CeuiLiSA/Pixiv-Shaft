package ceui.lisa.fragments;

import static android.app.Activity.RESULT_OK;
import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.UriUtils;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentSettingsDataBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.BackupUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.Settings;
import ceui.loxia.MoonSync;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.config.StorageChoice;
import ceui.pixiv.session.SessionManager;

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
                            String backupString = BackupUtils.getBackupString(mContext, builder.isChecked());
                            IllustDownload.downloadBackupFile((BaseActivity<?>) mActivity, "Shaft-Backup.json", backupString, new Callback<Uri>() {
                                @Override
                                public void doSomething(Uri t) {
                                    //为什么不用Shaft.sSettings.getDownloadWay() == 1，当用户更过改存储方式后，该值却不更新
                                    if (isSafStorage()) {
                                        // 获取 SAF 选择的文件夹路径
                                        String rootPath = getSafFullPath();
                                        if (rootPath != null) {
                                            // 显示 SAF 文件夹路径 + ShaftBackups 目录
                                            Common.showToast(getString(R.string.backup_success) + rootPath + "/ShaftBackups");
                                        } else {
                                            Common.showToast(getString(R.string.backup_success));
                                        }
                                    } else {Common.showToast(getString(R.string.backup_success) + Settings.FILE_PATH_BACKUP);}
                                }
                                private boolean isSafStorage() {
                                    return DownloadsRegistry.currentImagesStorage() instanceof StorageChoice.Saf;
                                }
                                private String getSafFullPath() {
                                    String rootUri = Shaft.sSettings.getRootPathUri();
                                    if (TextUtils.isEmpty(rootUri)) {
                                        return null;
                                    }

                                    try {
                                        Uri treeUri = Uri.parse(rootUri);
                                        String uriPath = treeUri.getPath();
                                        if (uriPath != null && uriPath.contains("/tree/")) {
                                            String treePart = uriPath.substring(uriPath.indexOf("/tree/") + 6);

                                            if (treePart.startsWith("primary:")) {
                                                String path = treePart.substring(8);
                                                return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + path;
                                            } else if (treePart.contains(":")) {
                                                String[] parts = treePart.split(":", 2);
                                                if (parts.length == 2) {
                                                    String path = parts[1];
                                                    return "/storage/" + parts[0] + "/" + path;
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }

                                    return null;
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
            FileUtils.deleteAllInDir(LegacyFile.gifCacheFolder(mContext));
            Common.showToast(getString(R.string.success_clearGifCache), 2);
            loadCacheSizeAsync(baseBind.gifCacheSize, LegacyFile.gifCacheFolder(mContext));
        });

        // 清除批量下载关联数据 —— illust_download_table / download_queue 两张表
        // 的 illustGson 列是重度用户存储占用的大头 (单条 10–30 KB,几万行就上 GB);
        // 加这一行让用户能把"下载管理"reset 回初始状态。落盘的图不会被删,
        // 已下载的内容仍能在系统相册看到。
        loadBulkDownloadCacheSizeAsync(baseBind.bulkDownloadCacheSize);
        baseBind.clearBulkDownloadCache.setOnClickListener(v -> showClearBulkDownloadConfirmDialog());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Params.REQUEST_CODE_CHOOSE && resultCode == RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                String fileString = new String(UriUtils.uri2Bytes(uri));
                boolean restoreResult = BackupUtils.restoreBackups(mContext, fileString);
                Common.showToast(restoreResult ? getString(R.string.restore_success) : getString(R.string.restore_failed));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
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
        final com.qmuiteam.qmui.widget.dialog.QMUITipDialog progress =
                new com.qmuiteam.qmui.widget.dialog.QMUITipDialog.Builder(getActivity())
                        .setIconType(com.qmuiteam.qmui.widget.dialog.QMUITipDialog.Builder.ICON_TYPE_LOADING)
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
