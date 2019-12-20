package ceui.lisa.test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Creator implements CreateUploadList{

    @Override
    public List<File> compare(List<File> all, List<File> hasUpload, int size) {
        if(all == null || all.size() == 0){
            return new ArrayList<>();
        }

        all.removeAll(hasUpload);

        if(all.size() <= size){
            return all;
        }else {
            return all.subList(0, size);
        }
    }
}
