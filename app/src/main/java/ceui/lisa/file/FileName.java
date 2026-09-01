package ceui.lisa.file;

import ceui.lisa.download.FileCreator;
import ceui.pixiv.api.model.Illust;
import ceui.lisa.utils.Common;

public class FileName implements FileNameProxy {

    private static final String DASH = "_";

    @Override
    public String zipName(Illust illust) {
        return Common.removeFSReservedChars(illust.getTitle()) + DASH + illust.getId() + ".zip";
    }

    @Override
    public String unzipName(Illust illust) {
        return Common.removeFSReservedChars(illust.getTitle()) + DASH + illust.getId() + DASH + "unzip";
    }

    @Override
    public String gifName(Illust illust) {
        return FileCreator.customGifFileName(illust);
    }
}
