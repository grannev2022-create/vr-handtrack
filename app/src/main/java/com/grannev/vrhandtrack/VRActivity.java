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
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VRActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "HandTrackingApp";
    private static final int CAMERA_PERMISSION_CODE = 100;

    private PreviewView previewView;
    private OverlayView overlayView;
    private HandLandmarker handLandmarker;
    private ExecutorService cameraExecutor;

    private SensorManager sensorManager;
    private Sensor gyro;
    private float[] gyroData = new float[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout rootLayout = new FrameLayout(this);
        previewView = new PreviewView(this);
        overlayView = new OverlayView(this);

        rootLayout.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rootLayout.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);

        cameraExecutor = Executors.newSingleThreadExecutor();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

        setupHandLandmarker();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    private void setupHandLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build();

            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setNumHands(2)
                            .setMinHandDetectionConfidence(0.5f)
                            .setMinTrackingConfidence(0.5f)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setResultListener((result, inputImage) -> {
                                runOnUiThread(() -> overlayView.setResults(result));
                            })
                            .build();

            handLandmarker = HandLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка запуска MediaPipe: " + e.getMessage());
            Toast.makeText(this, "Не найден файл hand_landmarker.task в assets", Toast.LENGTH_LONG).show();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Ошибка камеры: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy image) {
        if (handLandmarker == null) {
            image.close();
            return;
        }

        try {
            Bitmap rawBitmap = image.toBitmap();
            Matrix matrix = new Matrix();
            matrix.postRotate(image.getImageInfo().getRotationDegrees());

            Bitmap rotatedBitmap = Bitmap.createBitmap(
                    rawBitmap, 0, 0, rawBitmap.getWidth(), rawBitmap.getHeight(), matrix, true);

            MPImage mpImage = new BitmapImageBuilder(rotatedBitmap).build();
            handLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis());
        } catch (Exception e) {
            Log.e(TAG, "Ошибка обработки кадра: " + e.getMessage());
        } finally {
            image.close();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_SHORT).show();
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
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroData = event.values.clone();
            if (overlayView != null) {
                overlayView.setGyro(gyroData);
            }
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

    private static class OverlayView extends View {
        private HandLandmarkerResult results;
        private float[] gyroVals = new float[3];
        private final Paint pointPaint;
        private final Paint linePaint;
        private final Paint dividerPaint;
        private final Paint textPaint;

        private static final int[][] HAND_CONNECTIONS = {
                {0, 1}, {1, 2}, {2, 3}, {3, 4},
                {0, 5}, {5, 6}, {6, 7}, {7, 8},
                {5, 9}, {9, 10}, {10, 11}, {11, 12},
                {9, 13}, {13, 14}, {14, 15}, {15, 16},
                {13, 17}, {0, 17}, {17, 18}, {18, 19}, {19, 20}
        };

        public OverlayView(Context context) {
            super(context);
            pointPaint = new Paint();
            pointPaint.setColor(Color.RED);
            pointPaint.setStyle(Paint.Style.FILL);

            linePaint = new Paint();
            linePaint.setColor(Color.GREEN);
            linePaint.setStrokeWidth(6f);
            linePaint.setStyle(Paint.Style.STROKE);

            dividerPaint = new Paint();
            dividerPaint.setColor(Color.BLACK);
            dividerPaint.setStrokeWidth(12f);

            textPaint = new Paint();
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(36f);
            textPaint.setAntiAlias(true);
        }

        public void setResults(HandLandmarkerResult handLandmarkerResult) {
            this.results = handLandmarkerResult;
            invalidate();
        }

        public void setGyro(float[] g) {
            this.gyroVals = g;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            
            int width = getWidth();
            int height = getHeight();
            int halfW = width / 2;

            // Рисуем разделительную линию по центру
            canvas.drawLine(halfW, 0, halfW, height, dividerPaint);

            // Левый глаз
            drawEye(canvas, 0, 0, halfW, height);
            // Правый глаз
            drawEye(canvas, halfW, 0, halfW, height);

            // Текст гироскопа по центру
            if (gyroVals != null) {
                canvas.drawText(String.format("G: %.1f, %.1f, %.1f", gyroVals[0], gyroVals[1], gyroVals[2]), 20, 40, textPaint);
            }
        }

        private void drawEye(Canvas canvas, float ox, float oy, int vw, int vh) {
            if (results == null || results.landmarks().isEmpty()) return;

            for (List<NormalizedLandmark> handLandmarks : results.landmarks()) {
                for (int[] connection : HAND_CONNECTIONS) {
                    NormalizedLandmark start = handLandmarks.get(connection[0]);
                    NormalizedLandmark end = handLandmarks.get(connection[1]);
                    canvas.drawLine(
                            ox + start.x() * vw, oy + start.y() * vh,
                            ox + end.x() * vw, oy + end.y() * vh,
                            linePaint
                    );
                }

                for (NormalizedLandmark landmark : handLandmarks) {
                    canvas.drawCircle(ox + landmark.x() * vw, oy + landmark.y() * vh, 10f, pointPaint);
                }
            }
        }
    }
}
