package ceui.lisa.fragments;

import android.text.TextUtils;
import android.view.View;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.FragmentEditAccountBinding;
import ceui.lisa.http.LegacyApiCalls;
import ceui.pixiv.api.model.AccountResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;

import ceui.pixiv.session.SessionManager;

public class FragmentEditAccount extends BaseFragment<FragmentEditAccountBinding> {

    private boolean canChangePixivID = false;
    private boolean hasPassword = false;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_edit_account;
    }

    @Override
    protected void initData() {
        if (!SessionManager.INSTANCE.isLoggedIn()) {
            Common.showToast("你还没有登录");
            mActivity.finish();
            return;
        }
        baseBind.toolbar.toolbarTitle.setText(R.string.string_250);
        baseBind.toolbar.toolbar.setNavigationOnClickListener(v -> finish());
        LegacyApiCalls.getAccountState(this, userState -> {
            if (userState.getUser_state() != null) {
                canChangePixivID = userState.getUser_state().isCan_change_pixiv_id();
                baseBind.pixivId.setEnabled(canChangePixivID);
                hasPassword = userState.getUser_state().isHas_password();
                // 显隐挂在 TextInputLayout 上，否则外框和浮动标签会留在原地
                baseBind.userOldPasswordLayout.setVisibility(hasPassword ? View.VISIBLE : View.GONE);
            }
        });
        if (!TextUtils.isEmpty(SessionManager.INSTANCE.getMailAddress())) {
            baseBind.emailAddress.setText(SessionManager.INSTANCE.getMailAddress());
        }
        // 新登录流程中，App不直接接触密码明文，所以不显示较为合理
        // baseBind.userOldPassword.setText(Shaft.Local.getUser().getUser().getPassword());
        // baseBind.userNewPassword.setText(Shaft.Local.getUser().getUser().getPassword());
        baseBind.pixivId.setText(SessionManager.INSTANCE.getAccountName());
        baseBind.pixivId.setEnabled(false);
        baseBind.submit.setOnClickListener(v -> submit());
    }

    private void submit() {
        if(hasPassword && TextUtils.isEmpty(baseBind.userOldPassword.getText().toString())){
            Common.showToast("更新账号信息需要输入当前密码");
            return;
        }
        String currentPassword = baseBind.userOldPassword.getText().toString();
        if (canChangePixivID) {
            //可以修改pixivID
            if (TextUtils.isEmpty(baseBind.pixivId.getText().toString())) {
                //pixiv ID为空
                Common.showToast("pixiv ID不能为空");
                return;
            }
            if (TextUtils.isEmpty(baseBind.userNewPassword.getText().toString())) {
                //新密码为空
                Common.showToast("新密码不能为空");
                return;
            }
            boolean isPixivIdNotChanged = baseBind.pixivId.getText().toString().equals(SessionManager.INSTANCE.getAccountName());
            boolean isPasswordNotChanged = baseBind.userNewPassword.getText().toString().equals(currentPassword);
            if (TextUtils.isEmpty(baseBind.emailAddress.getText().toString())) {
                //邮箱地址为空
                if (isPixivIdNotChanged && isPasswordNotChanged) {
                    Common.showToast("你还没有做任何修改");
                } else if (isPixivIdNotChanged && !isPasswordNotChanged) {
                    Common.showToast("正在修改密码");
                    LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                            null, null, currentPassword, baseBind.userNewPassword.getText().toString(),
                            accountEditResponse -> {
                        Local.getUser().getUser().setPassword(baseBind.userNewPassword.getText().toString());
                        saveUser();
                        mActivity.finish();
                        Common.showToast("密码修改成功");
                    });
                } else if (!isPixivIdNotChanged && isPasswordNotChanged) {
                    Common.showToast("正在修改PixivID");
                    LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                            null, baseBind.pixivId.getText().toString(), currentPassword, null,
                            accountEditResponse -> {
                        Local.getUser().getUser().setAccount(baseBind.pixivId.getText().toString());
                        saveUser();
                        mActivity.finish();
                        Common.showToast("PixivID修改成功");
                    });
                } else if (!isPixivIdNotChanged && !isPasswordNotChanged) {
                    Common.showToast("正在修改PixivID 和密码");
                    LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                            null, baseBind.pixivId.getText().toString(), currentPassword, baseBind.userNewPassword.getText().toString(),
                            accountEditResponse -> {
                        Local.getUser().getUser().setAccount(baseBind.pixivId.getText().toString());
                        Local.getUser().getUser().setPassword(baseBind.userNewPassword.getText().toString());
                        saveUser();
                        mActivity.finish();
                        Common.showToast("PixivID 和密码修改成功");
                    });
                }
            } else {
                if (TextUtils.isEmpty(baseBind.pixivId.getText().toString())) {
                    //pixiv ID为空
                    Common.showToast("pixiv ID不能为空");
                    return;
                }
                if (TextUtils.isEmpty(baseBind.userNewPassword.getText().toString())) {
                    //新密码为空
                    Common.showToast("新密码不能为空");
                    return;
                }

                if (isPixivIdNotChanged && isPasswordNotChanged) {
                    LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                            baseBind.emailAddress.getText().toString(), null, currentPassword, null,
                            accountEditResponse -> {
                        mActivity.finish();
                        Common.showToast("验证邮件发送成功！", true);
                    });
                } else if (!isPixivIdNotChanged && isPasswordNotChanged) {
                    LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                            baseBind.emailAddress.getText().toString(), baseBind.pixivId.getText().toString(), currentPassword, null,
                            accountEditResponse -> {
                        Local.getUser().getUser().setAccount(baseBind.pixivId.getText().toString());
                        saveUser();
                        mActivity.finish();
                        Common.showToast("验证邮件发送成功！", true);
                    });
                } else if (isPixivIdNotChanged && !isPasswordNotChanged) {
                    LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                            baseBind.emailAddress.getText().toString(), null, currentPassword, baseBind.userNewPassword.getText().toString(),
                            accountEditResponse -> {
                        Local.getUser().getUser().setPassword(baseBind.userNewPassword.getText().toString());
                        saveUser();
                        mActivity.finish();
                        Common.showToast("验证邮件发送成功！", true);
                    });
                } else if (!isPixivIdNotChanged && !isPasswordNotChanged) {
                    LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                            baseBind.emailAddress.getText().toString(), baseBind.pixivId.getText().toString(), currentPassword, baseBind.userNewPassword.getText().toString(),
                            accountEditResponse -> {
                        Local.getUser().getUser().setPassword(baseBind.userNewPassword.getText().toString());
                        Local.getUser().getUser().setAccount(baseBind.pixivId.getText().toString());
                        saveUser();
                        mActivity.finish();
                        Common.showToast("验证邮件发送成功！", true);
                    });
                }
            }
        } else {
            //不可以修改pixivID
            if (TextUtils.isEmpty(baseBind.userNewPassword.getText().toString())) {
                //新密码为空
                Common.showToast("新密码不能为空");
                return;
            }
            boolean isPasswordNotChanged = baseBind.userNewPassword.getText().toString().equals(currentPassword);
            if (TextUtils.isEmpty(baseBind.emailAddress.getText().toString())) {
                //邮箱地址为空
                if (isPasswordNotChanged) {
                    Common.showToast("你还没有做任何修改");
                } else {
                    Common.showToast("正在修改密码");
                    LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                            null, null, currentPassword, baseBind.userNewPassword.getText().toString(),
                            accountEditResponse -> {
                        Local.getUser().getUser().setPassword(baseBind.userNewPassword.getText().toString());
                        saveUser();
                        mActivity.finish();
                        Common.showToast("密码修改成功");
                    });
                }
            } else {
                //邮箱地址不为空
                boolean isEmailNotChanged = baseBind.emailAddress.getText().toString().equals(Local.getUser().getUser().getMail_address());
                if (isEmailNotChanged) {
                    if (isPasswordNotChanged) {
                        Common.showToast("你还没有做任何修改");
                    } else {
                        Common.showToast("正在修改密码");
                        LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                                null, null, currentPassword, baseBind.userNewPassword.getText().toString(),
                                accountEditResponse -> {
                            Local.getUser().getUser().setPassword(baseBind.userNewPassword.getText().toString());
                            saveUser();
                            mActivity.finish();
                            Common.showToast("密码修改成功");
                        });
                    }
                } else {
                    if (isPasswordNotChanged) {
                        LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                                baseBind.emailAddress.getText().toString(), null, currentPassword, null,
                                accountEditResponse -> {
                            mActivity.finish();
                            Common.showToast("验证邮件发送成功！", true);
                        });
                    } else {
                        LegacyApiCalls.editAccount(this, SessionManager.INSTANCE.getBearerToken(),
                                baseBind.emailAddress.getText().toString(), null, currentPassword, baseBind.userNewPassword.getText().toString(),
                                accountEditResponse -> {
                            Local.getUser().getUser().setPassword(baseBind.userNewPassword.getText().toString());
                            saveUser();
                            mActivity.finish();
                            Common.showToast("验证邮件发送成功！", true);
                        });
                    }
                }
            }
        }
    }

    private void saveUser() {
        AccountResponse currentUser = Local.getUser();
        Local.saveUser(currentUser);
        UserEntity userEntity = new UserEntity();
        userEntity.setLoginTime(System.currentTimeMillis());
        userEntity.setUserID(currentUser.getUser().getId());
        userEntity.setUserGson(Shaft.sGson.toJson(currentUser));
        AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(userEntity);
    }
}
