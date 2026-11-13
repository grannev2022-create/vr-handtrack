package com.grannev.vrhandtrack;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

public class HandSkeletonActivity extends AppCompatActivity implements SensorEventListener {
    private static final int REQ_CAMERA = 1;
    private static final String MODEL = "hand_landmarker.task";
    private PreviewView previewView;
    private HandLandmarker handLandmarker;
    private OverlayView overlayView;
    
    private SensorManager sensorManager;
    private Sensor gyro;
    private float[] gyroData = new float[3];
    
    private final float[][] smoothed = new float[21][3];
    private boolean hasSmooth = false;
    private static final float EMA_ALPHA = 0.6f;
    private static final float PINCH_IN = 0.25f;
    private static final float PINCH_OUT = 0.35f;
    private boolean pinchActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        previewView = new PreviewView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        overlayView = new OverlayView();
        root.addView(overlayView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

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
                            .setErrorListener(e -> {})
                            .build();

            handLandmarker = HandLandmarker.createFromOptions(this, options);
        } catch (Exception e) {}
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
            } catch (Exception e) {}
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
                overlayView.updateData(null, false, gyroData);
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

            if (!pinchActive && norm < PINCH_IN) pinchActive = true;
            else if (pinchActive && norm > PINCH_OUT) pinchActive = false;

            overlayView.updateData(smoothed, pinchActive, gyroData);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroData = event.values.clone();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    class OverlayView extends SurfaceView implements SurfaceHolder.Callback {
        private Paint linePaint = new Paint();
        private Paint pointPaint = new Paint();
        private Paint pinchPaint = new Paint();
        private float[][] points;
        private boolean isPinching;
        private float[] gyro;

        public OverlayView() {
            super(HandSkeletonActivity.this);
            getHolder().addCallback(this);
            setZOrderOnTop(true);
            getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            
            linePaint.setColor(Color.GREEN);
            linePaint.setStrokeWidth(5f);
            linePaint.setStyle(Paint.Style.STROKE);
            
            pointPaint.setColor(Color.CYAN);
            pointPaint.setStyle(Paint.Style.FILL);
            
            pinchPaint.setColor(Color.RED);
            pinchPaint.setStyle(Paint.Style.FILL);
        }

        public void updateData(float[][] pts, boolean pinch, float[] gyroVals) {
            this.points = pts;
            this.isPinching = pinch;
            this.gyro = gyroVals;
            invalidate();
        }

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {}
        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}
        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {}

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

            if (points != null) {
                int w = getWidth();
                int h = getHeight();

                int[][] connections = {
                    {0,1},{1,2},{2,3},{3,4},
                    {0,5},{5,6},{6,7},{7,8},
                    {5,9},{9,10},{10,11},{11,12},
                    {9,13},{13,14},{14,15},{15,16},
                    {13,17},{17,18},{18,19},{19,20},
                    {0,17}
                };

                for (int[] conn : connections) {
                    canvas.drawLine(points[conn[0]][0] * w, points[conn[0]][1] * h,
                            points[conn[1]][0] * w, points[conn[1]][1] * h, linePaint);
                }

                for (int i = 0; i < 21; i++) {
                    float radius = (i == 4 || i == 8) ? 12f : 6f;
                    if (isPinching && (i == 4 || i == 8)) {
                        canvas.drawCircle(points[i][0] * w, points[i][1] * h, radius * 2, pinchPaint);
                    } else {
                        canvas.drawCircle(points[i][0] * w, points[i][1] * h, radius, pointPaint);
                    }
                }
            }

            if (gyro != null) {
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(30f);
                textPaint.setAntiAlias(true);
                canvas.drawText(String.format("Gyro: %.2f, %.2f, %.2f", gyro[0], gyro[1], gyro[2]), 40, 60, textPaint);
            }
        }
    }
}
