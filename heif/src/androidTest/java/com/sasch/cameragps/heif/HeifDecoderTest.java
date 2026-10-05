package com.sasch.cameragps.heif;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeifDecoderTest {
    private static final long BUDGET = 128L * 1024 * 1024;

    private byte[] fixture(String name) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    private void verifyRed(Bitmap image, int width, int height) {
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
        int color = image.getPixel(width / 2, height / 2);
        assertTrue(Color.red(color) > 240);
        assertTrue(Color.green(color) < 15);
        assertTrue(Color.blue(color) < 15);
    }

    @Test public void decodesTenBit422AndEncodesJpeg() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source = File.createTempFile("heif-test-", ".hif", context.getCacheDir());
        try {
            try (FileOutputStream stream = new FileOutputStream(source)) {
                stream.write(fixture("red-422-10bit.hif"));
            }
            Bitmap bitmap = HeifDecoder.decodeFile(source.getAbsolutePath(), BUDGET, () -> false);
            try {
                verifyRed(bitmap, 128, 64);
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, jpeg));
                byte[] bytes = jpeg.toByteArray();
                assertEquals(0xff, bytes[0] & 255);
                assertEquals(0xd8, bytes[1] & 255);
                Bitmap restored = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                try { verifyRed(restored, 128, 64); } finally { restored.recycle(); }
            } finally { bitmap.recycle(); }
            byte[] metadata = HeifDecoder.readExif(source.getAbsolutePath());
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(metadata), ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY);
            assertEquals("Alpha GPS test", exif.getAttribute(ExifInterface.TAG_MAKE));
            assertThrows(IOException.class, () -> HeifDecoder.decodeFile(source.getAbsolutePath(), BUDGET, () -> true));
            assertThrows(IOException.class, () -> HeifDecoder.decodeFile(source.getAbsolutePath(), BUDGET, () -> {
                throw new IllegalStateException("Callback failed");
            }));
            Bitmap afterCancellation = HeifDecoder.decodeFile(source.getAbsolutePath(), BUDGET, null);
            try { verifyRed(afterCancellation, 128, 64); } finally { afterCancellation.recycle(); }
        } finally { source.delete(); }
    }

    @Test public void thumbnailsApplyRotationAndRejectMalformedInput() throws Exception {
        byte[] encoded = fixture("red-422-10bit-rotated.hif");
        byte[] original = encoded.clone();
        Bitmap bitmap = HeifDecoder.decodeThumbnail(encoded, BUDGET);
        try { verifyRed(bitmap, 64, 128); } finally { bitmap.recycle(); }
        assertArrayEquals(original, encoded);
        assertThrows(IOException.class, () -> HeifDecoder.decodeThumbnail(new byte[]{1, 2, 3}, BUDGET));
    }

    @Test
    public void mapsNativeFailuresToIOException() throws Exception {
        IOException emptyPath = assertThrows(IOException.class, () -> HeifDecoder.decodeFile("", BUDGET, null));
        assertEquals("Invalid HEIF path", emptyPath.getMessage());
        assertThrows(IOException.class, () -> HeifDecoder.decodeFile(null, BUDGET, null));
        assertThrows(IOException.class, () -> HeifDecoder.readExif(null));
        assertThrows(IOException.class, () -> HeifDecoder.readExif(""));
        assertThrows(IOException.class, () -> HeifDecoder.decodeThumbnail(null, BUDGET));
        assertThrows(IOException.class, () -> HeifDecoder.decodeThumbnail(new byte[0], BUDGET));
        IOException oversized = assertThrows(IOException.class,
            () -> HeifDecoder.decodeThumbnail(new byte[512 * 1024 + 1], BUDGET));
        assertEquals("Invalid HEIF preview", oversized.getMessage());
        byte[] encoded = fixture("red-422-10bit.hif");
        assertThrows(IOException.class, () -> HeifDecoder.decodeThumbnail(encoded, -1));
        assertThrows(IOException.class, () -> HeifDecoder.decodeFile("", -1, null));
        assertThrows(IOException.class, () -> HeifDecoder.decodeThumbnail(encoded, 1));
        Bitmap recovered = HeifDecoder.decodeThumbnail(encoded, BUDGET);
        try { verifyRed(recovered, 128, 64); } finally { recovered.recycle(); }
    }

    @Test
    public void previewsRetainMoreDetailThanThumbnails() throws Exception {
        byte[] encoded = fixture("red-422-preview.hif");
        byte[] original = encoded.clone();
        Bitmap thumbnail = HeifDecoder.decodeThumbnail(encoded, BUDGET);
        Bitmap preview = HeifDecoder.decodePreview(encoded, BUDGET);
        try {
            verifyRed(thumbnail, 640, 320);
            verifyRed(preview, 2048, 1024);
        } finally {
            thumbnail.recycle();
            preview.recycle();
        }
        assertThrows(IOException.class, () -> HeifDecoder.decodePreview(null, BUDGET));
        assertThrows(IOException.class, () -> HeifDecoder.decodePreview(new byte[]{1, 2, 3}, BUDGET));
        assertThrows(IOException.class, () -> HeifDecoder.decodePreview(new byte[8 * 1024 * 1024 + 1], BUDGET));
        assertThrows(IOException.class, () -> HeifDecoder.decodePreview(encoded, 1));
        Bitmap recovered = HeifDecoder.decodePreview(encoded, BUDGET);
        try {
            verifyRed(recovered, 2048, 1024);
        } finally {
            recovered.recycle();
        }
        assertArrayEquals(original, encoded);
    }
}
