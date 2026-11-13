package com.grannev.vrhandtrack;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VRActivity extends AppCompatActivity implements SensorEventListener {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private PreviewView previewView;
    private OverlayView overlayView;
    private HandLandmarker handLandmarker;
    private ExecutorService cameraExecutor;

    private SensorManager sensorManager;
    private Sensor rotationSensor;

    private CameraManager cameraManager;
    private String cameraId;
    private boolean isFlashOn = false;

    // Сглаживание
    private final float[][] smoothed = new float[21][3];
    private boolean hasSmooth = false;
    private static final float EMA_ALPHA = 0.5f;

    // Щипок
    private boolean pinchActive = false;
    private static final float PINCH_IN = 0.25f;
    private static final float PINCH_OUT = 0.35f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout rootLayout = new FrameLayout(this);
        previewView = new PreviewView(this);
        overlayView = new OverlayView(this);

        rootLayout.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        rootLayout.addView(overlayView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootLayout);

        cameraExecutor = Executors.newSingleThreadExecutor();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraManager != null) cameraId = cameraManager.getCameraIdList()[0];
        } catch (CameraAccessException e) { Log.e("VR", "CamMgr error", e); }

        setupHandLandmarker();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    private void setupHandLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
            handLandmarker = HandLandmarker.createFromOptions(this,
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions).setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f).setMinTrackingConfidence(0.5f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener((result, inputImage) -> runOnUiThread(() -> processResults(result)))
                    .build());
        } catch (Exception e) { Log.e("VR", "Init error", e); }
    }

    private void processResults(HandLandmarkerResult result) {
        if (result.landmarks().isEmpty()) {
            hasSmooth = false; pinchActive = false; overlayView.setResults(null, false);
            return;
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

        if (!pinchActive && norm < PINCH_IN) { pinchActive = true; toggleFlashlight(true); }
        else if (pinchActive && norm > PINCH_OUT) { pinchActive = false; toggleFlashlight(false); }

        overlayView.setResults(smoothed, pinchActive);
    }

    private void toggleFlashlight(boolean state) {
        if (cameraManager == null || cameraId == null) return;
        try {
            cameraManager.setTorchMode(cameraId, state);
            isFlashOn = state;
        } catch (CameraAccessException e) { Log.e("VR", "Flash error", e); }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                future.get().bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) { Log.e("VR", "Cam bind error", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy image) {
        if (handLandmarker == null) { image.close(); return; }
        try {
            Bitmap raw = image.toBitmap();
            Matrix matrix = new Matrix();
            matrix.postRotate(image.getImageInfo().getRotationDegrees());
            Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
            handLandmarker.detectAsync(new BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis());
        } catch (Exception e) { Log.e("VR", "Frame error", e); }
        finally { image.close(); }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        toggleFlashlight(false);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rawMatrix = new float[16];
            SensorManager.getRotationMatrixFromVector(rawMatrix, event.values);
            if (overlayView != null) overlayView.setHeadMatrix(rawMatrix);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (handLandmarker != null) handLandmarker.close();
    }

    private static class OverlayView extends SurfaceView implements SurfaceHolder.Callback {
        private float[][] points;
        private boolean pinch;
        private float[] headMatrix = new float[16];
        private final Paint pointPaint, linePaint, dividerPaint;

        private static final int[][] HAND_CONNECTIONS = {
                {0, 1}, {1, 2}, {2, 3}, {3, 4},
                {0, 5}, {5, 6}, {6, 7}, {7, 8},
                {5, 9}, {9, 10}, {10, 11}, {11, 12},
                {9, 13}, {13, 14}, {14, 15}, {15, 16},
                {13, 17}, {0, 17}, {17, 18}, {18, 19}, {19, 20}
        };

        public OverlayView(Context context) {
            super(context);
            getHolder().addCallback(this);
            setZOrderMediaOverlay(true);

            pointPaint = new Paint(); pointPaint.setColor(Color.RED); pointPaint.setStyle(Paint.Style.FILL);
            linePaint = new Paint(); linePaint.setColor(Color.GREEN); linePaint.setStrokeWidth(6f); linePaint.setStyle(Paint.Style.STROKE);
            dividerPaint = new Paint(); dividerPaint.setColor(Color.BLACK); dividerPaint.setStrokeWidth(12f);
        }

        public void setResults(float[][] pts, boolean p) {
            this.points = pts; this.pinch = p; invalidate();
        }

        public void setHeadMatrix(float[] m) {
            this.headMatrix = m; invalidate();
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

            int width = getWidth();
            int height = getHeight();

            // Разделитель для VR (полоса посередине)
            canvas.drawLine(width / 2f, 0, width / 2f, height, dividerPaint);

            // Если точки есть — рисуем скелет (ровно по центру)
            if (points != null) {
                // Отрисовка линий
                for (int[] conn : HAND_CONNECTIONS) {
                    float x1 = points[conn[0]][0] * width;
                    float y1 = points[conn[0]][1] * height;
                    float x2 = points[conn[1]][0] * width;
                    float y2 = points[conn[1]][1] * height;
                    canvas.drawLine(x1, y1, x2, y2, linePaint);
                }
                // Отрисовка точек
                for (int i = 0; i < 21; i++) {
                    float radius = (i == 4 || i == 8) ? 15f : 8f;
                    if (pinch && (i == 4 || i == 8)) {
                        canvas.drawCircle(points[i][0] * width, points[i][1] * height, radius * 2, pointPaint);
                    } else {
                        canvas.drawCircle(points[i][0] * width, points[i][1] * height, radius, pointPaint);
                    }
                }
            }
        }
    }
}
