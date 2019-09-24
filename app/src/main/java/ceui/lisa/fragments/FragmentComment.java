package ceui.lisa.fragments;

import android.view.View;

import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.lisa.R;
import ceui.lisa.adapters.CommentAdapter;
import ceui.lisa.databinding.FragmentCommentBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.CommentsBean;
import ceui.lisa.model.IllustCommentsResponse;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentComment extends BaseBindFragment<FragmentCommentBinding> {

    private int illustID;
    private String title;
    public int parentCommentID;

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
        baseBind.toolbar.setTitle(title);
        FragmentC fragmentC = FragmentC.newInstance(illustID);
        FragmentTransaction fragmentTransaction = getChildFragmentManager().beginTransaction();
        fragmentTransaction
                .add(R.id.fragment_container, fragmentC)
                .show(fragmentC)
                .commit();
        baseBind.post.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO: 2019-09-24
            }
        });
    }
}
