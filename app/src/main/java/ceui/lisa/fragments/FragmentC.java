package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.CAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentCommentBinding;
import ceui.lisa.databinding.RecyCommentListBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListComment;
import ceui.lisa.models.CommentHolder;
import ceui.lisa.models.CommentsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentC extends NetListFragment<FragmentCommentBinding,
        ListComment, CommentsBean, RecyCommentListBinding> {

    private static final String[] OPTIONS = new String[]{"回复评论", "复制评论"};
    private int illustID;
    private String title;
    private int parentCommentID;

    public static FragmentC newInstance(int id, String title) {
        FragmentC comment = new FragmentC();
        comment.illustID = id;
        comment.title = title;
        return comment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_comment;
    }

    @Override
    public NetControl<ListComment> present() {
        return new NetControl<ListComment>() {
            @Override
            public Observable<ListComment> initApi() {
                return Retro.getAppApi().getComment(sUserModel.getResponse().getAccess_token(), illustID);
            }

            @Override
            public Observable<ListComment> initNextApi() {
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
                if (!sUserModel.getResponse().getUser().isIs_mail_authorized()) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                    builder.setMessage("发布评论需要先绑定邮箱");
                    builder.setPositiveButton("立即绑定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(mContext, TemplateActivity.class);
                            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "绑定邮箱");
                            startActivity(intent);
                        }
                    });
                    builder.setNegativeButton("取消", null);
                    AlertDialog alertDialog = builder.create();
                    alertDialog.show();
                    alertDialog
                            .getButton(AlertDialog.BUTTON_POSITIVE)
                            .setTextColor(
                                    getResources().getColor(R.color.colorPrimary)
                            );
                    alertDialog
                            .getButton(AlertDialog.BUTTON_NEGATIVE)
                            .setTextColor(
                                    getResources().getColor(R.color.colorPrimary)
                            );
                    return;
                }

                if (baseBind.inputBox.getText().toString().length() == 0) {
                    Common.showToast("请输入评论内容");
                    return;
                }

                NullCtrl<CommentHolder> nullCtrl = new NullCtrl<CommentHolder>() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        Common.hideKeyboard(mActivity);
                        baseBind.inputBox.setHint("请输入评论内容");
                        baseBind.inputBox.setText("");
                        baseBind.progress.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void success(CommentHolder commentHolder) {
                        if (allItems.size() == 0) {
                            mRecyclerView.setVisibility(View.VISIBLE);
                            noData.setVisibility(View.INVISIBLE);
                        }

                        allItems.add(0, commentHolder.getComment());
                        mAdapter.notifyItemInserted(0);
                        baseBind.recyclerView.scrollToPosition(0);
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        baseBind.progress.setVisibility(View.GONE);
                    }
                };
                if (parentCommentID != 0) {
                    Retro.getAppApi().postComment(sUserModel.getResponse().getAccess_token(), illustID,
                            baseBind.inputBox.getText().toString(), parentCommentID)
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(nullCtrl);
                } else {
                    Retro.getAppApi().postComment(sUserModel.getResponse().getAccess_token(), illustID,
                            baseBind.inputBox.getText().toString())
                            .subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(nullCtrl);
                }
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
