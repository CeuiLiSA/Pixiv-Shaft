package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import ceui.pixiv.witstudio.dialog.WitSkinManager;
import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentSettingsAiBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;

/** 设置 · AI 功能（超分/抠图/漫画翻译模型） */
public class FragmentSettingsAi extends SettingsPageFragment<FragmentSettingsAiBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_ai;
    }

    @Override
    protected void initData() {
        // AI 超分辨率模型
        {
            ceui.pixiv.ui.upscale.UpscaleModel saved = ceui.pixiv.ui.upscale.ModelPickerDialog.Companion.getSavedModel();
            baseBind.defaultUpscaleModel.setText(saved != null ? saved.getDisplayName() : getString(R.string.string_not_set));
            baseBind.defaultUpscaleModelRela.setOnClickListener(v -> {
                ceui.pixiv.ui.upscale.ModelPickerDialog.Companion.show(getChildFragmentManager(), model -> {
                    Shaft.sSettings.setDefaultUpscaleModel(model.name());
                    Local.setSettings(Shaft.sSettings);
                    baseBind.defaultUpscaleModel.setText(model.getDisplayName());
                    return kotlin.Unit.INSTANCE;
                });
            });
        }

        // AI 抠图模型
        {
            ceui.pixiv.ui.upscale.RembgModel savedRembg = ceui.pixiv.ui.upscale.RembgModelPickerDialog.Companion.getSavedModel();
            baseBind.defaultRembgModel.setText(savedRembg != null ? savedRembg.getDisplayName() : getString(R.string.string_not_set));
            baseBind.defaultRembgModelRela.setOnClickListener(v -> {
                if (getChildFragmentManager().findFragmentByTag("RembgModelPickerDialog") != null) return;
                ceui.pixiv.ui.upscale.RembgModelPickerDialog dialog = new ceui.pixiv.ui.upscale.RembgModelPickerDialog();
                dialog.setOnModelSelected(model -> {
                    Shaft.sSettings.setDefaultRembgModel(model.name());
                    Local.setSettings(Shaft.sSettings);
                    baseBind.defaultRembgModel.setText(model.getDisplayName());
                    return kotlin.Unit.INSTANCE;
                });
                dialog.show(getChildFragmentManager(), "RembgModelPickerDialog");
            });
        }

        // RIFE 补帧模型 — 动图(ugoira)AI 补帧,把 8-15fps 的动图插到 2 倍帧率
        {
            baseBind.rifeModelRela.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "RIFE补帧模型下载");
                intent.putExtra("rife_model_name", ceui.pixiv.ui.interpolate.RifeModel.RIFE_V4_6.name());
                startActivity(intent);
            });
            bindLongPressDeleteModel(
                    baseBind.rifeModelRela,
                    ceui.pixiv.ui.interpolate.RifeModelManager.INSTANCE,
                    ceui.pixiv.ui.interpolate.RifeModel.RIFE_V4_6);
        }

        // 气泡检测模型 (comic-text-detector) — 漫画翻译流水线的文本框/气泡检测阶段
        {
            baseBind.bubbleDetectorModelRela.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "漫画文本框检测模型下载");
                intent.putExtra("ctd_model_name", ceui.pixiv.ui.translate.ComicTextDetectorModel.CTD_BASE.name());
                startActivity(intent);
            });
            bindLongPressDeleteModel(
                    baseBind.bubbleDetectorModelRela,
                    ceui.pixiv.ui.translate.ComicTextDetectorModelManager.INSTANCE,
                    ceui.pixiv.ui.translate.ComicTextDetectorModel.CTD_BASE);
        }

        // 漫画 OCR 识别模型 (manga-ocr) — 把检测出的气泡里的日文识别成文本
        {
            baseBind.ocrModelRela.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "漫画OCR模型下载");
                intent.putExtra("manga_ocr_model_name", ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE.name());
                startActivity(intent);
            });
            bindLongPressDeleteModel(
                    baseBind.ocrModelRela,
                    ceui.pixiv.ui.translate.MangaOcrModelManager.INSTANCE,
                    ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE);
        }

        // 自定义 AI 翻译（#975）— OpenAI 兼容接口替代内置 Google web 翻译
        baseBind.aiTranslateRela.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
            intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "自定义AI翻译");
            startActivity(intent);
        });
    }

    private void updateModelStatus() {
        if (baseBind == null) return;

        ceui.pixiv.ui.interpolate.RifeModel rife = ceui.pixiv.ui.interpolate.RifeModel.RIFE_V4_6;
        boolean rifeReady = ceui.pixiv.ui.interpolate.RifeModelManager.INSTANCE.isModelReady(mContext, rife);
        baseBind.rifeModelStatus.setText(rifeReady
                ? rife.getDisplayName()
                : getString(R.string.string_model_not_ready, rife.getSizeLabel()));

        ceui.pixiv.ui.translate.ComicTextDetectorModel ctd = ceui.pixiv.ui.translate.ComicTextDetectorModel.CTD_BASE;
        boolean ctdReady = ceui.pixiv.ui.translate.ComicTextDetectorModelManager.INSTANCE.isModelReady(mContext, ctd);
        baseBind.bubbleDetectorModelStatus.setText(ctdReady
                ? ctd.getDisplayName()
                : getString(R.string.string_model_not_ready, ctd.getSizeLabel()));

        ceui.pixiv.ui.translate.MangaOcrModel ocr = ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE;
        boolean ocrReady = ceui.pixiv.ui.translate.MangaOcrModelManager.INSTANCE.isModelReady(mContext, ocr);
        baseBind.ocrModelStatus.setText(ocrReady
                ? ocr.getDisplayName()
                : getString(R.string.string_model_not_ready, ocr.getSizeLabel()));

        // 已配置并启用 → 显示模型名；否则显示「未设置」
        baseBind.aiTranslateStatus.setText(ceui.pixiv.ui.translate.AiTranslator.INSTANCE.isActive()
                ? Shaft.sSettings.getAiTranslateModel()
                : getString(R.string.string_not_set));
    }

    // 长按 Settings 模型行直接删除。未下载状态长按提示用户先下载；
    // 已下载状态弹 QMUI 确认对话框，确认后删除并刷新右侧状态文字。
    private void bindLongPressDeleteModel(View row,
                                          ceui.pixiv.ui.common.ModelDownloadManager mgr,
                                          ceui.pixiv.ui.common.DownloadableModel model) {
        row.setOnLongClickListener(v -> {
            if (mContext == null) return false;
            if (!mgr.isModelReady(mContext, model)) {
                Common.showToast(getString(R.string.string_rembg_model_long_press_to_delete));
                return true;
            }
            new WitDialog.MessageDialogBuilder(getActivity())
                    .setTitle(R.string.string_rembg_model_delete_confirm_title)
                    .setMessage(getString(R.string.string_rembg_model_delete_confirm_message, model.getDisplayName()))
                    .setSkinManager(WitSkinManager.defaultInstance(mContext))
                    .addAction(0, getString(R.string.string_cancel), WitDialogAction.ACTION_PROP_NEUTRAL,
                            (d, i) -> d.dismiss())
                    .addAction(0, getString(R.string.string_rembg_model_delete), WitDialogAction.ACTION_PROP_NEGATIVE,
                            (d, i) -> {
                                d.dismiss();
                                mgr.deleteModel(mContext, model);
                                // RIFE 模型删除后清掉播放引擎内存里记的补帧 gif —— 否则本会话
                                // 看过的作品仍播补帧缓存、新作品走原速,同会话行为不一致
                                if (mgr == ceui.pixiv.ui.interpolate.RifeModelManager.INSTANCE) {
                                    ceui.pixiv.ui.bulk.UgoiraEngine.invalidateAll();
                                }
                                updateModelStatus();
                                Common.showToast(getString(R.string.string_rembg_model_delete_done, model.getDisplayName()));
                            })
                    .show();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateModelStatus();
    }
}
