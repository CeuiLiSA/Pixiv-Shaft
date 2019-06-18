package ceui.lisa.utils;


import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.response.IllustSearchResponse;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.NullResponse;
import ceui.lisa.response.UserModel;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class PixivOperate {

//    public static void followOrUnfollowClick(int id, TextView post_like_user){
//        UserModel.ResponseBean response = Local.getUser().getResponse();
//        if (id == response.getUser().getId()) {
//            Common.showToast("不能对自己操作");
//        }
//        Retro.getAppApi().getUserDetail(response.getAccess_token(), id)
//                .subscribeOn(Schedulers.newThread())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(new Observer<UserDetailResponse>() {
//                    @Override
//                    public void onSubscribe(Disposable d) {
//
//                    }
//
//                    @Override
//                    public void onNext(UserDetailResponse userDetailResponse) {
//                        UserBean user = userDetailResponse.getUser();
//
//                        if (user.isIs_followed()) {
//                            PixivOperate.postUnFollowUser(id);
//                            post_like_user.setText("+ 關注");
//                            user.setIs_followed(false);
//                        } else {
//                            PixivOperate.postFollowUser(id, "public");
//                            post_like_user.setText("取消關注");
//                            user.setIs_followed(true);
//                        }
//                    }
//
//                    @Override
//                    public void onError(Throwable e) {
//
//                    }
//
//                    @Override
//                    public void onComplete() {
//
//                    }
//                });
//    }

    public static void postFollowUser(int userID, String followType) {
        Retro.getAppApi().postFollow(
                Local.getUser().getResponse().getAccess_token(), userID, followType)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<NullResponse>() {
                    @Override
                    public void onNext(NullResponse nullResponse) {
                        if (followType.equals("public")) {
                            Common.showToast("关注成功~(公开的)");
                        } else {
                            Common.showToast("关注成功~(非公开的)");
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
                        Common.showToast("取消关注~");
                    }
                });
    }


    public static void postLike(IllustsBean illustsBean, UserModel userModel, String starType){
        if(illustsBean == null){
            return;
        }

        if(illustsBean.isIs_bookmarked()){ //已收藏
            illustsBean.setIs_bookmarked(false);
            Retro.getAppApi().postDislike(userModel.getResponse().getAccess_token(), illustsBean.getId())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void onNext(NullResponse nullResponse) {
                            Common.showToast("取消收藏");
                        }
                    });
        }else { //没有收藏
            illustsBean.setIs_bookmarked(true);
            Retro.getAppApi().postLike(userModel.getResponse().getAccess_token(), illustsBean.getId(), starType)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<NullResponse>() {
                        @Override
                        public void onNext(NullResponse nullResponse) {
                            Common.showToast("收藏成功");
                        }
                    });
        }
    }

    public static void getIllustByID(UserModel userModel, int illustID, Context context){
        Retro.getAppApi().getIllustByID(userModel.getResponse().getAccess_token(), illustID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<IllustSearchResponse>() {
                    @Override
                    public void onNext(IllustSearchResponse illustSearchResponse) {
                        if(illustSearchResponse != null){
                            if(illustSearchResponse.getIllust() != null){
                                List<IllustsBean> tempList = new ArrayList<>();
                                tempList.add(illustSearchResponse.getIllust());
                                IllustChannel.get().setIllustList(tempList);
                                Intent intent = new Intent(context, ViewPagerActivity.class);
                                intent.putExtra("position", 0);
                                context.startActivity(intent);
                            }else {
                                Common.showToast("illustSearchResponse.getIllust() 为空");
                            }
                        }else {
                            Common.showToast("illustSearchResponse 为空");
                        }
                    }
                });
    }
}
