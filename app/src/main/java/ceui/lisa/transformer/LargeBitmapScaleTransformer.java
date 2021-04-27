package ceui.lisa.transformer;

import android.graphics.Bitmap;

import com.blankj.utilcode.util.ImageUtils;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class LargeBitmapScaleTransformer extends BitmapTransformation {

    private static final String ID = "ceui.lisa.transformer.LargeBitmapScaleTransformer";
    private static final byte[] ID_BYTES = ID.getBytes();
    private static final int MAX_BITMAP_SIZE = 100 * 1024 * 1024;

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform, int outWidth, int outHeight) {
        int width = toTransform.getWidth();
        int height = toTransform.getHeight();
        int byteCount = toTransform.getByteCount();

        if (byteCount > MAX_BITMAP_SIZE) {
            double scale = Math.sqrt((double) byteCount / MAX_BITMAP_SIZE);
            width = (int) Math.floor(width / scale);
            height = (int) Math.floor(height / scale);
            toTransform = ImageUtils.compressByScale(toTransform, width, height);
        }

        return toTransform;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof LargeBitmapScaleTransformer;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
    }
}
