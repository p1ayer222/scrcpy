package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.DisplayControl;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public final class Screenshot {

    public static final int DEFAULT_JPEG_QUALITY = 80;
    public static final int MAX_SIZE = 1 << 21; // 2 MiB, must match protocol limit

    private static final int CAPTURE_TIMEOUT_MS = 1000;

    public static final class Result {
        public final int width;
        public final int height;
        public final byte[] data;

        public Result(int width, int height, byte[] data) {
            this.width = width;
            this.height = height;
            this.data = data;
        }
    }

    private Screenshot() {
        // not instantiable
    }

    public static Result capture(int displayId, int maxDimension)
            throws IOException {
        DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (displayInfo == null) {
            throw new IOException("Unknown display id: " + displayId);
        }

        Size size = displayInfo.getSize();
        int width = size.getWidth();
        int height = size.getHeight();

        if (maxDimension > 0) {
            int max = Math.max(width, height);
            if (max > maxDimension) {
                float scale = maxDimension / (float) max;
                width = Math.max(1, Math.round(width * scale));
                height = Math.max(1, Math.round(height * scale));
            }
        }

        Bitmap bitmap = captureBitmap(displayId, displayInfo, width, height);
        if (bitmap == null) {
            throw new IOException("Could not capture screenshot");
        }

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (format == FORMAT_PNG) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
                    throw new IOException("PNG compression failed");
                }
            } else {
                int quality = Math.min(100, Math.max(1, jpegQuality));
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)) {
                    throw new IOException("JPEG compression failed");
                }
            }

            byte[] result = bos.toByteArray();
            if (result.length > MAX_SIZE) {
                throw new IOException("Screenshot is too big (" + result.length + " bytes, max " + MAX_SIZE + ")");
            }
            return new Result(bitmap.getWidth(), bitmap.getHeight(), result);
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap captureBitmap(int displayId, DisplayInfo displayInfo, int width, int height)
            throws IOException {
        Bitmap bitmap = captureViaVirtualDisplay(displayId, width, height);
        if (bitmap != null) {
            return bitmap;
        }

        bitmap = captureViaSurfaceControl(displayId, displayInfo, width, height);
        if (bitmap != null) {
            return bitmap;
        }

        return null;
    }

    /**
     * Capture using the same DisplayManager VirtualDisplay mirroring path as video capture.
     * Unlike {@code screencap}, this can capture secure/protected surfaces mirrored by scrcpy.
     */
    private static Bitmap captureViaVirtualDisplay(int displayId, int width, int height) throws IOException {
        ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        VirtualDisplay virtualDisplay = null;
        try {
            Surface surface = imageReader.getSurface();
            virtualDisplay = ServiceManager.getDisplayManager()
                    .createVirtualDisplay("scrcpy-screenshot", width, height, displayId, surface);

            AtomicReference<Image> imageRef = new AtomicReference<>();
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    Image previous = imageRef.getAndSet(image);
                    if (previous != null) {
                        previous.close();
                    }
                }
            }, null);

            long deadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS;
            Image image;
            do {
                Thread.sleep(50);
                image = imageRef.get();
            } while (image == null && System.currentTimeMillis() < deadline);

            if (image == null) {
                image = imageReader.acquireLatestImage();
            }
            if (image == null) {
                return null;
            }

            try {
                return imageToBitmap(image);
            } finally {
                image.close();
            }
        } catch (ReflectiveOperationException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
            imageReader.close();
        }
    }

    private static Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            return null;
        }

        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();

        int rowPadding = rowStride - pixelStride * width;
        Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            bitmap.recycle();
            return cropped;
        }
        return bitmap;
    }

    private static Bitmap captureViaSurfaceControl(int displayId, DisplayInfo displayInfo, int width, int height) {
        IBinder displayToken = getDisplayToken(displayId, displayInfo);
        if (displayToken == null) {
            return null;
        }

        if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
            return SurfaceControl.captureDisplay(displayToken, width, height);
        }

        return SurfaceControl.screenshot(displayToken, new Rect(), width, height, displayInfo.getRotation());
    }

    private static IBinder getDisplayToken(int displayId, DisplayInfo displayInfo) {
        if (displayId == 0) {
            return SurfaceControl.getBuiltInDisplay();
        }

        String uniqueId = displayInfo.getUniqueId();
        if (uniqueId != null && uniqueId.startsWith("local:")) {
            try {
                long physicalDisplayId = Long.parseLong(uniqueId.substring("local:".length()));
                boolean useDisplayControl = Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14
                        && !SurfaceControl.hasGetPhysicalDisplayIdsMethod();
                return useDisplayControl
                        ? DisplayControl.getPhysicalDisplayToken(physicalDisplayId)
                        : SurfaceControl.getPhysicalDisplayToken(physicalDisplayId);
            } catch (NumberFormatException e) {
                Ln.w("Could not parse physical display id from uniqueId: " + uniqueId);
            }
        }

        return SurfaceControl.getBuiltInDisplay();
    }
}
