package ceui.lisa.file;

import android.os.Environment;

import java.io.File;

import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelDetail;

public class LegencyFile implements FileProxy{

    @Override
    public File imageFile(IllustsBean illust, int index) {
        Environment.isExternalStorageLegacy();
        return null;
    }

    @Override
    public File gifFile(IllustsBean illust) {
        return null;
    }

    @Override
    public File novelFile(NovelDetail novel) {
        return null;
    }
}
