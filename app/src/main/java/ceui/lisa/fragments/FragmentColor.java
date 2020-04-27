package ceui.lisa.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentColorBinding;
import ceui.lisa.utils.Params;

public class FragmentColor extends BaseFragment<FragmentColorBinding> {

    private String color;

    public static FragmentColor newInstance(String pColor) {
        Bundle args = new Bundle();
        args.putString(Params.CONTENT, pColor);
        FragmentColor fragment = new FragmentColor();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        color = bundle.getString(Params.CONTENT);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_color;
    }

    @Override
    public void initView(View view) {
        baseBind.image.setBackgroundColor(Color.parseColor(color));
        baseBind.colorText.setText(color);
    }

}
