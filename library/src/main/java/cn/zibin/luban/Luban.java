package cn.zibin.luban;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

import static cn.zibin.luban.Preconditions.checkNotNull;

public class Luban {

    public static final int FIRST_GEAR = 1;
    public static final int THIRD_GEAR = 3;

    private static final String TAG = "Luban";
    public static String DEFAULT_DISK_CACHE_DIR = "luban_disk_cache";

    private static volatile Luban INSTANCE;

    private final File mCacheDir;
    private File mTargetFile;

    private OnCompressListener compressListener;
    private File mFile;
    private int gear = THIRD_GEAR;
    private Context mContext;
    private int mTargetSize;

    Luban(Context context) {
        mCacheDir = Luban.getPhotoCacheDir(context);
        mContext = context;
    }

    /**
     * Returns a directory with a default name in the private cache directory of the application to use to store
     * retrieved media and thumbnails.
     *
     * @param context A context.
     * @see #getPhotoCacheDir(Context, String)
     */
    public static File getPhotoCacheDir(Context context) {
        return getPhotoCacheDir(context, Luban.DEFAULT_DISK_CACHE_DIR);
    }

    /**
     * Returns a directory with the given name in the private cache directory of the application to use to store
     * retrieved media and thumbnails.
     *
     * @param context   A context.
     * @param cacheName The name of the subdirectory in which to store the cache.
     * @see #getPhotoCacheDir(Context)
     */
    public static File getPhotoCacheDir(Context context, String cacheName) {
        File cacheDir = context.getCacheDir();
        if (cacheDir != null) {
            File result = new File(cacheDir, cacheName);
            if (!result.mkdirs() && (!result.exists() || !result.isDirectory())) {
                // File wasn't able to create a directory, or the result exists but not a directory
                return null;
            }
            return result;
        }
        if (Log.isLoggable(TAG, Log.ERROR)) {
            Log.e(TAG, "default disk cache dir is null");
        }
        return null;
    }

    public static Luban get(Context context) {
//        if (INSTANCE == null) INSTANCE = new Luban(context);
//
//        return INSTANCE;
        return new Luban(context);
    }

    public Observable<File> asObservable() {
        return Observable.create(new Observable.OnSubscribe<File>() {
            @Override
            public void call(Subscriber<? super File> subscriber) {
                try {
                    subscriber.onNext(compressTaskStart());
                    subscriber.onCompleted();
                } catch (Exception e) {
                    subscriber.onError(e);
                }

            }
        });
    }


    public File compressTaskStart() throws Exception {

        Bitmap pre_compress = pre_compress();

        if (gear == FIRST_GEAR) {
            return firstCompress(pre_compress);
        } else if (gear == THIRD_GEAR)
            return thirdCompress(pre_compress);
        else
            throw new Exception("不支持的类型");
    }


    //开启混合压缩模式,先设备宽高比压一次,然后采用鲁班再压一次.
    // Luban在压缩大图的时候效率很低, 所以我们预压一次, 加快压缩进度
    public void superLaunch() {
            pre_compress();
            launch();
    }

    private Bitmap pre_compress()  {
        Bitmap preloadBitmap = null;
        try {
            int deviceWidth = mContext.getResources().getDisplayMetrics().widthPixels;
            int deviceHeight = mContext.getResources().getDisplayMetrics().heightPixels;

            Log.e(TAG, "当前设备高:" + deviceHeight + ",当前设备宽:" + deviceWidth);
            Log.e(TAG,"原始图片大小:" + mFile.length() / 1024 + "KB");

            //设备宽高比
            int deviceRatio = (int) ((deviceWidth * 1.0f) / deviceHeight + 0.5f);

            preloadBitmap = preLoadCompress(mFile.getAbsolutePath(), deviceWidth, deviceHeight);

        } catch (IOException e) {
            e.printStackTrace();
        }
        return preloadBitmap;
    }


    /**
     * 最
     *
     * @param kb
     * @return
     */
    public Luban maxAllowSize(int kb) {
        mTargetSize = kb;
        return this;
    }


