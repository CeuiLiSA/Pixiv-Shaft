package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import ceui.pixiv.witstudio.dialog.WitSkinManager;
import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsAccountBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

/** 设置 · 账号 */
public class FragmentSettingsAccount extends SettingsPageFragment<FragmentSettingsAccountBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_account;
    }

    @Override
    protected void initData() {
        baseBind.userManage.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "账号管理");
            startActivity(intent);
        });

        baseBind.editAccount.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "绑定邮箱");
            startActivity(intent);
        });

        // Google Play 渠道合规：邮箱备份会把用户邮箱传到 pixshaft-api，而数据安全表单
        // 未声明「电子邮件地址」收集（40760 被 Play 政策标记）。lite 渠道隐藏该入口。
        if (ceui.lisa.BuildConfig.IS_LITE) {
            baseBind.accountBackupDivider.setVisibility(View.GONE);
            baseBind.accountBackup.setVisibility(View.GONE);
        } else {
            baseBind.accountBackup.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "邮箱备份");
                intent.putExtra("mode", "backup");
                startActivity(intent);
            });
        }

        baseBind.editFile.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "编辑个人资料");
            startActivity(intent);
        });

        baseBind.workSpace.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "我的作业环境");
            startActivity(intent);
        });

        baseBind.r18Space.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
            intent.putExtra(Params.URL, Params.URL_R18_SETTING);
            startActivity(intent);
        });

        baseBind.premiumSpace.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
            intent.putExtra(Params.URL, Params.URL_PREMIUM_SETTING);
            startActivity(intent);
        });

        baseBind.loginOut.setOnClickListener(v -> {
            WitDialog.CheckBoxMessageDialogBuilder builder = new WitDialog.CheckBoxMessageDialogBuilder(getActivity());
            builder
                    .setTitle(getString(R.string.string_185))
                    .setMessage(getString(R.string.string_186))
                    .setChecked(true)
                    .setSkinManager(WitSkinManager.defaultInstance(mContext))
                    .addAction(getString(R.string.string_187), new WitDialogAction.ActionListener() {
                        @Override
                        public void onClick(WitDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    .addAction(R.string.login_out, new WitDialogAction.ActionListener() {
                        @Override
                        public void onClick(WitDialog dialog, int index) {
                            Common.logOut(mContext, builder.isChecked());
                            mActivity.finish();
                            dialog.dismiss();
                        }
                    })
                    .create()
                    .show();
        });
    }
}
