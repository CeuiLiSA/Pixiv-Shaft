package ceui.lisa.fragments;

import android.text.TextUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
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

    private boolean canChangePixivID = false;

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
                            canChangePixivID = novelDetail.getUser_state().isCan_change_pixiv_id();
                            baseBind.pixivId.setEnabled(canChangePixivID);
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
        if (canChangePixivID) {
            //可以修改pixivID
            if (TextUtils.isEmpty(baseBind.pixivId.getText().toString())) {
                //pixiv ID为空
                Common.showToast("pixiv ID不能为空");
                return;
            }
            if (TextUtils.isEmpty(baseBind.userPassword.getText().toString())) {
                //新密码为空
                Common.showToast("新密码不能为空");
                return;
            }
            if (TextUtils.isEmpty(baseBind.emailAddress.getText().toString())) {
                //邮箱地址为空
                if (baseBind.pixivId.getText().toString().equals(sUserModel.getResponse().getUser().getAccount()) &&
                        baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                    Common.showToast("你还没有做任何修改");
                } else if (baseBind.pixivId.getText().toString().equals(sUserModel.getResponse().getUser().getAccount()) &&
                        !baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                    Common.showToast("正在修改密码");
                    Retro.getSignApi().changePassword(sUserModel.getResponse().getAccess_token(),
                            sUserModel.getResponse().getUser().getPassword(),
                            baseBind.userPassword.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    sUserModel.getResponse().getUser().setPassword(baseBind.userPassword.getText().toString());
                                    saveUser();
                                    mActivity.finish();
                                    Common.showToast("密码修改成功");
                                }
                            });
                } else if (!baseBind.pixivId.getText().toString().equals(sUserModel.getResponse().getUser().getAccount()) &&
                        baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                    Common.showToast("正在修改PixivID");
                    Retro.getSignApi().changePixivID(sUserModel.getResponse().getAccess_token(),
                            baseBind.pixivId.getText().toString(),
                            sUserModel.getResponse().getUser().getPassword())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    sUserModel.getResponse().getUser().setAccount(baseBind.pixivId.getText().toString());
                                    saveUser();
                                    mActivity.finish();
                                    Common.showToast("PixivID修改成功");
                                }
                            });
                } else if (!baseBind.pixivId.getText().toString().equals(sUserModel.getResponse().getUser().getAccount()) &&
                        !baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                    Common.showToast("正在修改PixivID 和密码");
                    Retro.getSignApi().changePasswordPixivID(sUserModel.getResponse().getAccess_token(),
                            baseBind.pixivId.getText().toString(),
                            sUserModel.getResponse().getUser().getPassword(),
                            baseBind.userPassword.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    sUserModel.getResponse().getUser().setAccount(baseBind.pixivId.getText().toString());
                                    sUserModel.getResponse().getUser().setPassword(baseBind.userPassword.getText().toString());
                                    saveUser();
                                    mActivity.finish();
                                    Common.showToast("PixivID 和密码修改成功");
                                }
                            });
                }
            } else {
                if (TextUtils.isEmpty(baseBind.pixivId.getText().toString())) {
                    //pixiv ID为空
                    Common.showToast("pixiv ID不能为空");
                    return;
                }
                if (TextUtils.isEmpty(baseBind.userPassword.getText().toString())) {
                    //新密码为空
                    Common.showToast("新密码不能为空");
                    return;
                }

                if (baseBind.pixivId.getText().toString().equals(sUserModel.getResponse().getUser().getAccount()) &&
                        baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                    Retro.getSignApi().changeEmail(sUserModel.getResponse().getAccess_token(),
                            baseBind.emailAddress.getText().toString(),
                            sUserModel.getResponse().getUser().getPassword())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    mActivity.finish();
                                    Common.showToast("验证邮件发送成功！", true);
                                }
                            });
                } else if (!baseBind.pixivId.getText().toString().equals(sUserModel.getResponse().getUser().getAccount()) &&
                        baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                    Retro.getSignApi().changeEmailAndPixivID(sUserModel.getResponse().getAccess_token(),
                            baseBind.emailAddress.getText().toString(),
                            baseBind.pixivId.getText().toString(),
                            sUserModel.getResponse().getUser().getPassword())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    sUserModel.getResponse().getUser().setAccount(baseBind.pixivId.getText().toString());
                                    saveUser();
                                    mActivity.finish();
                                    Common.showToast("验证邮件发送成功！", true);
                                }
                            });
                } else if (baseBind.pixivId.getText().toString().equals(sUserModel.getResponse().getUser().getAccount()) &&
                        !baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                    Retro.getSignApi().changeEmailAndPassword(sUserModel.getResponse().getAccess_token(),
                            baseBind.emailAddress.getText().toString(),
                            sUserModel.getResponse().getUser().getPassword(),
                            baseBind.userPassword.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    sUserModel.getResponse().getUser().setPassword(baseBind.userPassword.getText().toString());
                                    saveUser();
                                    mActivity.finish();
                                    Common.showToast("验证邮件发送成功！", true);
                                }
                            });
                } else if (!baseBind.pixivId.getText().toString().equals(sUserModel.getResponse().getUser().getAccount()) &&
                        !baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                    Retro.getSignApi().edit(
                            sUserModel.getResponse().getAccess_token(),
                            baseBind.emailAddress.getText().toString(),
                            baseBind.pixivId.getText().toString(),
                            sUserModel.getResponse().getUser().getPassword(),
                            baseBind.userPassword.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    sUserModel.getResponse().getUser().setPassword(baseBind.userPassword.getText().toString());
                                    sUserModel.getResponse().getUser().setAccount(baseBind.pixivId.getText().toString());
                                    saveUser();
                                    mActivity.finish();
                                    Common.showToast("验证邮件发送成功！", true);
                                }
                            });
                }
            }
        } else {
            //不可以修改pixivID
            if (TextUtils.isEmpty(baseBind.userPassword.getText().toString())) {
                //新密码为空
                Common.showToast("新密码不能为空");
                return;
            }
            if (TextUtils.isEmpty(baseBind.emailAddress.getText().toString())) {
                //邮箱地址为空
                if (baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                    Common.showToast("你还没有做任何修改");
                } else {
                    Common.showToast("正在修改密码");
                    Retro.getSignApi().changePassword(sUserModel.getResponse().getAccess_token(),
                            sUserModel.getResponse().getUser().getPassword(),
                            baseBind.userPassword.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new NullCtrl<AccountEditResponse>() {
                                @Override
                                public void success(AccountEditResponse accountEditResponse) {
                                    sUserModel.getResponse().getUser().setPassword(baseBind.userPassword.getText().toString());
                                    saveUser();
                                    mActivity.finish();
                                    Common.showToast("密码修改成功");
                                }
                            });
                }
            } else {
                //邮箱地址不为空
                if (baseBind.emailAddress.getText().toString().equals(sUserModel.getResponse().getUser().getMail_address())) {
                    if (baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                        Common.showToast("你还没有做任何修改");
                    } else {
                        Common.showToast("正在修改密码");
                        Retro.getSignApi().changePassword(sUserModel.getResponse().getAccess_token(),
                                sUserModel.getResponse().getUser().getPassword(),
                                baseBind.userPassword.getText().toString())
                                .subscribeOn(Schedulers.newThread())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(new NullCtrl<AccountEditResponse>() {
                                    @Override
                                    public void success(AccountEditResponse accountEditResponse) {
                                        sUserModel.getResponse().getUser().setPassword(baseBind.userPassword.getText().toString());
                                        saveUser();
                                        mActivity.finish();
                                        Common.showToast("密码修改成功");
                                    }
                                });
                    }
                } else {
                    if (baseBind.userPassword.getText().toString().equals(sUserModel.getResponse().getUser().getPassword())) {
                        Retro.getSignApi().changeEmail(sUserModel.getResponse().getAccess_token(),
                                baseBind.emailAddress.getText().toString(),
                                sUserModel.getResponse().getUser().getPassword())
                                .subscribeOn(Schedulers.newThread())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(new NullCtrl<AccountEditResponse>() {
                                    @Override
                                    public void success(AccountEditResponse accountEditResponse) {
                                        mActivity.finish();
                                        Common.showToast("验证邮件发送成功！", true);
                                    }
                                });
                    } else {
                        Retro.getSignApi().changeEmailAndPassword(
                                sUserModel.getResponse().getAccess_token(),
                                baseBind.emailAddress.getText().toString(),
                                sUserModel.getResponse().getUser().getPassword(),
                                baseBind.userPassword.getText().toString())
                                .subscribeOn(Schedulers.newThread())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(new NullCtrl<AccountEditResponse>() {
                                    @Override
                                    public void success(AccountEditResponse accountEditResponse) {
                                        sUserModel.getResponse().getUser().setPassword(baseBind.userPassword.getText().toString());
                                        saveUser();
                                        mActivity.finish();
                                        Common.showToast("验证邮件发送成功！", true);
                                    }
                                });
                    }
                }
            }
        }
    }

    private void saveUser() {
        Local.saveUser(sUserModel);
        UserEntity userEntity = new UserEntity();
        userEntity.setLoginTime(System.currentTimeMillis());
        userEntity.setUserID(sUserModel.getResponse().getUser().getId());
        userEntity.setUserGson(Shaft.sGson.toJson(sUserModel));
        AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(userEntity);
    }
}
