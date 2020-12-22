package ceui.lisa.file;

import ceui.lisa.models.IllustsBean;

public class FileName implements FileNameProxy {

    private static final String DASH = "_";

    @Override
    public String zipName(IllustsBean illust) {
        return illust.getTitle() + DASH + illust.getId() + ".zip";
    }

    @Override
    public String unzipName(IllustsBean illust) {
        return illust.getTitle() + DASH + illust.getId() + DASH + "unzip";
    }

    @Override
    public String gifName(IllustsBean illust) {
        return illust.getTitle() + DASH + illust.getId() + ".gif";
    }


}
