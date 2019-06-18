package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.CardView;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.rengwuxian.materialedittext.MaterialEditText;

import ceui.lisa.R;
import ceui.lisa.activities.CoverActivity;
import ceui.lisa.activities.LoginActivity;
import ceui.lisa.http.Retro;
import ceui.lisa.response.UserBean;
import ceui.lisa.response.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class FragmentSign extends NetworkFragment<UserModel> {

    public static final String CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT";
    public static final String CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj";
    public static final String DEVICE_TOKEN = "pixiv";

    private ProgressBar mProgressBar;
    private MaterialEditText userName;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_sign;
    }

    @Override
    View initView(View v) {
        mProgressBar = v.findViewById(R.id.progress);
        userName = v.findViewById(R.id.user_name);
        CardView login = v.findViewById(R.id.login);
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });
        TextView goLogin = v.findViewById(R.id.go_to_login);
        goLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((LoginActivity) getActivity()).showLoginFragment();
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

        return api;
    }

}
