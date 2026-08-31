package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsAccountBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.pixiv.ui.navigation.TemplateRoute;

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
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.ACCOUNT_SWITCH.key);
            startActivity(intent);
        });

        baseBind.editAccount.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.EDIT_ACCOUNT.key);
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
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.EMAIL_BACKUP.key);
                intent.putExtra("mode", "backup");
                startActivity(intent);
            });
        }

        baseBind.editFile.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.EDIT_PROFILE.key);
            startActivity(intent);
        });

        baseBind.workSpace.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WORKSPACE.key);
            startActivity(intent);
        });

        baseBind.r18Space.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_LINK.key);
            intent.putExtra(Params.URL, Params.URL_R18_SETTING);
            startActivity(intent);
        });

        baseBind.premiumSpace.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_LINK.key);
            intent.putExtra(Params.URL, Params.URL_PREMIUM_SETTING);
            startActivity(intent);
        });

        baseBind.loginOut.setOnClickListener(v -> {
            WitDialog.CheckBoxMessageDialogBuilder builder = new WitDialog.CheckBoxMessageDialogBuilder(getActivity());
            builder
                    .setTitle(getString(R.string.string_185))
                    .setMessage(getString(R.string.string_186))
                    .setChecked(true)
                    .addAction(getString(R.string.string_187), new WitDialogAction.ActionListener() {
                        @Override
                        public void onClick(WitDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    // NEGATIVE → wit_danger:退出登录是破坏性操作,按钮同样标红(日夜各一版,不跟主题色)。
                    .addAction(0, R.string.login_out, WitDialogAction.ACTION_PROP_NEGATIVE, new WitDialogAction.ActionListener() {
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
