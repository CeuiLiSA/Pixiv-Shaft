package ceui.lisa.core;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;


public abstract class BaseFragment extends Fragment {

    protected String className = getClass().getSimpleName();
    protected Context mContext;
    protected FragmentActivity mActivity;
    protected View rootView;
    protected int mLayoutID;

    public BaseFragment() {
        Log.d(className, "new class");
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = getContext();
        mActivity = getActivity();

        Bundle bundle = getArguments();
        if (bundle != null) {
            initBundle(bundle);
        }

        getLayout();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(mLayoutID, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rootView = view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        initView(rootView);
        initData();
    }

    public void initView(View view) {

    }

    public void initBundle(Bundle bundle) {

    }

    public abstract void getLayout();

    public abstract void initData();

}
