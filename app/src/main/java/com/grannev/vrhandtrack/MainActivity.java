package com.grannev.vrhandtrack;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.FrameLayout;
import android.widget.TextView;
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
import com.google.mediapipe.tasks.components.containers.Landmark;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAMERA = 1;
    private static final String MODEL = "hand_landmarker.task";
    private PreviewView previewView;
    private HandLandmarker handLandmarker;
    private FrameLayout overlay;
    private TextView statusText;

    private final float[][] smoothed = new float[21][3];
    private boolean hasSmooth = false;
    private static final float EMA_ALPHA = 0.6f;
    private static final float PINCH_IN = 0.25f;
    private static final float PINCH_OUT = 0.35f;
    private boolean pinchActive = false;
    private long lastPinchLog = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        previewView = new PreviewView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTextColor(Color.WHITE);
        statusText.setPadding(32, 32, 32, 32);
        root.addView(statusText, new FrameLayout.LayoutParams(-2, -2));

        setContentView(root);

        initMediaPipe();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void initMediaPipe() {
        try {
            BaseOptions base = BaseOptions.builder()
                    .setModelAssetPath(MODEL)
                    .setDelegate(Delegate.CPU)
                    .build();

            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(base)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumHands(1)
                            .setMinHandDetectionConfidence(0.5f)
                            .setMinHandPresenceConfidence(0.5f)
                            .setMinTrackingConfidence(0.5f)
                            .setResultListener(this::onResult)
                            .setErrorListener(e -> runOnUiThread(() ->
                                    statusText.setText("MP error: " + e.getMessage())))
                            .build();

            handLandmarker = HandLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            statusText.setText("Init error: " + e.getMessage());
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                analysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                statusText.setText("Camera error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy image) {
        if (handLandmarker == null) { image.close(); return; }
        Bitmap bmp = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());
        MPImage mp = new BitmapImageBuilder(bmp).build();
        long ts = SystemClock.uptimeMillis();
        handLandmarker.detectAsync(mp, ts);
        image.close();
    }

    private void onResult(HandLandmarkerResult result, MPImage input) {
        runOnUiThread(() -> {
            if (result.landmarks().isEmpty()) {
                hasSmooth = false;
                pinchActive = false;
                overlay.removeAllViews();
                statusText.setText("Руки не видно, блядь");
                return;
            }

            List<NormalizedLandmark> lm = result.landmarks().get(0);

            if (!hasSmooth) {
                for (int i = 0; i < 21; i++) {
                    smoothed[i][0] = lm.get(i).x();
                    smoothed[i][1] = lm.get(i).y();
                    smoothed[i][2] = lm.get(i).z();
                }
                hasSmooth = true;
            } else {
                for (int i = 0; i < 21; i++) {
                    smoothed[i][0] = EMA_ALPHA * lm.get(i).x() + (1 - EMA_ALPHA) * smoothed[i][0];
                    smoothed[i][1] = EMA_ALPHA * lm.get(i).y() + (1 - EMA_ALPHA) * smoothed[i][1];
                    smoothed[i][2] = EMA_ALPHA * lm.get(i).z() + (1 - EMA_ALPHA) * smoothed[i][2];
                }
            }

            float dx = smoothed[4][0] - smoothed[8][0];
            float dy = smoothed[4][1] - smoothed[8][1];
            float dist = (float) Math.sqrt(dx * dx + dy * dy);

            float px = smoothed[0][0] - smoothed[9][0];
            float py = smoothed[0][1] - smoothed[9][1];
            float palm = (float) Math.sqrt(px * px + py * py);
            float norm = palm > 0.001f ? dist / palm : dist;

            if (!pinchActive && norm < PINCH_IN) {
                pinchActive = true;
                lastPinchLog = System.currentTimeMillis();
            } else if (pinchActive && norm > PINCH_OUT) {
                pinchActive = false;
            }

            statusText.setText(String.format("Dist: %.2f\nPinch: %s", norm, pinchActive ? "ДА, БЛЯДЬ" : "нет"));

            overlay.removeAllViews();
            if (pinchActive) {
                TextView indicator = new TextView(this);
                indicator.setText("ЩИПОК");
                indicator.setTextSize(40);
                indicator.setTextColor(Color.RED);
                indicator.setPadding(80, 300, 80, 80);
                overlay.addView(indicator);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }
}