    /**
     * 传入想要图片的宽高,会自动根据缩放比进行一次缩放,生成缩放后的图片再进行质量压缩..(尝试替换质量压缩方式为LuBan的算法)
     *
     * @throws IOException
     */
    public static Bitmap preLoadCompress(String oriPath, int bitmapMaxWidth, int bitmapMaxHeight)
            throws IOException {

        File file = new File(oriPath);
        long len = file.length();
        int mSize = (int) (len / 1024); // kb

        Log.e(TAG, "压缩前:" + mSize + "KB");

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(new FileInputStream(oriPath), null, options);
        int reqHeight = bitmapMaxHeight;
        int reqWidth = bitmapMaxWidth;

        int inSampleSize = calculateInSampleSizeByPowerOf2(options, reqWidth, reqHeight);
        Log.e(TAG, "缩放比例:" + inSampleSize); // 1
        options.inSampleSize = inSampleSize;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = true; //

        Bitmap bitmap = BitmapFactory.decodeFile(oriPath, options);
//		Bitmap compressImage;
//        File imageFile = compressSize(desPath, bitmap);
//        Log.e(TAG,"压缩后的文件大小:" + imageFile.length() / 1024 + "KB");
        return bitmap;
    }

/*    @NonNull
    private static File compressSize(String desPath, Bitmap bitmap) throws IOException {
        ByteArrayInputStream bis;
        bis = compressImage(bitmap, 250);
        // 将压缩后的bitmap保存到图片文件
        Log.e(TAG, "文件路径:" + desPath);
        File imageFile = new File(desPath);
        if (imageFile.exists()) {
            imageFile.delete();
        }
        if (!imageFile.getParentFile().exists()) {
            imageFile.getParentFile().mkdirs();
        }

        imageFile.createNewFile();
        FileOutputStream fos = new FileOutputStream(imageFile);

        byte[] buffer = new byte[1024];
        int readLen = -1;
        while ((readLen = bis.read(buffer)) > 0) {
            fos.write(buffer, 0, readLen);
        }
        fos.flush();
        fos.close();
        bis.close();
        return imageFile;
    }*/


    /**
     * 采用 power of 2 的方式计算出最接近的缩放比
     *
     * @param options
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public static int calculateInSampleSizeByPowerOf2(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;

        Log.e(TAG, "原图高度:" + height + ",宽度:" + width);

        int inSampleSize = 1; // 9696 2332 height wid 1024 768

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and
            // keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) > reqHeight || (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }

        }

        Log.e(TAG, "inSampleSize:" + inSampleSize);
        return inSampleSize;
    }


    public Luban launch() {
        checkNotNull(mFile, "the image file cannot be null, please call .load() before this method!");

        if (compressListener != null) compressListener.onStart();

        // @formatter:off
/*        Observable.just(mFile)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        if (compressListener != null) compressListener.onError(throwable);
                    }
                })
                .onErrorResumeNext(Observable.<File>empty())
                .map(new Func1<File, File>() {
                    @Override
                    public File call(File file) {
                        Log.e("Luban","开始转换:" + System.currentTimeMillis() + ",mode: " + gear);
                        if (gear == FIRST_GEAR)
                            return firstCompress(mFile);
                        else if (gear == THIRD_GEAR)
                            return thirdCompress(mFile.getAbsolutePath());
                        else
                            return null;

                    }
                }).filter(new Func1<File, Boolean>() {
                        @Override
                        public Boolean call(File file) {
                               return file != null;
                        }
                 }).subscribe(new Action1<File>() {
                        @Override
                        public void call(File file) {
                            Log.e("Luban","结束转换:" + System.currentTimeMillis());
                            if (compressListener != null) compressListener.onSuccess(file);
                        }
                 });*/
        // @formatter:on

            //耗时操作.
            Bitmap preloadBitmap  = pre_compress();

            if (gear == Luban.FIRST_GEAR)
                Observable.just(firstCompress(preloadBitmap))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnError(new Action1<Throwable>() {
                            @Override
                            public void call(Throwable throwable) {
                                if (compressListener != null) compressListener.onError(throwable);
                            }
                        })
                        .onErrorResumeNext(Observable.<File>empty())
                        .filter(new Func1<File, Boolean>() {
                            @Override
                            public Boolean call(File file) {
                                return file != null;
                            }
                        })
                        .subscribe(new Action1<File>() {
                            @Override
                            public void call(File file) {
                                if (compressListener != null) compressListener.onSuccess(file);
                            }
                        });
            else if (gear == Luban.THIRD_GEAR)
                Observable.just(thirdCompress(preloadBitmap))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnError(new Action1<Throwable>() {
                            @Override
                            public void call(Throwable throwable) {
                                if (compressListener != null) compressListener.onError(throwable);
                            }
                        })
                        .onErrorResumeNext(Observable.<File>empty())
                        .filter(new Func1<File, Boolean>() {
                            @Override
                            public Boolean call(File file) {
                                return file != null;
                            }
                        })
                        .subscribe(new Action1<File>() {
                            @Override
                            public void call(File file) {
                                if (compressListener != null) compressListener.onSuccess(file);
                            }
                        });



