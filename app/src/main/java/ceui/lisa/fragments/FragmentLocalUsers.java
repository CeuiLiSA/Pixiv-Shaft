package ceui.lisa.fragments;

import android.content.Intent;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.databinding.DataBindingUtil;

import com.bumptech.glide.Glide;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.core.RxRun;
import ceui.lisa.core.RxRunnable;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.FragmentLocalUserBinding;
import ceui.lisa.databinding.RecyLocalUserBinding;
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
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
    }

    @Override
    protected void initData() {
        RxRun.runOn(new RxRunnable<List<UserModel>>() {
            @Override
            public List<UserModel> execute() {
                List<UserEntity> temp = AppDatabase.getAppDatabase(mContext)
                        .downloadDao().getAllUser();
                allItems = new ArrayList<>();
                for (int i = 0; i < temp.size(); i++) {
                    allItems.add(Shaft.sGson.fromJson(temp.get(i).getUserGson(), UserModel.class));
                }
                return allItems;
            }
        }, new NullCtrl<List<UserModel>>() {
            @Override
            public void success(List<UserModel> userModels) {
                if (userModels.size() != 0) {
                    for (int i = 0; i < userModels.size(); i++) {
                        bindData(userModels.get(i));
                    }
                }
            }
        });
    }

    private void bindData(UserModel userModel) {
        RecyLocalUserBinding binding = DataBindingUtil.inflate(
                LayoutInflater.from(mContext),
                R.layout.recy_local_user, null, false);
        binding.userName.setText(String.format("%s (%s)", userModel.getUser().getName(),
                userModel.getUser().getAccount()));
        binding.loginTime.setText(TextUtils.isEmpty(userModel.getUser().getMail_address()) ?
                "未绑定邮箱" : userModel.getUser().getMail_address());
        Glide.with(mContext).load(GlideUtil.getHead(userModel.getUser())).into(binding.userHead);
        if (sUserModel != null && sUserModel.getUser() != null && userModel.getUser().getId() ==
                sUserModel.getUser().getId()) {
            binding.currentUser.setVisibility(View.VISIBLE);
        } else {
            binding.currentUser.setVisibility(View.GONE);
        }
        binding.exportUser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                userModel.setLocal_user(Params.USER_KEY);
                String userJson = Shaft.sGson.toJson(userModel);
                Common.copy(mContext, userJson, false);
                Common.showToast("已导出到剪切板", 2);
            }
        });

        binding.getRoot().setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Local.saveUser(userModel);
                Dev.refreshUser = true;
                Shaft.sUserModel = userModel;
                Common.restart();
                mActivity.finish();
            }
        });

        binding.getRoot().setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Common.copy(mContext, String.valueOf(userModel.getUser().getAccount()));
                return true;
            }
        });
        baseBind.userList.addView(binding.getRoot());
    }
}
