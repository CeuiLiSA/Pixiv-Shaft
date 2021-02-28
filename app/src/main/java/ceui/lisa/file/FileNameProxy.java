package ceui.lisa.file;

import ceui.lisa.models.IllustsBean;

public interface FileNameProxy {

    String zipName(IllustsBean illust);

    String unzipName(IllustsBean illust);

    String gifName(IllustsBean illust);
}
