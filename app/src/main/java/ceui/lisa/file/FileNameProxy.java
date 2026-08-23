package ceui.lisa.file;

import ceui.loxia.Illust;

public interface FileNameProxy {

    String zipName(Illust illust);

    String unzipName(Illust illust);

    String gifName(Illust illust);
}
