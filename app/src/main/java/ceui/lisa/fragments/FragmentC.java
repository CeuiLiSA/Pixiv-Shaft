package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.lisa.activities.UActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.CAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyCommentListBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.model.CommentsBean;
import ceui.lisa.model.IllustCommentsResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentC extends NetListFragment<FragmentBaseListBinding,
        IllustCommentsResponse, CommentsBean, RecyCommentListBinding> {

    private static final String[] OPTIONS = new String[]{"回复评论", "复制评论"};
    private int illustID;

    public static FragmentC newInstance(int id) {
        FragmentC comment = new FragmentC();
        comment.illustID = id;
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
                        if (getParentFragment() instanceof FragmentComment) {
                            ((FragmentComment) getParentFragment()).baseBind.inputBox.setHint(
                                    "回复" + allItems.get(position).getUser().getName()
                            );
                            ((FragmentComment) getParentFragment()).parentCommentID =
                                    allItems.get(position).getId();
                        }
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
                        if (getParentFragment() instanceof FragmentComment) {
                            ((FragmentComment) getParentFragment()).baseBind.inputBox.setHint(
                                    "回复" + allItems.get(position).getParent_comment().getUser().getName()
                            );
                            ((FragmentComment) getParentFragment()).parentCommentID =
                                    allItems.get(position).getParent_comment().getId();
                        }
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
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
    }
}
