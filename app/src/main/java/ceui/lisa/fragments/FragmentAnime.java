package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentAnimeBinding;

public class FragmentAnime extends BaseFragment<FragmentAnimeBinding> {

    public static FragmentAnime newInstance() {
        Bundle args = new Bundle();
        FragmentAnime fragment = new FragmentAnime();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_anime;
    }

    @Override
    public void initView(View view) {
        baseBind.reflect.bindTargetView(baseBind.cardPixiv);
        baseBind.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.reflect.startTransform();
            }
        });

        baseBind.cardPixiv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.reflect.finishTransform();
            }
        });
    }
}
