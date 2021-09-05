package ceui.lisa.fragments;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.blankj.utilcode.util.UriUtils;
import com.bumptech.glide.Glide;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentEditFileBinding;
import ceui.lisa.download.FileSizeUtil;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Display;
import ceui.lisa.models.NullResponse;
import ceui.lisa.models.Preset;
import ceui.lisa.models.UserDetailResponse;
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

import static android.app.Activity.RESULT_OK;
import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentEditFile extends SwipeFragment<FragmentEditFileBinding> implements Display<Preset>, DatePickerDialog.OnDateSetListener {

    private File imageFile = null;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_edit_file;
    }

    @Override
    protected void initData() {
        if (sUserModel == null) {
            Common.showToast(getString(R.string.string_260));
            mActivity.finish();
            return;
        }
        Glide.with(mContext)
                .load(GlideUtil.getHead(sUserModel.getUser()))
                .into(baseBind.userHead);
        baseBind.submit.setOnClickListener(v -> submit());
        baseBind.changeHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    String[] permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
                    int i = ContextCompat.checkSelfPermission(mContext, permissions[0]);
                    if (i != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(mActivity, permissions, 1);
                    } else {
                        selectPhoto();
                    }
                } else {
                    selectPhoto();
                }
            }
        });
        baseBind.toolbar.toolbarTitle.setText(R.string.string_92);
        baseBind.toolbar.toolbar.setNavigationOnClickListener(v -> finish());

        Retro.getAppApi().getPresets(sUserModel.getAccess_token())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<Preset>() {
                    @Override
                    public void success(Preset preset) {
                        invoke(preset);
                    }
                });
    }

    private void submit() {
        baseBind.progress.setVisibility(View.VISIBLE);
        List<MultipartBody.Part> parts = new ArrayList<>();
        if (imageFile != null) {
            if (imageFile.length() >= 5 * 1024 * 1024) {
                Common.showToast(getString(R.string.string_259) +
                        FileSizeUtil.getFileOrFilesSize(imageFile, FileSizeUtil.SIZETYPE_MB) + "M");
                return;
            } else {
                RequestBody imageBody = RequestBody.create(MediaType.parse("image/jpeg"), imageFile);
                MultipartBody.Part imagePart = MultipartBody.Part.createFormData("profile_image", imageFile.getName(), imageBody);
                parts.add(imagePart);
            }
        }

        MultipartBody.Part sexPart = MultipartBody.Part.createFormData("gender", sex);
        MultipartBody.Part addressPart = MultipartBody.Part.createFormData("address", address);
        MultipartBody.Part countyPart = MultipartBody.Part.createFormData("country", country);
        MultipartBody.Part jobPart = MultipartBody.Part.createFormData("job", job);
        MultipartBody.Part userName = MultipartBody.Part.createFormData("user_name", Common.checkEmpty(baseBind.userName));
        MultipartBody.Part webPage = MultipartBody.Part.createFormData("webpage", Common.checkEmpty(baseBind.webpage));
        MultipartBody.Part twitter = MultipartBody.Part.createFormData("twitter", Common.checkEmpty(baseBind.twitter));
        MultipartBody.Part comment = MultipartBody.Part.createFormData("comment", Common.checkEmpty(baseBind.comment));
        MultipartBody.Part birthdayPart = MultipartBody.Part.createFormData("birthday", birthday);

        parts.add(sexPart);
        parts.add(addressPart);
        if (isGlobal) {
            parts.add(countyPart);
        }
        parts.add(jobPart);
        parts.add(userName);
        parts.add(webPage);
        parts.add(twitter);
        parts.add(comment);
        parts.add(birthdayPart);

        Retro.getAppApi().updateUserProfile(sUserModel.getAccess_token(), parts)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<NullResponse>() {
                    @Override
                    public void success(NullResponse nullResponse) {
                        Common.showToast(getString(R.string.string_261));
                        //修改好了之后刷新用户信息
                        PixivOperate.refreshUserData(sUserModel, new Callback<UserModel>() {
                            @Override
                            public void onResponse(Call<UserModel> call, Response<UserModel> response) {
                                if (response != null) {
                                    UserModel newUser = response.body();
                                    if (newUser != null) {
                                        newUser.getUser().setPassword(sUserModel.getUser().getPassword());
                                        newUser.getUser().setIs_login(true);
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

    private void selectPhoto() {
        Intent intentToPickPic = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intentToPickPic.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        startActivityForResult(intentToPickPic, Params.REQUEST_CODE_CHOOSE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        Common.showLog(className + "activity result");
        if (resultCode == RESULT_OK
                && requestCode == Params.REQUEST_CODE_CHOOSE
                && data != null) {
            Uri imageUri = data.getData();

            if (imageUri == null) {
                Common.showToast(getString(R.string.string_262));
                return;
            }
            imageFile = UriUtils.uri2File(imageUri);
            Glide.with(mContext)
                    .load(imageFile)
                    .into(baseBind.userHead);
        }
    }

    @Override
    public void invoke(Preset preset) {
        baseBind.address.setAdapter(new ArrayAdapter<>(mContext, R.layout.spinner_item, preset.getProfile_presets().getAddresses()));
        baseBind.address.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (preset.getProfile_presets().getAddresses().get(position).getIs_global()) {
                    baseBind.countryLl.setVisibility(View.VISIBLE);
                    isGlobal = true;
                } else {
                    baseBind.countryLl.setVisibility(View.GONE);
                    isGlobal = false;
                }
                address = String.valueOf(preset.getProfile_presets().getAddresses().get(position).getId());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        baseBind.country.setAdapter(new ArrayAdapter<>(mContext, R.layout.spinner_item, preset.getProfile_presets().getCountries()));
        baseBind.country.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                country = preset.getProfile_presets().getCountries().get(position).getCode();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        baseBind.job.setAdapter(new ArrayAdapter<>(mContext, R.layout.spinner_item, preset.getProfile_presets().getJobs()));
        baseBind.job.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                job = String.valueOf(preset.getProfile_presets().getJobs().get(position).getId());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
        List<String> sexList = new ArrayList<>();
        sexList.add("未选择");
        sexList.add("男性");
        sexList.add("女性");
        baseBind.sex.setAdapter(new ArrayAdapter<>(mContext, R.layout.spinner_item, sexList));
        baseBind.sex.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    sex = "unknown";
                } else if (position == 1) {
                    sex = "male";
                } else if (position == 2) {
                    sex = "female";
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        //加载预设信息
        Retro.getAppApi().getUserDetail(sUserModel.getAccess_token(), sUserModel.getUserId())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<UserDetailResponse>() {
                    @Override
                    public void success(UserDetailResponse user) {
                        for (int i = 0; i < preset.getProfile_presets().getAddresses().size(); i++) {
                            if (user.getProfile().getAddress_id() == preset.getProfile_presets().getAddresses().get(i).getId()) {
                                baseBind.address.setSelection(i);
                                if (preset.getProfile_presets().getAddresses().get(i).getIs_global()) {
                                    for (int j = 0; j < preset.getProfile_presets().getCountries().size(); j++) {
                                        if (!TextUtils.isEmpty(user.getProfile().getCountry_code())) {
                                            if (user.getProfile().getCountry_code().equals(preset.getProfile_presets().getCountries().get(j).getCode())) {
                                                baseBind.country.setSelection(j);
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        for (int i = 0; i < preset.getProfile_presets().getJobs().size(); i++) {
                            if (user.getProfile().getJob_id() == preset.getProfile_presets().getJobs().get(i).getId()) {
                                baseBind.job.setSelection(i);
                            }
                        }

                        if ("male".equals(user.getProfile().getGender())) {
                            baseBind.sex.setSelection(1);
                        } else if ("female".equals(user.getProfile().getGender())) {
                            baseBind.sex.setSelection(2);
                        } else {
                            baseBind.sex.setSelection(0);
                        }

                        baseBind.userName.setText(user.getUser().getName());
                        baseBind.webpage.setText(user.getProfile().getWebpage());
                        baseBind.twitter.setText(user.getProfile().getTwitter_account());
                        baseBind.comment.setText(user.getUser().getComment());
                        birthday = user.getProfile().getBirth();
                        baseBind.birthday.setText(birthday);

                        // 生日设置
                        baseBind.birthdayArea.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                DatePickerDialog dpd;
                                Calendar now = Calendar.getInstance();
                                Calendar start = Calendar.getInstance();
                                if (!TextUtils.isEmpty(birthday)) {
                                    String[] t = birthday.split("-");
                                    dpd = DatePickerDialog.newInstance(
                                            FragmentEditFile.this,
                                            Integer.parseInt(t[0]), // Initial year selection
                                            Integer.parseInt(t[1]) - 1, // Initial month selection
                                            Integer.parseInt(t[2]) // Initial day selection
                                    );
                                } else {
                                    dpd = DatePickerDialog.newInstance(
                                            FragmentEditFile.this,
                                            now.get(Calendar.YEAR) - 18, // Initial year selection
                                            0, // Initial month selection
                                            1 // Initial day selection
                                    );
                                }
                                start.set(now.get(Calendar.YEAR) - 100, 0, 1);
                                dpd.setMinDate(start);
                                dpd.setMaxDate(now);
                                dpd.setAccentColor(Common.resolveThemeAttribute(mContext, R.attr.colorPrimary));
                                dpd.setThemeDark(mContext.getResources().getBoolean(R.bool.is_night_mode));
                                dpd.show(getParentFragmentManager(), "DatePickerDialog");
                            }
                        });
                    }
                });
    }

    private String sex = "", address = "", job = "", country = "", birthday = "";
    private boolean isGlobal = false;
    private final boolean isDeleteProfileImage = false;

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
    }

    @Override
    public void onDateSet(DatePickerDialog view, int year, int monthOfYear, int dayOfMonth) {
        birthday = LocalDate.of(year, monthOfYear + 1, dayOfMonth).toString();
        baseBind.birthday.setText(birthday);
    }
}
