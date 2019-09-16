package ceui.lisa.fragments;

import android.content.Intent;
import androidx.appcompat.widget.Toolbar;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.gson.Gson;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.LoginAlphaActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.model.UserModel;
import ceui.lisa.utils.Common;
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

public class FragmentLocalUsers extends BaseFragment {

    private LinearLayout userList;
    private ProgressBar mProgressBar;
    //private SimpleDateFormat formatter = new SimpleDateFormat("MM月dd日 HH:mm:ss");
    private List<UserModel> allItems = new ArrayList<>();

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_local_user;
    }

    @Override
    View initView(View v) {
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        mProgressBar = v.findViewById(R.id.progress);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getActivity().finish();
            }
        });
        userList = v.findViewById(R.id.user_list);
        RelativeLayout loginOut = v.findViewById(R.id.login_out);
        loginOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, LoginAlphaActivity.class);
                startActivity(intent);
            }
        });

        RelativeLayout addUser = v.findViewById(R.id.add_user);
        addUser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, LoginAlphaActivity.class);
                startActivity(intent);
            }
        });
        return v;
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
                        Gson gson = new Gson();
                        allItems = new ArrayList<>();
                        for (int i = 0; i < userEntities.size(); i++) {
                            allItems.add(gson.fromJson(userEntities.get(i).getUserGson(), UserModel.class));
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
                                    userList.addView(v);
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
        ImageView delete = v.findViewById(R.id.delete_user);

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
        Glide.with(mContext).load(GlideUtil.getHead(userModel.getResponse().getUser())).into(userHead);
        current.setVisibility(userModel.getResponse().getUser().getId() ==
                sUserModel.getResponse().getUser().getId() ? View.VISIBLE : View.GONE);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showToast("删除还没做");
            }
        });

        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mProgressBar.setVisibility(View.VISIBLE);
                PixivOperate.changeUser(userModel, new Callback<UserModel>() {
                    @Override
                    public void onResponse(Call<UserModel> call, Response<UserModel> response) {
                        if (response != null) {
                            UserModel newUser = response.body();
                            if(newUser != null) {
                                newUser.getResponse().setUser(Shaft.sUserModel.getResponse().getUser());
                            }
                            Local.saveUser(newUser);
                            mProgressBar.setVisibility(View.INVISIBLE);
                            getActivity().finish();
                        }
                    }

                    @Override
                    public void onFailure(Call<UserModel> call, Throwable t) {
                        Common.showToast(t.toString());
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }
                });
            }
        });
    }
}