        return this;
    }

    public Luban from(File file) {
        mFile = file;
        return this;
    }

    public Luban to(File destFile) {
        mTargetFile = destFile;
        return this;
    }


    public Luban setCompressListener(OnCompressListener listener) {
        compressListener = listener;
        return this;
    }

    public Luban putGear(
            @GearMode
            int gear) {
        this.gear = gear;
        return this;
    }


    private File thirdCompress(
            @NonNull
            Bitmap pre_compress_bitmap) {

        String thumb;
        if (mTargetFile == null) {
            thumb = mCacheDir.getAbsolutePath() + "/" + System.currentTimeMillis();
        } else {
            thumb = mTargetFile.getAbsolutePath() + "/" + System.currentTimeMillis();
        }


        double scale;

        int angle = getImageSpinAngle(mFile.getAbsolutePath());
        int[] imageSize = getImageSize(mFile.getAbsolutePath());
        int width = imageSize[0];
        int height = imageSize[1];


        int thumbW = width % 2 == 1 ? width + 1 : width;
        int thumbH = height % 2 == 1 ? height + 1 : height;

        //识别谁宽,谁高. 经过这套算数后,  一定是  宽< 高
        width = thumbW > thumbH ? thumbH : thumbW;
        height = thumbW > thumbH ? thumbW : thumbH;

        // c的值一定 < 1
        double c = ((double) width / height);

        Log.e(TAG, "宽:高 = " + c);


        if (c <= 1 && c > 0.5625) {
            if (height < 1664) {
                scale = (width * height) / Math.pow(1664, 2) * 150;
                scale = scale < 60 ? 60 : scale;
            } else if (height >= 1664 && height < 4990) {
                thumbW = width / 2;
                thumbH = height / 2;
                scale = (thumbW * thumbH) / Math.pow(2495, 2) * 300;
                scale = scale < 60 ? 60 : scale;
            } else if (height >= 4990 && height < 10240) {
                thumbW = width / 4;
                thumbH = height / 4;
                scale = (thumbW * thumbH) / Math.pow(2560, 2) * 300;
                scale = scale < 100 ? 100 : scale;
            } else {
                int multiple = height / 1280 == 0 ? 1 : height / 1280;
                thumbW = width / multiple;
                thumbH = height / multiple;
                scale = (thumbW * thumbH) / Math.pow(2560, 2) * 300;
                scale = scale < 100 ? 100 : scale;
            }
        } else if (c <= 0.5625 && c > 0.5) {
            int multiple = height / 1280 == 0 ? 1 : height / 1280;
            thumbW = width / multiple;
            thumbH = height / multiple;
            scale = (thumbW * thumbH) / (1440.0 * 2560.0) * 200;
            scale = scale < 100 ? 100 : scale;
        } else {
            int multiple = (int) Math.ceil(height / (1280.0 / c));
            thumbW = width / multiple;
            thumbH = height / multiple;
            scale = ((thumbW * thumbH) / (1280.0 * (1280 / c))) * 500;
            scale = scale < 100 ? 100 : scale;
        }


        if (mTargetSize != 0 && mTargetSize < scale) {
            return compress(pre_compress_bitmap, thumb, thumbW, thumbH, angle, (long) mTargetSize);
        } else {
            return compress(pre_compress_bitmap, thumb, thumbW, thumbH, angle, (long) scale);
        }
    }


    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    protected int sizeOf(Bitmap data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB_MR1) {
            return data.getRowBytes() * data.getHeight();
        } else {
            return data.getByteCount();
        }
    }


    private File firstCompress(
            @NonNull
            Bitmap pre_compress_bitmap) {
        int minSize = 60;
        int longSide = 720;
        int shortSide = 1280;

//        String filePath = file.getAbsolutePath();

        String thumbFilePath;
        if (mTargetFile == null) {
            thumbFilePath = mCacheDir.getAbsolutePath() + "/" + System.currentTimeMillis();
        } else {
            thumbFilePath = mTargetFile.getAbsolutePath() + "/" + System.currentTimeMillis();
        }


        long size = 0;
//        long maxSize = file.length() / 5;
        long maxSize = sizeOf(pre_compress_bitmap);

        int angle = getImageSpinAngle(mFile.getAbsolutePath());
        int[] imgSize = getImageSize(mFile.getAbsolutePath());
        int width = 0, height = 0;
        if (imgSize[0] <= imgSize[1]) {
            double scale = (double) imgSize[0] / (double) imgSize[1];
            if (scale <= 1.0 && scale > 0.5625) {
                width = imgSize[0] > shortSide ? shortSide : imgSize[0];
                height = width * imgSize[1] / imgSize[0];
                size = minSize;
            } else if (scale <= 0.5625) {
                height = imgSize[1] > longSide ? longSide : imgSize[1];
                width = height * imgSize[0] / imgSize[1];
                size = maxSize;
            }
        } else {
            double scale = (double) imgSize[1] / (double) imgSize[0];
            if (scale <= 1.0 && scale > 0.5625) {
                height = imgSize[1] > shortSide ? shortSide : imgSize[1];
                width = height * imgSize[0] / imgSize[1];
                size = minSize;
            } else if (scale <= 0.5625) {
                width = imgSize[0] > longSide ? longSide : imgSize[0];
                height = width * imgSize[1] / imgSize[0];
                size = maxSize;
            }
        }

        return compress(pre_compress_bitmap, thumbFilePath, width, height, angle, size);
    }


    /**
     * obtain the image's width and height
     *
     * @param imagePath the path of image
     */
    public int[] getImageSize(String imagePath) {
        int[] res = new int[2];

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        options.inSampleSize = 1;
        BitmapFactory.decodeFile(imagePath, options);

        res[0] = options.outWidth;
        res[1] = options.outHeight;

        return res;
    }

    /**
     * obtain the thumbnail that specify the size
     *
     * @param width  the width of thumbnail
     * @param height the height of thumbnail
     * @return {@link Bitmap}
     */
    private Bitmap compress(Bitmap pre_compress_bitmap, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
//        options.inJustDecodeBounds = true;
//        BitmapFactory.decodeFile(imagePath, options);

        int outH = pre_compress_bitmap.getHeight();
        int outW = pre_compress_bitmap.getWidth();
        int inSampleSize = 1;

        if (outH > height || outW > width) {
            int halfH = outH / 2;
            int halfW = outW / 2;

            while ((halfH / inSampleSize) > height && (halfW / inSampleSize) > width) {
                inSampleSize *= 2;
            }
        }

        options.inSampleSize = inSampleSize;

        options.inJustDecodeBounds = false;

        int heightRatio = (int) Math.ceil(options.outHeight / (float) height);
        int widthRatio = (int) Math.ceil(options.outWidth / (float) width);

        if (heightRatio > 1 || widthRatio > 1) {
            if (heightRatio > widthRatio) {
                options.inSampleSize = heightRatio;
            } else {
                options.inSampleSize = widthRatio;
            }
        }
        options.inJustDecodeBounds = false;


        Bitmap scaledBitmap = Bitmap.createScaledBitmap(pre_compress_bitmap, width, height, false);
        return scaledBitmap;
//        return BitmapFactory.decodeFile(imagePath, options);
    }

    /**
     * obtain the image rotation angle
     *
     * @param path path of target image
     */
    private int getImageSpinAngle(String path) {
        int degree = 0;
        try {
            ExifInterface exifInterface = new ExifInterface(path);
            int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    degree = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    degree = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    degree = 270;
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return degree;
    }

    /**
     * 指定参数压缩图片
     * create the thumbnail with the true rotate angle
     *
     * @param thumbFilePath the thumbnail path
     * @param width         width of thumbnail
     * @param height        height of thumbnail
     * @param angle         rotation angle of thumbnail
     * @param size          the file size of image
     */
    private File compress(Bitmap pre_compress_bitmap, String thumbFilePath, int width, int height, int angle, long size) {
        Bitmap thbBitmap = compress(pre_compress_bitmap, width, height);

        thbBitmap = rotatingImage(angle, thbBitmap);

        return saveImage(thumbFilePath, thbBitmap, size);
    }

    /**
     * 旋转图片
     * rotate the image with specified angle
     *
     * @param angle  the angle will be rotating 旋转的角度
     * @param bitmap target image               目标图片
     */
    private static Bitmap rotatingImage(int angle, Bitmap bitmap) {
        //rotate image
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);

        //create a new image
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * 保存图片到指定路径
     * Save image with specified size
     *
     * @param targetPath the image file save path 储存路径
     * @param bitmap   the image what be save   目标图片
     * @param size     the file size of image   期望大小
     */
    private File saveImage(String targetPath, Bitmap bitmap, long size) {
        checkNotNull(bitmap, TAG + "bitmap cannot be null");

        File result = new File(targetPath.substring(0, targetPath.lastIndexOf("/")));

        if (!result.exists() && !result.mkdirs()) return null;

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        int options = 100;
        bitmap.compress(Bitmap.CompressFormat.JPEG, options, stream);

        int j = 1;
        //循环压缩到指定大小..
        while (stream.toByteArray().length / 1024 > size) {
            Log.e(TAG, "第" + j + "次压缩后:" + ",option:" + options + ",baos大小:" + stream.toByteArray().length / 1024 + "KB");
            j++;
            stream.reset();
            options -= 10;
            bitmap.compress(Bitmap.CompressFormat.JPEG, options, stream);
        }

        try {
            FileOutputStream fos = new FileOutputStream(targetPath);
            fos.write(stream.toByteArray());
            fos.flush();
            fos.close();
            bitmap.recycle();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new File(targetPath);
    }
}