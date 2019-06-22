package ceui.lisa.fragments;

import android.support.v7.widget.CardView;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.rengwuxian.materialedittext.MaterialEditText;

import ceui.lisa.R;
import ceui.lisa.activities.LoginActivity;
import ceui.lisa.model.UserModel;
import io.reactivex.Observable;

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
