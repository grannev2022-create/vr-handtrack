package com.grannev.vrhandtrack;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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

public class VRActivity extends AppCompatActivity implements SensorEventListener {
    private static final int REQ_CAMERA = 1;
    private static final String MODEL = "hand_landmarker.task";
    private HandLandmarker handLandmarker;
    private SkeletonRenderer renderer = new SkeletonRenderer();
    private OverlayView overlayView;
    private PreviewView previewView;
    
    private SensorManager sensorManager;
    private Sensor gyro;
    private float[] gyroData = new float[3];
    
    private final float[][][] smoothed = new float[2][21][3];
    private final boolean[] hasSmooth = new boolean[2];
    private final boolean[] pinchActive = new boolean[2];
    private float emaAlpha = 0.6f; // будет заменено из настроек
    private static final float PINCH_IN = 0.25f, PINCH_OUT = 0.35f;

    private boolean isWebMode = false;
    private long webModeStartTime = 0;

    private final RectF btn1 = new RectF(0.2f, 0.2f, 0.8f, 0.3f);
    private final RectF btn2 = new RectF(0.2f, 0.5f, 0.8f, 0.6f);
    private final boolean[] btn1Hover = new boolean[2];
    private final boolean[] btn2Hover = new boolean[2];
    private int btn1Clicks = 0, btn2Clicks = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Читаем настройки
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        int smoothVal = prefs.getInt(SettingsActivity.KEY_SMOOTH, 6);
        // Преобразуем 0-10 в 0.9 - 0.1 (чем больше сглаживание, тем меньше alpha)
        emaAlpha = 0.9f - (smoothVal * 0.08f);
        if (emaAlpha < 0.1f) emaAlpha = 0.1f;
        if (emaAlpha > 0.9f) emaAlpha = 0.9f;

        FrameLayout root = new FrameLayout(this);
        previewView = new PreviewView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        overlayView = new OverlayView();
        root.addView(overlayView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

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
                    .setNumHands(2)
                    .setResultListener(this::onResult).build());
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

    private boolean isSpiderWebGesture(List<NormalizedLandmark> lm) {
        boolean indexStraight = lm.get(8).y() < lm.get(5).y() - 0.1f;
        boolean middleStraight = lm.get(12).y() < lm.get(9).y() - 0.1f;
        return indexStraight && middleStraight && lm.get(8).y() < 0.5f;
    }

