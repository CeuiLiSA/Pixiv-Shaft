package ceui.lisa.fragments;

import android.view.View;

import androidx.fragment.app.FragmentTransaction;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentCommentBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.CommentHolder;
import ceui.lisa.utils.Common;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentComment extends BaseBindFragment<FragmentCommentBinding> {

    public int parentCommentID;
    private int illustID;
    private String title;

    public static FragmentComment newInstance(int id, String title) {
        FragmentComment comment = new FragmentComment();
        comment.illustID = id;
        comment.title = title;
        return comment;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_comment;
    }

    @Override
    void initData() {
        baseBind.toolbar.setTitle(title + "的评论");
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.finish();
            }
        });
        FragmentC fragmentC = FragmentC.newInstance(illustID);
        FragmentTransaction fragmentTransaction = getChildFragmentManager().beginTransaction();
        fragmentTransaction
                .add(R.id.fragment_container, fragmentC)
                .show(fragmentC)
                .commit();
        baseBind.post.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseBind.inputBox.getText().toString().length() == 0) {
                    Common.showToast("请输入评论内容");
                    return;
                }

                Retro.getAppApi().postComment(sUserModel.getResponse().getAccess_token(), illustID,
                        baseBind.inputBox.getText().toString(), parentCommentID)
                        .subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new NullCtrl<CommentHolder>() {
                            @Override
                            public void onSubscribe(Disposable d) {
                                Common.hideKeyboard(mActivity);
                                baseBind.inputBox.setHint("请输入评论内容");
                                baseBind.inputBox.setText("");
                                baseBind.progress.setVisibility(View.VISIBLE);
                            }

                            @Override
                            public void success(CommentHolder commentHolder) {
                                fragmentC.allItems.add(0, commentHolder.getComment());
                                fragmentC.mAdapter.notifyItemInserted(0);
                                fragmentC.baseBind.recyclerView.scrollToPosition(0);
                            }

                            @Override
                            public void must(boolean isSuccess) {
                                baseBind.progress.setVisibility(View.GONE);
                            }
                        });
            }
        });
        baseBind.clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseBind.inputBox.getText().toString().length() != 0) {
                    baseBind.inputBox.setText("");
                    return;
                }
                if (parentCommentID != 0) {
                    baseBind.inputBox.setHint("留下你的评论吧");
                    parentCommentID = 0;
                    return;
                }
            }
        });
    }
}
