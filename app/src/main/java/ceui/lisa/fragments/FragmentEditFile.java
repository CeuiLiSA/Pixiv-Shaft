package ceui.lisa.fragments;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.view.View;

import androidx.annotation.Nullable;

import com.blankj.utilcode.util.UriUtils;
import com.bumptech.glide.Glide;
import com.zhihu.matisse.Matisse;
import com.zhihu.matisse.MimeType;
import com.zhihu.matisse.engine.impl.GlideEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentEditFileBinding;
import ceui.lisa.download.FileSizeUtil;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.models.NullResponse;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentEditFile extends BaseFragment<FragmentEditFileBinding> {

    private File imageFile = null;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_edit_file;
    }

    @Override
    void initData() {
        if (sUserModel == null) {
            Common.showToast("你还没有登录");
            mActivity.finish();
            return;
        }
        Glide.with(mContext)
                .load(GlideUtil.getHead(sUserModel.getResponse().getUser()))
                .into(baseBind.userHead);
        baseBind.submit.setOnClickListener(v -> submit());
        baseBind.changeHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Matisse.from(mActivity)
                        .choose(MimeType.ofImage())// 选择 mime 的类型
                        .countable(true)
                        .maxSelectable(1) // 图片选择的最多数量
                        .restrictOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                        .thumbnailScale(1.0f) // 缩略图的比例
                        .imageEngine(new GlideEngine()) // 使用的图片加载引擎
                        .forResult(Params.REQUEST_CODE_CHOOSE);
            }
        });
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
    }

    private void submit() {
        if (imageFile == null) {
            Common.showToast("你还没有做任何改变");
            return;
        }

        if (imageFile.length() >= 5 * 1024 * 1024) {
            Common.showToast("照片不能大于5M，本张照片大小：" +
                    FileSizeUtil.getFileOrFilesSize(imageFile, FileSizeUtil.SIZETYPE_MB) + "M");
            return;
        }

        baseBind.progress.setVisibility(View.VISIBLE);

        RequestBody imageBody = RequestBody.create(MediaType.parse("image/jpeg"), imageFile);
        MultipartBody.Part imagePart = MultipartBody.Part.createFormData("profile_image", imageFile.getName(), imageBody);


        List<MultipartBody.Part> parts = new ArrayList<>();
        parts.add(imagePart);

        Retro.getAppApi().updateUserProfile(sUserModel.getResponse().getAccess_token(), parts)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<NullResponse>() {
                    @Override
                    public void success(NullResponse nullResponse) {
                        Common.showToast("修改成功！");
                        //修改好了之后刷新用户信息
                        PixivOperate.refreshUserData(sUserModel, new Callback<UserModel>() {
                            @Override
                            public void onResponse(Call<UserModel> call, Response<UserModel> response) {
                                if (response != null) {
                                    UserModel newUser = response.body();
                                    if (newUser != null) {
                                        newUser.getResponse().getUser().setPassword(sUserModel.getResponse().getUser().getPassword());
                                        newUser.getResponse().getUser().setIs_login(true);
                                        Local.saveUser(newUser);
                                        Dev.refreshUser = true;
                                        mActivity.finish();
                                    }
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

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        Common.showLog(className + "activity result");
        if (requestCode == Params.REQUEST_CODE_CHOOSE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                List<Uri> result = Matisse.obtainResult(data);
                if (result != null && result.size() == 1) {
                    imageFile = UriUtils.uri2File(result.get(0));
                    Glide.with(mContext)
                            .load(imageFile)
                            .into(baseBind.userHead);
                }
            }
        }
    }
}
