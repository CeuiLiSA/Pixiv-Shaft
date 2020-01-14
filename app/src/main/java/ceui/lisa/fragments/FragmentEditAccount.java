package ceui.lisa.fragments;

import android.text.TextUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentEditAccountBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.models.AccountEditResponse;
import ceui.lisa.models.UserState;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentEditAccount extends BaseBindFragment<FragmentEditAccountBinding> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_edit_account;
    }

    @Override
    void initData() {
        Retro.getAppApi().getAccountState(Shaft.sUserModel.getResponse().getAccess_token())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<UserState>() {
                    @Override
                    public void success(UserState novelDetail) {
                        if (novelDetail.getUser_state() != null) {
                            baseBind.pixivId.setEnabled(novelDetail.getUser_state().isCan_change_pixiv_id());
                        }
                    }
                });
        if (!TextUtils.isEmpty(Shaft.sUserModel.getResponse().getUser().getMail_address())) {
            baseBind.emailAddress.setText(Shaft.sUserModel.getResponse().getUser().getMail_address());
        }
        baseBind.userPassword.setText(Shaft.sUserModel.getResponse().getUser().getPassword());
        baseBind.pixivId.setText(Shaft.sUserModel.getResponse().getUser().getAccount());
        baseBind.pixivId.setEnabled(false);
        baseBind.submit.setOnClickListener(v -> submit());
    }

    private void submit() {
        if (!TextUtils.isEmpty(Shaft.sUserModel.getResponse().getUser().getMail_address())) {
            if (Shaft.sUserModel.getResponse().getUser().isIs_mail_authorized()) {
                if (Shaft.sUserModel.getResponse().getUser().getPassword().equals(
                        baseBind.userPassword.getText().toString()) &&
                        Shaft.sUserModel.getResponse().getUser().getMail_address().equals(
                                baseBind.emailAddress.getText().toString())) {
                    Common.showToast("你还没有做任何修改");
                } else if (!Shaft.sUserModel.getResponse().getUser().getPassword().equals(
                        baseBind.userPassword.getText().toString()) &&
                        !Shaft.sUserModel.getResponse().getUser().getMail_address().equals(
                                baseBind.emailAddress.getText().toString())) {
                    //改邮箱 + 改密码
                    Retro.getSignApi().changeEmailAndAddress(
                            Shaft.sUserModel.getResponse().getAccess_token(),
                            baseBind.emailAddress.getText().toString(),
                            Shaft.sUserModel.getResponse().getUser().getPassword(),
                            baseBind.userPassword.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    if (!accountEditResponse.isError() &&
                                            accountEditResponse.getBody() != null &&
                                            accountEditResponse.getBody().isIs_succeed()) {
                                        Common.showToast("验证邮件发送成功!", true);
                                        mActivity.finish();
                                    }
                                }
                            });
                } else if (!Shaft.sUserModel.getResponse().getUser().getPassword().equals(
                        baseBind.userPassword.getText().toString()) &&
                        Shaft.sUserModel.getResponse().getUser().getMail_address().equals(
                                baseBind.emailAddress.getText().toString())) {
                    //只改密码
                    Retro.getSignApi().changePassword(
                            Shaft.sUserModel.getResponse().getAccess_token(),
                            Shaft.sUserModel.getResponse().getUser().getPassword(),
                            baseBind.userPassword.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    if (!accountEditResponse.isError() &&
                                            accountEditResponse.getBody() != null &&
                                            accountEditResponse.getBody().isIs_succeed()) {
                                        Common.showToast("密码修改成功!");
                                        mActivity.finish();
                                    }
                                }
                            });
                } else if (Shaft.sUserModel.getResponse().getUser().getPassword().equals(
                        baseBind.userPassword.getText().toString()) &&
                        !Shaft.sUserModel.getResponse().getUser().getMail_address().equals(
                                baseBind.emailAddress.getText().toString())) {
                    //只改邮箱
                    Retro.getSignApi().changeEmail(
                            Shaft.sUserModel.getResponse().getAccess_token(),
                            baseBind.emailAddress.getText().toString(),
                            Shaft.sUserModel.getResponse().getUser().getPassword())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    if (!accountEditResponse.isError() &&
                                            accountEditResponse.getBody() != null &&
                                            accountEditResponse.getBody().isIs_succeed()) {
                                        Common.showToast("验证邮件发送成功!", true);
                                        mActivity.finish();
                                    }
                                }
                            });
                }
            } else {
                Retro.getSignApi().changeEmailAndAddress(
                        Shaft.sUserModel.getResponse().getAccess_token(),
                        baseBind.emailAddress.getText().toString(),
                        Shaft.sUserModel.getResponse().getUser().getPassword(),
                        baseBind.userPassword.getText().toString())
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new NullCtrl<AccountEditResponse>() {
                            @Override
                            public void success(AccountEditResponse accountEditResponse) {
                                if (!accountEditResponse.isError() &&
                                        accountEditResponse.getBody() != null &&
                                        accountEditResponse.getBody().isIs_succeed()) {
                                    Common.showToast("验证邮件发送成功!", true);
                                    mActivity.finish();
                                }
                            }
                        });
            }
        } else {
            if (TextUtils.isEmpty(baseBind.emailAddress.getText().toString())) {
                if (Shaft.sUserModel.getResponse().getUser().getAccount().equals(baseBind.pixivId.getText().toString())) {
                    if (TextUtils.isEmpty(baseBind.userPassword.getText().toString())) {
                        Common.showToast("新密码不能为空");
                        return;
                    }
                    if (baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                        Common.showToast("你还没有做任何改变");
                        return;
                    }
                    Retro.getSignApi().changePassword(
                            Shaft.sUserModel.getResponse().getAccess_token(),
                            Shaft.sUserModel.getResponse().getUser().getPassword(),
                            baseBind.userPassword.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    if (!accountEditResponse.isError() &&
                                            accountEditResponse.getBody() != null &&
                                            accountEditResponse.getBody().isIs_succeed()) {
                                        sUserModel.getResponse().getUser().setPassword(baseBind.userPassword.getText().toString());
                                        Local.saveUser(sUserModel);
                                        Common.showToast("密码修改成功!");
                                        mActivity.finish();
                                    }
                                }
                            });
                } else {
                    if (TextUtils.isEmpty(baseBind.userPassword.getText().toString())) {
                        Common.showToast("新密码不能为空");
                        return;
                    }
                    if (TextUtils.isEmpty(baseBind.pixivId.getText().toString())) {
                        Common.showToast("pixiv ID不能为空");
                        return;
                    }
                    Retro.getSignApi().changePasswordAndAddress(
                            Shaft.sUserModel.getResponse().getAccess_token(),
                            baseBind.pixivId.getText().toString(),
                            Shaft.sUserModel.getResponse().getUser().getPassword(),
                            baseBind.userPassword.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    if (!accountEditResponse.isError() &&
                                            accountEditResponse.getBody() != null &&
                                            accountEditResponse.getBody().isIs_succeed()) {
                                        Common.showToast("密码修改成功!");
                                        mActivity.finish();
                                    }
                                }
                            });
                }
            } else {
                if (TextUtils.isEmpty(baseBind.emailAddress.getText().toString())) {
                    Common.showToast("邮箱地址不能为空");
                    return;
                }
                if (TextUtils.isEmpty(baseBind.userPassword.getText().toString())) {
                    Common.showToast("新密码不能为空");
                    return;
                }
                if (TextUtils.isEmpty(baseBind.pixivId.getText().toString())) {
                    Common.showToast("pixiv ID不能为空");
                    return;
                }
                Retro.getSignApi().edit(
                        sUserModel.getResponse().getAccess_token(),
                        baseBind.emailAddress.getText().toString(),
                        baseBind.pixivId.getText().toString(),
                        Shaft.sUserModel.getResponse().getUser().getPassword(),
                        baseBind.userPassword.getText().toString())
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new NullCtrl<AccountEditResponse>() {
                            @Override
                            public void success(AccountEditResponse accountEditResponse) {
                                if (!accountEditResponse.isError() &&
                                        accountEditResponse.getBody() != null &&
                                        accountEditResponse.getBody().isIs_succeed()) {
                                    Common.showToast("验证邮件发送成功!", true);
                                    mActivity.finish();
                                }
                            }
                        });
            }
        }
    }
}
