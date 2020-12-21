package ceui.lisa.file;


import java.io.File;

import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelDetail;

public interface FileProxy {

    File imageFile(IllustsBean illust, int index);

    File gifFile(IllustsBean illust);

    File novelFile(NovelDetail novel);
}
