package ceui.lisa.viewmodel;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;

import ceui.lisa.models.TagsBean;

public class MutedTagsModel extends ViewModel {

    private MutableLiveData<List<TagsBean>> tagsList = new MutableLiveData<>();

    public MutableLiveData<List<TagsBean>> getTagsList() {
        return tagsList;
    }
}
