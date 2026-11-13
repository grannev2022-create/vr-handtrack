package com.grannev.vrhandtrack;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ArCorePose implements PoseProvider {

    private final Activity activity;
    private final HandTracker handTracker;
    private final Object lock = new Object();
    private final ExecutorService convertExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean converting = new AtomicBoolean(false);

    private Session session;
    private int cameraTexture;
    private int viewWidth, viewHeight;
    private long lastHandFrameNs;

    public ArCorePose(Activity activity, HandTracker handTracker) {
        this.activity = activity;
        this.handTracker = handTracker;
    }

    @Override
    public void start() throws Exception {
        synchronized (lock) {
            try {
                session = new Session(activity);
                Config config = new Config(session);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                config.setFocusMode(Config.FocusMode.AUTO);
                session.configure(config);
                session.resume();
            } catch (Exception e) {
                if (session != null) {
                    session.close();
                    session = null;
                }
                throw e;
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (session != null) {
                session.pause();
                session.close();
                session = null;
            }
            cameraTexture = 0;
        }
        convertExecutor.shutdown();
    }

    @Override
    public void onGlReady() {
        synchronized (lock) {
            if (session == null) return;
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            cameraTexture = tex[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            session.setCameraTextureName(cameraTexture);
        }
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        synchronized (lock) {
            viewWidth = width;
            viewHeight = height;
            applyDisplayGeometry();
        }
    }

    @SuppressWarnings("deprecation")
    private void applyDisplayGeometry() {
        if (session != null && viewWidth > 0) {
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            session.setDisplayGeometry(rotation, viewWidth, viewHeight);
        }
    }

    @Override
    public boolean getPose(float[] quat, float[] pos) {
        synchronized (lock) {
            if (session == null || cameraTexture == 0) return false;
            try {
                Frame frame = session.update();
                feedHandTracker(frame);

                Camera camera = frame.getCamera();
                if (camera.getTrackingState() != TrackingState.TRACKING) return false;

                Pose pose = camera.getDisplayOrientedPose();
                System.arraycopy(pose.getRotationQuaternion(), 0, quat, 0, 4);
                System.arraycopy(pose.getTranslation(), 0, pos, 0, 3);
                return true;
            } catch (CameraNotAvailableException | RuntimeException e) {
                return false;
            }
        }
    }

    private void feedHandTracker(Frame frame) {
        if (handTracker == null || handTracker.isBusy()) return;
        long now = System.nanoTime();
        if (now - lastHandFrameNs < 66_000_000L) return;
        if (!converting.compareAndSet(false, true)) return;
        lastHandFrameNs = now;

        try (Image image = frame.acquireCameraImage()) {
            final int w = image.getWidth();
            final int h = image.getHeight();
            final byte[] nv21 = toNv21(image);
            convertExecutor.execute(() -> {
                try {
                    YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    yuv.compressToJpeg(new Rect(0, 0, w, h), 70, out);
                    byte[] jpeg = out.toByteArray();
                    Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (bmp != null) handTracker.submit(bmp);
                } finally {
                    converting.set(false);
                }
            });
        } catch (NotYetAvailableException | RuntimeException e) {
            converting.set(false);
        }
    }

    private static byte[] toNv21(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuf = planes[0].getBuffer();
        int yRow = planes[0].getRowStride();
        for (int row = 0; row < h; row++) {
            yBuf.position(row * yRow);
            yBuf.get(nv21, row * w, w);
        }

        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int uvRow = planes[1].getRowStride();
        int uvPixel = planes[1].getPixelStride();
        int idx = w * h;
        for (int row = 0; row < h / 2; row++) {
            for (int col = 0; col < w / 2; col++) {
                int o = row * uvRow + col * uvPixel;
                nv21[idx++] = vBuf.get(o);
                nv21[idx++] = uBuf.get(o);
            }
        }
        return nv21;
    }
}
