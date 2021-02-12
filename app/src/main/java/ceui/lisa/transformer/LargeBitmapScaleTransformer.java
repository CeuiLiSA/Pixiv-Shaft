package ceui.lisa.transformer;

import android.graphics.Bitmap;

import com.blankj.utilcode.util.ImageUtils;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import ceui.lisa.utils.OpenGLUtils;

public class LargeBitmapScaleTransformer extends BitmapTransformation {

    private static final String ID = "ceui.lisa.transformer.LargeBitmapScaleTransformer";
    private static final byte[] ID_BYTES = ID.getBytes();

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform, int outWidth, int outHeight) {
        int width = toTransform.getWidth();
        int height = toTransform.getHeight();

        int renderLimit = OpenGLUtils.RENDER_LIMIT;
        if(height > renderLimit){
            width = width * renderLimit / height;
            height = renderLimit;
            Bitmap result = ImageUtils.compressByScale(toTransform, width, height);
            return result;
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
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest)  {
        messageDigest.update(ID_BYTES);
    }
}
