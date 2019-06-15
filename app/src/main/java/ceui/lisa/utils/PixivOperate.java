package ceui.lisa.utils;


import android.widget.TextView;

import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.response.BookmarkAddResponse;
import ceui.lisa.response.UserBean;
import ceui.lisa.response.UserDetailResponse;
import ceui.lisa.response.UserModel;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
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
                .subscribe(new ErrorCtrl<BookmarkAddResponse>() {
                    @Override
                    public void onNext(BookmarkAddResponse bookmarkAddResponse) {
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
                .subscribe(new ErrorCtrl<BookmarkAddResponse>() {
                    @Override
                    public void onNext(BookmarkAddResponse bookmarkAddResponse) {
                        Common.showToast("取消关注~");
                    }
                });
    }
}
