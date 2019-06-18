package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.CardView;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.rengwuxian.materialedittext.MaterialEditText;

import ceui.lisa.R;
import ceui.lisa.activities.CoverActivity;
import ceui.lisa.activities.LoginActivity;
import ceui.lisa.http.Retro;
import ceui.lisa.response.UserBean;
import ceui.lisa.utils.Local;
import ceui.lisa.response.UserModel;
import ceui.lisa.utils.Common;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class FragmentLogin extends NetworkFragment<UserModel> {

    public static final String CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT";
    public static final String CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj";
    public static final String DEVICE_TOKEN = "pixiv";

    private ProgressBar mProgressBar;
    private MaterialEditText userName, password;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_login;
    }

    @Override
    View initView(View v) {
        mProgressBar = v.findViewById(R.id.progress);
        userName = v.findViewById(R.id.user_name);
        password = v.findViewById(R.id.password);
        CardView login = v.findViewById(R.id.login);
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                api = initApi();
                login();
            }
        });
        TextView noAccount = v.findViewById(R.id.has_no_account);
        noAccount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((LoginActivity) getActivity()).showSignFragment();
            }
        });
        return v;
    }

    @Override
    void initData() {
        // do nothing here
    }

    @Override
    Observable<UserModel> initApi() {
        Common.showToast("初始化 api");
        api = Retro.getAccountApi().login(
                CLIENT_ID,
                CLIENT_SECRET,
                DEVICE_TOKEN,
                true,
                "password",
                true,
                password.getText().toString(),
                userName.getText().toString());
        return api;
    }

    private void login(){
        Common.hideKeyboard(mActivity);
        mProgressBar.setVisibility(View.VISIBLE);
        api.subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<UserModel>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(UserModel userModel) {
                        if(userModel != null){
                            UserBean.ProfileImageUrlsBean profile_image_urls = userModel.getResponse().getUser().getProfile_image_urls();
                            profile_image_urls.setMedium(profile_image_urls.getPx_50x50());

                            userModel.getResponse().getUser().setPassword(password.getText().toString());
                            Local.saveUser(userModel);
                            mProgressBar.setVisibility(View.INVISIBLE);
                            Intent intent = new Intent(mContext, CoverActivity.class);
                            startActivity(intent);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }
}
