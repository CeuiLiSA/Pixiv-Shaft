package ceui.lisa.fragments;

import android.content.Intent;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.FragmentLocalUserBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.Base64Util;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import de.hdodenhof.circleimageview.CircleImageView;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentLocalUsers extends BaseFragment<FragmentLocalUserBinding> {

    private List<UserModel> allItems = new ArrayList<>();

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_local_user;
    }

    @Override
    public void initView() {
        baseBind.toolbar.toolbarTitle.setText(R.string.string_251);
        baseBind.toolbar.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.finish();
            }
        });
        baseBind.addUser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "登录注册");
                startActivity(intent);
            }
        });
    }

    @Override
    protected void initData() {
        Observable.create((ObservableOnSubscribe<List<UserEntity>>) emitter -> {
            List<UserEntity> temp = AppDatabase.getAppDatabase(mContext)
                    .downloadDao().getAllUser();
            emitter.onNext(temp);
        })
                .map(new Function<List<UserEntity>, List<UserModel>>() {
                    @Override
                    public List<UserModel> apply(List<UserEntity> userEntities) throws Exception {
                        allItems = new ArrayList<>();
                        for (int i = 0; i < userEntities.size(); i++) {
                            allItems.add(Shaft.sGson.fromJson(userEntities.get(i).getUserGson(), UserModel.class));
                        }
                        return allItems;
                    }
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<List<UserModel>>() {
                    @Override
                    public void success(List<UserModel> userModels) {
                        if (userModels.size() != 0) {
                            for (int i = 0; i < userModels.size(); i++) {
                                View v = View.inflate(mContext, R.layout.recy_local_user, null);
                                bindData(v, userModels.get(i));
                                baseBind.userList.addView(v);
                            }
                        }
                    }
                });
    }


    private void bindData(View v, UserModel userModel) {
        TextView userName = v.findViewById(R.id.user_name);
        TextView loginTime = v.findViewById(R.id.login_time);
        TextView doublePwd = v.findViewById(R.id.double_pwd);
        CircleImageView userHead = v.findViewById(R.id.user_head);
        ImageView current = v.findViewById(R.id.current_user);
        ImageView exp = v.findViewById(R.id.export_user);
        TextView showPwd = v.findViewById(R.id.show_pwd);
        userName.setText(String.format("%s (%s)", userModel.getResponse().getUser().getName(),
                userModel.getResponse().getUser().getAccount()));
//        loginTime.setText(TextUtils.isEmpty(userModel.getResponse().getUser().getMail_address()) ?
//                "未绑定邮箱" : userModel.getResponse().getUser().getMail_address());
        loginTime.setText(userModel.getResponse().getUser().getPassword());
        doublePwd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.copy(mContext, userModel.getResponse().getUser().getPassword());
            }
        });
        showPwd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showPwd.getText().toString().equals("显示")) {
                    showPwd.setText("隐藏");
                    loginTime.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                } else {
                    showPwd.setText("显示");
                    loginTime.setTransformationMethod(PasswordTransformationMethod.getInstance());
                }
            }
        });
        Glide.with(mContext).load(GlideUtil.getHead(userModel.getResponse().getUser())).into(userHead);
        current.setVisibility(userModel.getResponse().getUser().getId() ==
                sUserModel.getResponse().getUser().getId() ? View.VISIBLE : View.GONE);
        exp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                userModel.getResponse().setLocal_user(Params.USER_KEY);
                //生成加密后的密码
                String secretPassword = Base64Util.encode(userModel.getResponse().getUser().getPassword());
                //添加一个标识，是已加密的密码
                String passwordWithSign = Params.SECRET_PWD_KEY + secretPassword;
                userModel.getResponse().getUser().setPassword(passwordWithSign);
                String userJson = Shaft.sGson.toJson(userModel);
                Common.copy(mContext, userJson, false);
                Common.showToast("已导出到剪切板", 2);
            }
        });

        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Local.saveUser(userModel);
                Dev.refreshUser = true;
                Shaft.sUserModel = userModel;
                Intent intent = new Intent(mContext, MainActivity.class);
                MainActivity.newInstance(intent, mContext);
                mActivity.finish();
            }
        });
    }
}
