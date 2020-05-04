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
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.FragmentLocalUserBinding;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.model.ExportUser;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.ClipBoardUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.PixivOperate;
import de.hdodenhof.circleimageview.CircleImageView;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentLocalUsers extends BaseFragment<FragmentLocalUserBinding> {

    //private SimpleDateFormat formatter = new SimpleDateFormat("MM月dd日 HH:mm:ss");
    private List<UserModel> allItems = new ArrayList<>();

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_local_user;
    }

    @Override
    public void initView(View view) {
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getActivity().finish();
            }
        });
        baseBind.loginOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "登录注册");
                startActivity(intent);
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
    void initData() {
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
                .subscribe(new ErrorCtrl<List<UserModel>>() {
                    @Override
                    public void onNext(List<UserModel> userModels) {
                        if (userModels != null) {
                            if (userModels.size() != 0) {
                                for (int i = 0; i < userModels.size(); i++) {
                                    View v = LayoutInflater.from(mContext).inflate(R.layout.recy_loal_user, null);
                                    bindData(v, userModels.get(i));
                                    baseBind.userList.addView(v);
                                }
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
                ExportUser expUser = new ExportUser();
                expUser.setUserName(userModel.getResponse().getUser().getAccount());
                expUser.setUserPassword(userModel.getResponse().getUser().getPassword());
                String userJson = Shaft.sGson.toJson(expUser);
                Common.copy(mContext, userJson, false);
                Common.showToast("已导出到剪切板", exp);
            }
        });

        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.progress.setVisibility(View.VISIBLE);
                PixivOperate.refreshUserData(userModel, new Callback<UserModel>() {
                    @Override
                    public void onResponse(Call<UserModel> call, Response<UserModel> response) {
                        if (response != null) {
                            UserModel newUser = response.body();
                            newUser.getResponse().getUser().setPassword(userModel.getResponse().getUser().getPassword());
                            newUser.getResponse().getUser().setIs_login(true);
                            Local.saveUser(newUser);
                            Dev.refreshUser = true;
                            mActivity.finish();
                        }
                        baseBind.progress.setVisibility(View.INVISIBLE);
                    }

                    @Override
                    public void onFailure(Call<UserModel> call, Throwable t) {
                        Common.showToast(t.toString());
                        baseBind.progress.setVisibility(View.INVISIBLE);
                    }
                });
            }
        });
    }
}
