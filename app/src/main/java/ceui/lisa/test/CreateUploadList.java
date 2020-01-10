package ceui.lisa.test;

import java.io.File;
import java.util.List;

public interface CreateUploadList {

    List<File> compare(List<File> all, List<File> hasUpload, int size);
}
