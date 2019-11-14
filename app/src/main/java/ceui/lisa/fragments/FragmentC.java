package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.lisa.R;
import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.CAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.FragmentCommentBinding;
import ceui.lisa.databinding.RecyCommentListBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.model.CommentHolder;
import ceui.lisa.model.CommentsBean;
import ceui.lisa.model.IllustCommentsResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentC extends NetListFragment<FragmentCommentBinding,
        IllustCommentsResponse, CommentsBean, RecyCommentListBinding> {

    private static final String[] OPTIONS = new String[]{"回复评论", "复制评论"};
    private int illustID;
    private String title;
    private int parentCommentID;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_comment;
    }

    public static FragmentC newInstance(int id, String title) {
        FragmentC comment = new FragmentC();
        comment.illustID = id;
        comment.title = title;
        return comment;
    }

    @Override
    public NetControl<IllustCommentsResponse> present() {
        return new NetControl<IllustCommentsResponse>() {
            @Override
            public Observable<IllustCommentsResponse> initApi() {
                return Retro.getAppApi().getComment(sUserModel.getResponse().getAccess_token(), illustID);
            }

            @Override
            public Observable<IllustCommentsResponse> initNextApi() {
                return Retro.getAppApi().getNextComment(sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<CommentsBean, RecyCommentListBinding> adapter() {
        return new CAdapter(allItems, mContext).setOnItemClickListener((v, position, viewType) -> {
            if (viewType == 0) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setItems(OPTIONS, (dialog, which) -> {
                    if (which == 0) {
                        baseBind.inputBox.setHint("回复" +
                                allItems.get(position).getUser().getName());
                        parentCommentID = allItems.get(position).getId();
                    } else if (which == 1) {
                        Common.copy(mContext, allItems.get(position).getComment());
                    }
                });
                AlertDialog alertDialog = builder.create();
                alertDialog.show();
            } else if (viewType == 1) {
                Intent userIntent = new Intent(mContext, UActivity.class);
                userIntent.putExtra(Params.USER_ID, allItems.get(position).getUser().getId());
                startActivity(userIntent);
            } else if (viewType == 2) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setItems(OPTIONS, (dialog, which) -> {
                    if (which == 0) {
                        baseBind.inputBox.setHint(
                                "回复" + allItems.get(position).getParent_comment().getUser().getName()
                        );
                        parentCommentID =
                                allItems.get(position).getParent_comment().getId();
                    } else if (which == 1) {
                        Common.copy(mContext, allItems.get(position).getParent_comment().getComment());
                    }
                });
                AlertDialog alertDialog = builder.create();
                alertDialog.show();

            } else if (viewType == 3) {
                Intent userIntent = new Intent(mContext, UActivity.class);
                userIntent.putExtra(Params.USER_ID, allItems.get(position).getParent_comment().getUser().getId());
                startActivity(userIntent);
            }
        });
    }

    @Override
    public String getToolbarTitle() {
        return title + "的评论";
    }

    @Override
    public void initRecyclerView() {
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
    }

    @Override
    public void initView(View view) {
        super.initView(view);
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
                                allItems.add(0, commentHolder.getComment());
                                mAdapter.notifyItemInserted(0);
                                baseBind.recyclerView.scrollToPosition(0);
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
