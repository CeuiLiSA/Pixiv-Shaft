package ceui.lisa.fragments;

import android.view.View;

import com.mikepenz.aboutlibraries.Libs;
import com.mikepenz.aboutlibraries.LibsBuilder;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentLicenseBinding;

public class FragmentLicense extends BaseBindFragment<FragmentLicenseBinding>{

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_license;
    }

    @Override
    void initData() {
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getActivity().finish();
            }
        });
        getChildFragmentManager().beginTransaction().add(R.id.fragment_container,
                new LibsBuilder()
                        .withAboutAppName("Shaft")
                        .withLibraryModification("aboutlibraries",
                        Libs.LibraryFields.LIBRARY_OPEN_SOURCE, "_AboutLibraries").supportFragment()).commit();
    }
}
