package ceui.lisa.file;

import ceui.lisa.download.FileCreator;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class FileName implements FileNameProxy {

    private static final String DASH = "_";

    @Override
    public String zipName(IllustsBean illust) {
        return Common.removeFSReservedChars(illust.getTitle()) + DASH + illust.getId() + ".zip";
    }

    @Override
    public String unzipName(IllustsBean illust) {
        return Common.removeFSReservedChars(illust.getTitle()) + DASH + illust.getId() + DASH + "unzip";
    }

    @Override
    public String gifName(IllustsBean illust) {
        return FileCreator.customGifFileName(illust);
    }
}
