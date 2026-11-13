package com.grannev.vrhandtrack;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

public class VRActivity extends AppCompatActivity {
    private static final int REQ_CAMERA = 1;
    private static final String MODEL = "hand_landmarker.task";
    private HandLandmarker handLandmarker;
    private SkeletonRenderer renderer = new SkeletonRenderer();
    private OverlayView overlayView;
    private PreviewView previewView;
    private final float[][] smoothed = new float[21][3];
    private boolean hasSmooth = false;
    private static final float EMA_ALPHA = 0.6f;
    private boolean pinchActive = false;
    private static final float PINCH_IN = 0.25f, PINCH_OUT = 0.35f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        previewView = new PreviewView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        overlayView = new OverlayView();
        root.addView(overlayView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        initMediaPipe();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void initMediaPipe() {
        try {
            BaseOptions base = BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build();
            handLandmarker = HandLandmarker.createFromOptions(this, 
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base).setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1).setResultListener(this::onResult).build());
        } catch (Exception e) {}
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build();
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::analyzeFrame);
                future.get().bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {}
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy image) {
        if (handLandmarker == null) { image.close(); return; }
        Bitmap bmp = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());
        handLandmarker.detectAsync(new BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis());
        image.close();
    }

    private void onResult(HandLandmarkerResult result, MPImage input) {
        runOnUiThread(() -> {
            if (result.landmarks().isEmpty()) {
                hasSmooth = false; pinchActive = false; overlayView.updateData(null, false); return;
            }
            List<NormalizedLandmark> lm = result.landmarks().get(0);
            if (!hasSmooth) {
                for (int i = 0; i < 21; i++) { smoothed[i][0] = lm.get(i).x(); smoothed[i][1] = lm.get(i).y(); }
                hasSmooth = true;
            } else {
                for (int i = 0; i < 21; i++) {
                    smoothed[i][0] = EMA_ALPHA * lm.get(i).x() + (1 - EMA_ALPHA) * smoothed[i][0];
                    smoothed[i][1] = EMA_ALPHA * lm.get(i).y() + (1 - EMA_ALPHA) * smoothed[i][1];
                }
            }
            float dx = smoothed[4][0] - smoothed[8][0], dy = smoothed[4][1] - smoothed[8][1];
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float px = smoothed[0][0] - smoothed[9][0], py = smoothed[0][1] - smoothed[9][1];
            float palm = (float) Math.sqrt(px * px + py * py);
            float norm = palm > 0.001f ? dist / palm : dist;
            if (!pinchActive && norm < PINCH_IN) pinchActive = true;
            else if (pinchActive && norm > PINCH_OUT) pinchActive = false;
            overlayView.updateData(smoothed, pinchActive);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    class OverlayView extends SurfaceView implements SurfaceHolder.Callback {
        private float[][] points; private boolean pinch;
        public OverlayView() {
            super(VRActivity.this); getHolder().addCallback(this);
            setZOrderOnTop(true); getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        }
        public void updateData(float[][] pts, boolean p) { this.points = pts; this.pinch = p; invalidate(); }
        @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {}
        @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int f, int w, int h) {}
        @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            renderer.clear(canvas);
            int w = getWidth(); int h = getHeight(); int halfW = w / 2;
            Paint divider = new Paint();
            divider.setColor(Color.BLACK); divider.setStrokeWidth(10f);
            canvas.drawLine(halfW, 0, halfW, h, divider);
            renderer.drawSkeleton(canvas, points, pinch, 0, 0, halfW, h);
            renderer.drawSkeleton(canvas, points, pinch, halfW, 0, halfW, h);
        }
    }
}
