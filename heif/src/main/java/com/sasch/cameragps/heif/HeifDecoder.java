package com.sasch.cameragps.heif;

import android.graphics.Bitmap;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Software HEVC decoding; independent of the device's MediaCodec implementations. */
public final class HeifDecoder {
    static { System.loadLibrary("alpha_heif"); }

    private HeifDecoder() {}

    private static Bitmap createBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        return bitmap;
    }

    public static native Bitmap decodeFile(String path, long memoryBudget, BooleanSupplier cancelled) throws IOException;

    public static native Bitmap decodeThumbnail(byte[] encoded, long memoryBudget) throws IOException;

    public static native Bitmap decodePreview(byte[] encoded, long memoryBudget) throws IOException;

    public static native byte[] readExif(String path) throws IOException;
}
