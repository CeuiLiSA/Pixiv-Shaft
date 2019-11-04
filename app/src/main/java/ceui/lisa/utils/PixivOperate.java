package ceui.lisa.utils;


import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.LoginActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.IllustSearchResponse;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.NullResponse;
import ceui.lisa.model.UserModel;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Call;
import retrofit2.Callback;

import static com.blankj.utilcode.util.StringUtils.getString;

public class PixivOperate {

    public static void changeUser(UserModel userModel, Callback<UserModel> callback) {
        Call<UserModel> call = Retro.getAccountApi().refreshToken(
                LoginActivity.CLIENT_ID,
                LoginActivity.CLIENT_SECRET,
                "refresh_token",
                userModel.getResponse().getRefresh_token(),
                userModel.getResponse().getDevice_token(),
                true,
                true);
        call.enqueue(callback);
    }

    public static void postFollowUser(int userID, String followType) {
        Retro.getAppApi().postFollow(
                Local.getUser().getResponse().getAccess_token(), userID, followType)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<NullResponse>() {
                    @Override
                    public void onNext(NullResponse nullResponse) {
                        if (followType.equals(FragmentLikeIllust.TYPE_PUBLUC)) {
                            Common.showToast(getString(R.string.like_success_public));
                        } else {
                            Common.showToast(getString(R.string.like_success_private));
                        }
                    }
                });
    }

    public static void postUnFollowUser(int userID) {
        Retro.getAppApi().postUnFollow(
                Local.getUser().getResponse().getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<NullResponse>() {
                    @Override
                    public void onNext(NullResponse nullResponse) {
                        Common.showToast(getString(R.string.cancel_like));
                    }
                });
    }

    public static void postLike(IllustsBean illustsBean, UserModel userModel, String starType) {
        if (illustsBean == null) {
            return;
        }

        if (illustsBean.isIs_bookmarked()) { //已收藏
            illustsBean.setIs_bookmarked(false);
            Retro.getAppApi().postDislike(userModel.getResponse().getAccess_token(), illustsBean.getId())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void onNext(NullResponse nullResponse) {
                            Common.showToast(getString(R.string.cancel_like_illust));
                        }
                    });
        } else { //没有收藏
            illustsBean.setIs_bookmarked(true);
            Retro.getAppApi().postLike(userModel.getResponse().getAccess_token(), illustsBean.getId(), starType)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void onNext(NullResponse nullResponse) {
                            Common.showToast(getString(R.string.like_illust_success));
                        }
                    });
        }
    }

    public static void getIllustByID(UserModel userModel, int illustID, Context context) {
        Retro.getAppApi().getIllustByID(userModel.getResponse().getAccess_token(), illustID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<IllustSearchResponse>() {
                    @Override
                    public void onNext(IllustSearchResponse illustSearchResponse) {
                        if (illustSearchResponse != null) {
                            if (illustSearchResponse.getIllust() != null) {
                                List<IllustsBean> tempList = new ArrayList<>();
                                tempList.add(illustSearchResponse.getIllust());
                                IllustChannel.get().setIllustList(tempList);
                                Intent intent = new Intent(context, ViewPagerActivity.class);
                                intent.putExtra("position", 0);
                                context.startActivity(intent);
                            } else {
                                Common.showToast("illustSearchResponse.getIllust() 为空");
                            }
                        } else {
                            Common.showToast("illustSearchResponse 为空");
                        }
                    }
                });
    }

    public static void getIllustByID(UserModel userModel, int illustID, Context context,
                                     ceui.lisa.interfaces.Callback<Void> callback) {
        Retro.getAppApi().getIllustByID(userModel.getResponse().getAccess_token(), illustID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<IllustSearchResponse>() {
                    @Override
                    public void onNext(IllustSearchResponse illustSearchResponse) {
                        if (illustSearchResponse != null) {
                            if (illustSearchResponse.getIllust() != null) {
                                List<IllustsBean> tempList = new ArrayList<>();
                                tempList.add(illustSearchResponse.getIllust());
                                IllustChannel.get().setIllustList(tempList);
                                Intent intent = new Intent(context, ViewPagerActivity.class);
                                intent.putExtra("position", 0);
                                context.startActivity(intent);
                                if (callback != null) {
                                    callback.doSomething(null);
                                }
                            } else {
                                Common.showToast("illustSearchResponse.getIllust() 为空");
                            }
                        } else {
                            Common.showToast("illustSearchResponse 为空");
                        }
                    }
                });
    }
}
