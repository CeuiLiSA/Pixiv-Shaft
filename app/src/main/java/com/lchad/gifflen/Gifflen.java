package com.lchad.gifflen; /**
 * Copyright 2017 lchad
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import android.content.Context;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Created by lchad on 2017/3/24.
 * Github : https://www.github.com/lchad
 */

public class Gifflen {

    private static final String TAG = "com.lchad.gifflen.Gifflen";

    static {
        System.loadLibrary("gifflen");
    }

    private static final int DEFAULT_COLOR = 256;
    private static final int DEFAULT_QUALITY = 10;
    private static final int DEFAULT_WIDTH = 320;
    private static final int DEFAULT_HEIGHT = 320;
    private static final int DEFAULT_DELAY = 500;

    private int mColor;
    private int mQuality;
    private int mDelay;
    private int mWidth;
    private int mHeight;

    private String mTargetPath;

    private Handler mHandler;

    private OnEncodeFinishListener mOnEncodeFinishListener;

    private Gifflen(int color, int quality, int delay, int width, int height, OnEncodeFinishListener onEncodeFinishListener) {
        this.mColor = color;
        this.mQuality = quality;
        this.mDelay = delay;
        this.mWidth = width;
        this.mHeight = height;
        this.mOnEncodeFinishListener = onEncodeFinishListener;
        mHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 返回一个Builder对象.
     *
     * @return
     */
    public Builder newBuilder() {
        return new Builder();
    }

    /**
     * com.lchad.gifflen.Gifflen addFrame
     *
     * @param pixels pixels array from bitmap
     * @return 是否成功.
     */
    private native int addFrame(int[] pixels);

    /**
     * com.lchad.gifflen.Gifflen init
     *
     * @param path    Gif 图片的保存路径
     * @param width   Gif 图片的宽度.
     * @param height  Gif 图片的高度.
     * @param color   Gif 图片的色域.
     * @param quality 进行色彩量化时的quality参数.
     * @param delay   相邻的两帧之间的时间间隔.
     * @return 如果返回值不是0, 就代表着执行失败.
     */
    private native int init(String path, int width, int height, int color, int quality, int delay);

    /**
     * * native层做一些释放资源的操作.
     */
    private native void close();

    /**
     * 开始进行Gif生成
     *
     * @param width  宽度
     * @param height 高度
     * @param path   Gif保存的路径
     * @param files  传入的每一帧图片的File对象
     * @return 是否成功
     */
    public boolean encode(int width, int height, String path, List<File> files) {
        check(width, height, path);
        int state;
        int[] pixels = new int[width * height];

        state = init(path, width, height, mColor, mQuality, mDelay / 10);
        if (state != 0) {
            // 失败
            return false;
        }

        for (File aFileList : files) {
            Bitmap bitmap;
            try {
                bitmap = BitmapFactory.decodeStream(new FileInputStream(aFileList));
            } catch (FileNotFoundException e) {
                return false;
            }

            if (width < bitmap.getWidth() || height < bitmap.getHeight()) {
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
            }
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            addFrame(pixels);
            bitmap.recycle();
        }

        close();

        return true;
    }

    /**
     * 开始进行Gif生成
     *
     * @param path  Gif保存的路径
     * @param files 传入的每一帧图片的File对象
     * @return 是否成功
     */
    public boolean encode(String path, List<File> files) {
        return encode(mWidth, mHeight, path, files);
    }

    /**
     * 开始进行Gif生成
     *
     * @param context      上下文对象.
     * @param path         Gif保存的路径.
     * @param width        宽度.
     * @param height       高度.
     * @param drawableList 传入的图片资源id数组.
     * @return 是否成功.
     */
    public boolean encode(final Context context, final String path, final int width, final int height, final int[] drawableList) {
        check(width, height, path);
        if (drawableList == null || drawableList.length == 0) {
            return false;
        }
        int state;
        int[] pixels = new int[width * height];

        state = init(path, width, height, mColor, mQuality, mDelay / 10);
        if (state != 0) {
            // 失败
            return false;
        }

        for (int drawable : drawableList) {
            Bitmap bitmap;
            bitmap = BitmapFactory.decodeResource(context.getResources(), drawable);
            if (width < bitmap.getWidth() || height < bitmap.getHeight()) {
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
            }
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            addFrame(pixels);
            bitmap.recycle();
        }
        close();

        return true;
    }

    /**
     * 开始进行Gif生成
     *
     * @param context      上下文对象.
     * @param path         Gif保存的路径.
     * @param drawableList 传入的图片资源id数组.
     * @return 是否成功.
     */
    public boolean encode(final Context context, final String path, final int[] drawableList) {
        return encode(context, path, mWidth, mHeight, drawableList);
    }

    /**
     * 开始进行Gif生成
     *
     * @param context 上下文对象.
     * @param path    Gif保存的路径.
     * @param width   宽度.
     * @param height  高度.
     * @param uriList 传入的Uri数组.
     * @return 是否成功.
     */
    public boolean encode(Context context, final String path, final int width, final int height, final List<Uri> uriList) {
        check(width, height, path);
        if (uriList == null || uriList.size() == 0) {
            return false;
        }
        int state;
        int[] pixels = new int[width * height];

        state = init(path, width, height, mColor, mQuality, mDelay / 10);
        if (state != 0) {
            // Failed
            return false;
        }

        for (Uri uri : uriList) {
            Bitmap bitmap;
            String sourcePath = getRealPathFromURI(context, uri);
            if (TextUtils.isEmpty(sourcePath)) {
                Log.e(TAG, "the file path from url is empty");
                continue;
            }
            bitmap = BitmapFactory.decodeFile(sourcePath);
            if (width < bitmap.getWidth() || height < bitmap.getHeight()) {
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
            }
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            addFrame(pixels);
            bitmap.recycle();
        }
        close();

        return true;
    }

    /**
     * 开始进行Gif生成
     *
     * @param context 上下文对象.
     * @param path    Gif保存的路径.
     * @param uriList 传入的Uri数组.
     * @return 是否成功.
     */
    public boolean encode(Context context, final String path, final List<Uri> uriList) {
        return encode(context, path, mWidth, mHeight, uriList);
    }

    /**
     * 从Uri获取图片的绝对路径
     *
     * @param context    上下文对象.
     * @param contentUri 传入的Uri数组.
     * @return 文件绝对路径.
     */
    public String getRealPathFromURI(Context context, Uri contentUri) {
        Cursor cursor = null;
        try {
            String[] projection = {MediaStore.Images.Media.DATA};
            cursor = context.getContentResolver().query(contentUri, projection, null, null, null);
            if (cursor != null) {
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                cursor.moveToFirst();
                return cursor.getString(column_index);
            }

        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return "";
    }


    /**
     * 开始进行Gif生成
     *
     * @param path       Gif保存的路径.
     * @param width      宽度.
     * @param height     高度.
     * @param bitmapList 传入的Bitmap数组.
     * @return 是否成功.
     */
    public boolean encode(final String path, final int width, final int height, final Bitmap[] bitmapList) {
        check(width, height, path);
        if (bitmapList == null || bitmapList.length == 0) {
            return false;
        }
        int state;
        int[] pixels = new int[width * height];

        state = init(path, width, height, mColor, mQuality, mDelay / 10);
        if (state != 0) {
            // 失败
            return false;
        }

        for (Bitmap bitmap : bitmapList) {
            if (width < bitmap.getWidth() || height < bitmap.getHeight()) {
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
            }
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            addFrame(pixels);
            bitmap.recycle();
        }
        close();

        return true;
    }

    /**
     * 开始进行Gif生成
     *
     * @param path       Gif保存的路径.
     * @param bitmapList 传入的Bitmap数组.
     * @return 是否成功.
     */
    public boolean encode(final String path, final Bitmap[] bitmapList) {
        return encode(path, mWidth, mHeight, bitmapList);
    }

    /**
     * 开始进行Gif生成
     *
     * @param context    上下文对象.
     * @param path       Gif保存的路径.
     * @param width      宽度.
     * @param height     高度.
     * @param typedArray Android资源数组对象.
     * @return 是否成功.
     */
    public boolean encode(final Context context, final String path, final int width, final int height, final TypedArray typedArray) {
        check(width, height, path);
        if (typedArray == null || typedArray.length() == 0) {
            return false;
        }
        int state;
        int[] pixels = new int[width * height];

        state = init(path, width, height, mColor, mQuality, mDelay / 10);
        if (state != 0) {
            // 失败
            return false;
        }

        for (int i = 0; i < typedArray.length(); i++) {
            Bitmap bitmap;
            bitmap = BitmapFactory.decodeResource(context.getResources(), typedArray.getResourceId(i, -1));
            if (width < bitmap.getWidth() || height < bitmap.getHeight()) {
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
            }
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            addFrame(pixels);
            bitmap.recycle();
        }
        close();

        return true;
    }

    /**
     * 开始进行Gif生成
     *
     * @param context    上下文对象.
     * @param path       Gif保存的路径.
     * @param typedArray Android资源数组对象.
     * @return 是否成功.
     */
    public boolean encode(final Context context, final String path, final TypedArray typedArray) {
        return encode(context, path, mWidth, mHeight, typedArray);
    }

    public static final class Builder {

        public Builder() {
            color = DEFAULT_COLOR;
            quality = DEFAULT_QUALITY;
            delay = DEFAULT_DELAY;
            width = DEFAULT_WIDTH;
            height = DEFAULT_HEIGHT;
        }

        private int color;
        private int quality;
        private int delay;
        private int width;
        private int height;

        private OnEncodeFinishListener onEncodeFinishListener;

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        public Builder delay(int delay) {
            this.delay = delay;
            return this;
        }

        public Builder width(int wdith) {
            this.width = wdith;
            return this;
        }

        public Builder height(int height) {
            this.height = height;
            return this;
        }

        public Builder listener(OnEncodeFinishListener onEncodeFinishListener) {
            this.onEncodeFinishListener = onEncodeFinishListener;
            return this;
        }

        public Gifflen build() {
            if (this.color < 2 || this.color > 256) {
                this.color = DEFAULT_COLOR;
            }
            if (this.quality <= 0 || this.quality > 100) {
                quality = DEFAULT_QUALITY;
            }

            if (this.delay <= 0) {
                throw new IllegalStateException("the delay time value is invalid!!");
            }
            if (this.width <= 0) {
                throw new IllegalStateException("the width value is invalid!!");
            }
            if (this.height <= 0) {
                throw new IllegalStateException("the height value is invalid!!");
            }
            return new Gifflen(this.color, this.quality, this.delay, width, height, onEncodeFinishListener);
        }
    }

    private void check(final int width, final int height, String targetPath) {
        if (targetPath != null && targetPath.length() > 0) {
            mTargetPath = targetPath;
        } else {
            throw new IllegalStateException("the target path is invalid!!");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("the width or height value is invalid!!");
        }
    }

    public void onEncodeFinish() {
        if (mOnEncodeFinishListener != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mOnEncodeFinishListener.onEncodeFinish(mTargetPath);
                }
            });
        }
    }

    public interface OnEncodeFinishListener {
        void onEncodeFinish(String path);
    }

}