    private void onResult(HandLandmarkerResult result, MPImage input) {
        runOnUiThread(() -> {
            List<List<NormalizedLandmark>> allHands = result.landmarks();
            boolean webDetected = false;

            if (!allHands.isEmpty()) {
                for (int h = 0; h < allHands.size() && h < 2; h++) {
                    List<NormalizedLandmark> lm = allHands.get(h);
                    if (isSpiderWebGesture(lm)) webDetected = true;

                    if (!hasSmooth[h]) {
                        for (int i = 0; i < 21; i++) { smoothed[h][i][0] = lm.get(i).x(); smoothed[h][i][1] = lm.get(i).y(); }
                        hasSmooth[h] = true;
                    } else {
                        for (int i = 0; i < 21; i++) {
                            smoothed[h][i][0] = emaAlpha * lm.get(i).x() + (1 - emaAlpha) * smoothed[h][i][0];
                            smoothed[h][i][1] = emaAlpha * lm.get(i).y() + (1 - emaAlpha) * smoothed[h][i][1];
                        }
                    }
                    float dx = smoothed[h][4][0] - smoothed[h][8][0], dy = smoothed[h][4][1] - smoothed[h][8][1];
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    float px = smoothed[h][0][0] - smoothed[h][9][0], py = smoothed[h][0][1] - smoothed[h][9][1];
                    float palm = (float) Math.sqrt(px * px + py * py);
                    float norm = palm > 0.001f ? dist / palm : dist;
                    if (!pinchActive[h] && norm < PINCH_IN) pinchActive[h] = true;
                    else if (pinchActive[h] && norm > PINCH_OUT) pinchActive[h] = false;

                    float fx = smoothed[h][8][0], fy = smoothed[h][8][1];
                    boolean over1 = btn1.contains(fx, fy);
                    boolean over2 = btn2.contains(fx, fy);
                    if (pinchActive[h]) {
                        if (over1 && !btn1Hover[h]) { btn1Clicks++; btn1Hover[h] = true; }
                        if (over2 && !btn2Hover[h]) { btn2Clicks++; btn2Hover[h] = true; }
                    } else {
                        if (!over1) btn1Hover[h] = false;
                        if (!over2) btn2Hover[h] = false;
                    }
                }
            } else {
                hasSmooth[0] = false; hasSmooth[1] = false;
                pinchActive[0] = false; pinchActive[1] = false;
            }

            if (webDetected && !isWebMode) {
                isWebMode = true; webModeStartTime = System.currentTimeMillis();
                previewView.setVisibility(android.view.View.INVISIBLE);
            } else if (!webDetected && isWebMode && (System.currentTimeMillis() - webModeStartTime > 1000)) {
                isWebMode = false; previewView.setVisibility(android.view.View.VISIBLE);
            }

            overlayView.updateData(smoothed, hasSmooth, pinchActive, gyroData, btn1Hover, btn2Hover, btn1Clicks, btn2Clicks, isWebMode);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) gyroData = event.values.clone();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    class OverlayView extends SurfaceView implements SurfaceHolder.Callback {
        private float[][][] points; 
        private boolean[] smoothFlags, pinches;
        private float[] gyroVals;
        private boolean[] h1s, h2s;
        private int c1, c2;
        private boolean webMode;
        
        public OverlayView() {
            super(VRActivity.this); getHolder().addCallback(this);
            setZOrderOnTop(true); getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        }
        
        public void updateData(float[][][] pts, boolean[] sf, boolean[] p, float[] g, boolean[] oh1, boolean[] oh2, int c1, int c2, boolean wm) { 
            this.points = pts; this.smoothFlags = sf; this.pinches = p; this.gyroVals = g; 
            this.h1s = oh1; this.h2s = oh2; this.c1 = c1; this.c2 = c2; this.webMode = wm;
            invalidate(); 
        }
        
        @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {}
        @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int f, int w, int h) {}
        @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {}
        
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            renderer.clear(canvas);
            if (webMode) drawVirtualCorridors(canvas);

            int w = getWidth(); int h = getHeight(); int halfW = w / 2;
            Paint divider = new Paint();
            divider.setColor(Color.BLACK); divider.setStrokeWidth(10f);
            canvas.drawLine(halfW, 0, halfW, h, divider);
            drawEye(canvas, 0, 0, halfW, h);
            drawEye(canvas, halfW, 0, halfW, h);
        }

        private void drawVirtualCorridors(Canvas canvas) {
            int w = getWidth(); int h = getHeight();
            Paint corridorPaint = new Paint();
            corridorPaint.setColor(Color.CYAN);
            corridorPaint.setStrokeWidth(3f);
            corridorPaint.setStyle(Paint.Style.STROKE);
            int centerX = w / 2; int centerY = h / 2;
            for (int i = 0; i < 10; i++) {
                float angle = (float) (i * Math.PI / 5);
                float x1 = centerX + (float) (Math.cos(angle) * w);
                float y1 = centerY + (float) (Math.sin(angle) * h);
                canvas.drawLine(centerX, centerY, x1, y1, corridorPaint);
            }
            for (int i = 1; i < 10; i++) canvas.drawCircle(centerX, centerY, i * 50f, corridorPaint);
        }

        private void drawEye(Canvas canvas, float ox, float oy, int vw, int vh) {
            if (points != null && smoothFlags != null) {
                for (int h = 0; h < 2; h++) {
                    if (smoothFlags[h]) renderer.drawSkeleton(canvas, points[h], pinches[h], ox, oy, vw, vh);
                }
            }
            Paint btnPaint = new Paint();
            btnPaint.setStyle(Paint.Style.STROKE);
            btnPaint.setStrokeWidth(4f);
            boolean hover1 = (h1s != null && (h1s[0] || h1s[1]));
            boolean hover2 = (h2s != null && (h2s[0] || h2s[1]));
            
            RectF r1 = new RectF(ox + btn1.left * vw, oy + btn1.top * vh, ox + btn1.right * vw, oy + btn1.bottom * vh);
            btnPaint.setColor(hover1 ? Color.GREEN : Color.CYAN);
            canvas.drawRect(r1, btnPaint);
            Paint textPaint = new Paint(); textPaint.setColor(Color.WHITE); textPaint.setTextSize(40f); textPaint.setAntiAlias(true);
            canvas.drawText("Кнопка 1 (" + c1 + ")", r1.left + 20, r1.centerY() + 15, textPaint);

            RectF r2 = new RectF(ox + btn2.left * vw, oy + btn2.top * vh, ox + btn2.right * vw, oy + btn2.bottom * vh);
            btnPaint.setColor(hover2 ? Color.GREEN : Color.CYAN);
            canvas.drawRect(r2, btnPaint);
            canvas.drawText("Кнопка 2 (" + c2 + ")", r2.left + 20, r2.centerY() + 15, textPaint);
        }
    }
}
