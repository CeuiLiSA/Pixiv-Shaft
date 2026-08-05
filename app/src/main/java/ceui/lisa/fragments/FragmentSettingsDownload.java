package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.CompoundButton;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.core.Manager;
import ceui.lisa.databinding.FragmentSettingsDownloadBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DownloadLimitTypeUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.config.OverwritePolicy;
import ceui.pixiv.download.config.StorageChoice;

/** 设置 · 下载 */
public class FragmentSettingsDownload extends SettingsPageFragment<FragmentSettingsDownloadBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_download;
    }

    @Override
    protected void initData() {
        // R18 / AI 分目录开关：layout 里仍然存在，但路径 / 命名已收到「下载路径 / 文件名」页，
        // 这两个 switch 在主设置页保持隐藏，避免和 v3 配置打架。
        baseBind.r18DivideSaveRela.setVisibility(View.GONE);
        baseBind.aiDivideSaveRela.setVisibility(View.GONE);

        baseBind.r18DivideSave.setChecked(Shaft.sSettings.isR18DivideSave());
        baseBind.r18DivideSave.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setR18DivideSave(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.r18DivideSaveRela.setOnClickListener(v -> baseBind.r18DivideSave.performClick());

        // AI作品下载至单独的目录
        baseBind.aiDivideSave.setChecked(Shaft.sSettings.isAIDivideSave());
        baseBind.aiDivideSave.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setAIDivideSave(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.aiDivideSaveRela.setOnClickListener(v -> baseBind.aiDivideSave.performClick());

        // 下载路径 / 文件名 —— 所有分目录 / 命名 / 存储位置的配置都收在这一个入口
        baseBind.fileNameS.setText(getString(R.string.download_path_title));
        baseBind.fileName.setText(getString(R.string.download_path_entry_desc));
        baseBind.fileNameRela.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载路径与文件名");
            startActivity(intent);
        });

        // aria2 远程下载（#692）—— 把下载任务发给 NAS / 远程服务器上的 aria2
        refreshAria2Label();
        baseBind.aria2Rela.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "aria2远程下载");
            startActivity(intent);
        });

        // 下载内容信息头 —— 可视化勾选 / 拖拽排序小说 TXT 的元信息块
        baseBind.novelHeaderRela.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说信息头");
            startActivity(intent);
        });

        // 默认小说下载格式
        final String[] NOVEL_FORMAT_NAMES = new String[]{
                getString(R.string.option_always_ask),
                getString(R.string.format_txt),
                getString(R.string.format_markdown),
                getString(R.string.format_epub),
                getString(R.string.format_pdf)
        };
        final String[] NOVEL_FORMAT_VALUES = new String[]{"", "Txt", "Markdown", "Epub", "Pdf"};
        {
            int idx = 0;
            String cur = Shaft.sSettings.getDefaultNovelExportFormat();
            for (int i = 0; i < NOVEL_FORMAT_VALUES.length; i++) {
                if (NOVEL_FORMAT_VALUES[i].equals(cur)) { idx = i; break; }
            }
            baseBind.defaultNovelFormat.setText(NOVEL_FORMAT_NAMES[idx]);
        }
        baseBind.defaultNovelFormatRela.setOnClickListener(v -> {
            int checkedIdx = 0;
            String cur = Shaft.sSettings.getDefaultNovelExportFormat();
            for (int i = 0; i < NOVEL_FORMAT_VALUES.length; i++) {
                if (NOVEL_FORMAT_VALUES[i].equals(cur)) { checkedIdx = i; break; }
            }
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(checkedIdx)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addItems(NOVEL_FORMAT_NAMES, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Shaft.sSettings.setDefaultNovelExportFormat(NOVEL_FORMAT_VALUES[which]);
                            baseBind.defaultNovelFormat.setText(NOVEL_FORMAT_NAMES[which]);
                            Local.setSettings(Shaft.sSettings);
                            dialog.dismiss();
                        }
                    })
                    .show();
        });

        // 默认图片保存清晰度
        final String[] IMG_RES_NAMES = new String[]{
                getString(R.string.resolution_original),
                getString(R.string.resolution_large),
                getString(R.string.resolution_medium),
                getString(R.string.resolution_square_medium)
        };
        final String[] IMG_RES_VALUES = new String[]{
                Params.IMAGE_RESOLUTION_ORIGINAL,
                Params.IMAGE_RESOLUTION_LARGE,
                Params.IMAGE_RESOLUTION_MEDIUM,
                Params.IMAGE_RESOLUTION_SQUARE_MEDIUM
        };
        {
            int idx = 0;
            String cur = Shaft.sSettings.getDefaultImageResolution();
            if (!cur.isEmpty()) {
                for (int i = 0; i < IMG_RES_VALUES.length; i++) {
                    if (IMG_RES_VALUES[i].equals(cur)) { idx = i; break; }
                }
            }
            baseBind.defaultImageResolution.setText(IMG_RES_NAMES[idx]);
        }
        baseBind.defaultImageResolutionRela.setOnClickListener(v -> {
            int checkedIdx = 0;
            String cur = Shaft.sSettings.getDefaultImageResolution();
            if (!cur.isEmpty()) {
                for (int i = 0; i < IMG_RES_VALUES.length; i++) {
                    if (IMG_RES_VALUES[i].equals(cur)) { checkedIdx = i; break; }
                }
            }
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(checkedIdx)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addItems(IMG_RES_NAMES, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Shaft.sSettings.setDefaultImageResolution(IMG_RES_VALUES[which]);
                            baseBind.defaultImageResolution.setText(IMG_RES_NAMES[which]);
                            Local.setSettings(Shaft.sSettings);
                            dialog.dismiss();
                        }
                    })
                    .show();
        });

        // 文件重复时（OverwritePolicy）
        // SAF 模式下这个 setting 被锁住,因为 SafBackend.replace 已经 override 成
        // 不检测重复(目录 3 万+ 文件后 findFile 会退化成 O(N²)),所以 Skip/Replace/
        // Rename 三个语义不再适用,统一显示成「自动产生副本 · 不可改」+ toast 解释。
        final String[] POLICY_NAMES = new String[]{
                getString(R.string.download_path_policy_skip),
                getString(R.string.download_path_policy_replace),
                getString(R.string.download_path_policy_rename)
        };
        final OverwritePolicy[] POLICY_VALUES = OverwritePolicy.values();
        refreshOverwritePolicyRow();
        baseBind.overwritePolicyRela.setOnClickListener(v -> {
            /*
            if (DownloadsRegistry.isSaf()) {
                new QMUIDialog.MessageDialogBuilder(mActivity)
                        .setTitle(R.string.setting_overwrite_policy_saf_locked_title)
                        .setMessage(R.string.setting_overwrite_policy_saf_locked_message)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addAction(0, getString(R.string.button_ok), (dialog, index) -> dialog.dismiss())
                        .show();
                return;
            }*/
            OverwritePolicy cur = DownloadsRegistry.getStore().loadOrFallback().getDefaults().getOverwrite();
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(cur.ordinal())
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addItems(POLICY_NAMES, (dialog, which) -> {
                        OverwritePolicy selected = POLICY_VALUES[which];
                        DownloadsRegistry.getStore().update(cfg ->
                                cfg.copy(
                                        cfg.getVersion(),
                                        cfg.getDefaults().copy(
                                                cfg.getDefaults().getTemplate(),
                                                cfg.getDefaults().getStorage(),
                                                selected
                                        ),
                                        cfg.getPerBucket(),
                                        cfg.getWifiOnly(),
                                        cfg.getPageIndexFrom1(),
                                        cfg.getPadPageNumber()
                                )
                        );
                        refreshOverwritePolicyRow();
                        dialog.dismiss();
                    })
                    .show();
        });

        // 存储位置（StorageChoice）—— 0 = Pictures, 1 = Downloads, 2 = SAF
        refreshStorageLabel();
        baseBind.storageChoiceRela.setOnClickListener(v ->
                new QMUIDialog.CheckableDialogBuilder(mActivity)
                        .setCheckedIndex(currentStorageIndex())
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(storageNames(), (dialog, which) -> {
                            dialog.dismiss();
                            if (which == 0) {
                                DownloadsRegistry.applyGlobalStorage(
                                        new StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images));
                                refreshStorageLabel();
                                refreshOverwritePolicyRow();
                            } else if (which == 1) {
                                DownloadsRegistry.applyGlobalStorage(
                                        new StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads));
                                refreshStorageLabel();
                                refreshOverwritePolicyRow();
                            } else {
                                // SAF：走 BaseActivity.launchSafTreePicker。
                                // 1) 必须 legacy Activity.startActivityForResult —— 之前用 AndroidX 的
                                //    registerForActivityResult,vivo/iQOO 上 picker 选完投递不回回调。
                                // 2) 必须 explicit setComponent —— vivo OriginOS 非 debuggable apk 会拦掉
                                //    implicit ACTION_OPEN_DOCUMENT_TREE 重定向到 LAUNCHER。详见 helper 注释。
                                // BaseActivity#onActivityResult 的 ASK_URI 分支负责落 URI、拿 persistable
                                // 权限、applyGlobalStorage、toast;这边的 label 在 onResume 里再刷一次。
                                BaseActivity.launchSafTreePicker(mActivity);
                            }
                        })
                        .show());

        // 多图页码起始（pageIndexFrom1）
        final String[] PAGE_INDEX_NAMES = new String[]{
                getString(R.string.setting_page_index_from_0),
                getString(R.string.setting_page_index_from_1)
        };
        {
            boolean from1 = DownloadsRegistry.getStore().loadOrFallback().getPageIndexFrom1();
            baseBind.pageIndex.setText(PAGE_INDEX_NAMES[from1 ? 1 : 0]);
        }
        baseBind.pageIndexRela.setOnClickListener(v -> {
            boolean from1 = DownloadsRegistry.getStore().loadOrFallback().getPageIndexFrom1();
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(from1 ? 1 : 0)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addItems(PAGE_INDEX_NAMES, (dialog, which) -> {
                        boolean selected = which == 1;
                        DownloadsRegistry.getStore().update(cfg ->
                                cfg.copy(
                                        cfg.getVersion(),
                                        cfg.getDefaults(),
                                        cfg.getPerBucket(),
                                        cfg.getWifiOnly(),
                                        selected,
                                        cfg.getPadPageNumber()
                                )
                        );
                        baseBind.pageIndex.setText(PAGE_INDEX_NAMES[which]);
                        dialog.dismiss();
                    })
                    .show();
        });

        //插画详情长按下载
        baseBind.illustLongPressDownload.setChecked(Shaft.sSettings.isIllustLongPressDownload());
        baseBind.illustLongPressDownload.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setIllustLongPressDownload(isChecked);
                Common.showToast(getString(R.string.please_restart_app));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.illustLongPressDownloadRela.setOnClickListener(v ->
                baseBind.illustLongPressDownload.performClick());

        // 下载完成App内提示
        baseBind.toastDownloadResult.setChecked(Shaft.sSettings.isToastDownloadResult());
        baseBind.toastDownloadResult.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setToastDownloadResult(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.toastDownloadResultRela.setOnClickListener(v ->
                baseBind.toastDownloadResult.performClick());

        // 下载 JPEG 时把标签写进图片(XMP 关键词，issue #938）
        baseBind.writeExifTags.setChecked(Shaft.sSettings.isWriteTagsToImageExif());
        baseBind.writeExifTags.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setWriteTagsToImageExif(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.writeExifTagsRela.setOnClickListener(v ->
                baseBind.writeExifTags.performClick());

        // 低调下载:时间戳回拨,不出现在相册 / 微信 / QQ 最近图片前排(issue #731)
        baseBind.silentDownload.setChecked(Shaft.sSettings.isSilentDownload());
        baseBind.silentDownload.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setSilentDownload(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.silentDownloadRela.setOnClickListener(v ->
                baseBind.silentDownload.performClick());

        //下载限制类型
        final String[] DOWNLOAD_START_TYPE_NAMES = new String[]{
                getString(DownloadLimitTypeUtil.DOWNLOAD_START_TYPE_IDS[0]),
                getString(DownloadLimitTypeUtil.DOWNLOAD_START_TYPE_IDS[1]),
                getString(DownloadLimitTypeUtil.DOWNLOAD_START_TYPE_IDS[2])
        };
        baseBind.downloadLimitType.setText(DOWNLOAD_START_TYPE_NAMES[DownloadLimitTypeUtil.getCurrentStatusIndex()]);
        baseBind.downloadLimitType.setOnClickListener(v ->
                new QMUIDialog.CheckableDialogBuilder(mActivity)
                        .setCheckedIndex(Shaft.sSettings.getDownloadLimitType())
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(DOWNLOAD_START_TYPE_NAMES, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == Shaft.sSettings.getDownloadLimitType()) {
                                    Common.showLog("什么也不做");
                                } else {
                                    Shaft.sSettings.setDownloadLimitType(which);
                                    baseBind.downloadLimitType.setText(DOWNLOAD_START_TYPE_NAMES[DownloadLimitTypeUtil.getCurrentStatusIndex()]);
                                    Common.showToast(getString(R.string.string_428));
                                    Local.setSettings(Shaft.sSettings);
                                }
                                dialog.dismiss();
                            }
                        })
                        .show());
        baseBind.downloadLimitTypeRela.setOnClickListener(v ->
                baseBind.downloadLimitType.performClick());

        // 同时下载数 1-5（issue #859：用户希望多任务下载，默认 1=旧串行行为）
        final String[] CONCURRENCY_NAMES = new String[]{
                getString(R.string.setting_max_concurrent_downloads_one),
                getString(R.string.setting_max_concurrent_downloads_n, 2),
                getString(R.string.setting_max_concurrent_downloads_n, 3),
                getString(R.string.setting_max_concurrent_downloads_n, 4),
                getString(R.string.setting_max_concurrent_downloads_n, 5),
        };
        final Runnable refreshConcurrencyLabel = () -> {
            int n = Shaft.sSettings.getMaxConcurrentDownloads();
            if (n < 1) n = 1; if (n > 5) n = 5;
            baseBind.maxConcurrentDownloads.setText(CONCURRENCY_NAMES[n - 1]);
        };
        refreshConcurrencyLabel.run();
        baseBind.maxConcurrentDownloads.setOnClickListener(v -> {
            int current = Shaft.sSettings.getMaxConcurrentDownloads();
            if (current < 1) current = 1; if (current > 5) current = 5;
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(current - 1)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addItems(CONCURRENCY_NAMES, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            int chosen = which + 1;
                            if (chosen != Shaft.sSettings.getMaxConcurrentDownloads()) {
                                Shaft.sSettings.setMaxConcurrentDownloads(chosen);
                                Local.setSettings(Shaft.sSettings);
                                refreshConcurrencyLabel.run();
                                Common.showToast(getString(R.string.setting_max_concurrent_downloads_changed, chosen));
                                // 即时生效：仅 pump 新增的槽位，不要 startAll —— 那会把
                                // 用户手动暂停的 item 一并恢复，违反用户预期。
                                try { Manager.get().pumpAvailableSlots(); } catch (Exception ignored) {}
                            }
                            dialog.dismiss();
                        }
                    })
                    .show();
        });
        baseBind.maxConcurrentDownloadsRela.setOnClickListener(v ->
                baseBind.maxConcurrentDownloads.performClick());
    }

    private String[] storageNames() {
        return new String[]{
                getString(R.string.setting_storage_pictures),
                getString(R.string.setting_storage_downloads),
                getString(R.string.setting_storage_saf)
        };
    }

    private int currentStorageIndex() {
        StorageChoice cur = DownloadsRegistry.currentImagesStorage();
        if (cur instanceof StorageChoice.Saf) return 2;
        if (cur instanceof StorageChoice.MediaStore
                && ((StorageChoice.MediaStore) cur).getCollection()
                    == StorageChoice.MediaStore.Collection.Downloads) {
            return 1;
        }
        return 0;
    }

    private void refreshStorageLabel() {
        if (baseBind == null) return;
        baseBind.storageChoice.setText(storageNames()[currentStorageIndex()]);
    }

    private void refreshOverwritePolicyRow() {
        if (baseBind == null) return;
        /*
        if (DownloadsRegistry.isSaf()) {
            baseBind.overwritePolicy.setText(R.string.setting_overwrite_policy_saf_locked);
            baseBind.overwritePolicy.setAlpha(0.5f);
        } else {
            OverwritePolicy cur = DownloadsRegistry.getStore().loadOrFallback().getDefaults().getOverwrite();
            String[] names = new String[]{
                    getString(R.string.download_path_policy_skip),
                    getString(R.string.download_path_policy_replace),
                    getString(R.string.download_path_policy_rename)
            };
            baseBind.overwritePolicy.setText(names[cur.ordinal()]);
            baseBind.overwritePolicy.setAlpha(1.0f);
        }*/
        OverwritePolicy cur = DownloadsRegistry.getStore().loadOrFallback().getDefaults().getOverwrite();
        String[] names = new String[]{
                getString(R.string.download_path_policy_skip),
                getString(R.string.download_path_policy_replace),
                getString(R.string.download_path_policy_rename)
        };
        baseBind.overwritePolicy.setText(names[cur.ordinal()]);
        baseBind.overwritePolicy.setAlpha(1.0f);
    }

    /** aria2 远程下载入口行的状态文字：已启用时显示 RPC 地址，否则显示功能简介。 */
    private void refreshAria2Label() {
        if (baseBind == null) return;
        if (Shaft.sSettings.isAria2Enabled() && !TextUtils.isEmpty(Shaft.sSettings.getAria2RpcUrl())) {
            baseBind.aria2Desc.setText(getString(R.string.aria2_status_enabled, Shaft.sSettings.getAria2RpcUrl()));
        } else {
            baseBind.aria2Desc.setText(getString(R.string.aria2_settings_entry_desc));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // SAF picker 在 BaseActivity#onActivityResult 落库后，回到 fragment 时把 label 拉到现态。
        refreshStorageLabel();
        refreshOverwritePolicyRow();
        // 从 aria2 设置子页返回时把开关状态同步到入口行。
        refreshAria2Label();
    }
}
