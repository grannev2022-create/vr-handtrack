package com.grannev.vrhandtrack;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.ar.core.ArCoreApk;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VRActivity extends AppCompatActivity {
    private static final String TAG = "VRActivity";
    private static final long TOGGLE_COOLDOWN_MS = 600;

    private GLSurfaceView glView;
    private VrRenderer renderer;
    private HandOverlayView overlayView;
    private SettingsStore settings;
    private FlashlightController flashlight;
    private HandTracker handTracker;

    private PoseProvider pose;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;

    private long lastToggleMs;
    private boolean arInstallRequested;
    private boolean permissionAsked;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startTracking();
                } else {
                    Toast.makeText(this, "Нужен доступ к камере для трекинга рук", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SettingsStore(this);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new VrRenderer();
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        overlayView = new HandOverlayView(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(getWindow(), glView);
        insets.hide(WindowInsetsCompat.Type.systemBars());
        insets.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        flashlight = new FlashlightController(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            handTracker = new HandTracker(this, new HandTracker.Listener() {
                @Override
                public void onPinchStart() {
                    toggleFlashlight();
                }

                @Override
                public void onPinchEnd() {
                }

                @Override
                public void onHandResult(HandLandmarkerResult result, int width, int height) {
                    overlayView.onHands(result, width, height);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "HandTracker не создан", e);
            Toast.makeText(this, "Нет модели hand_landmarker.task в assets — жесты отключены", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleFlashlight() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastToggleMs < TOGGLE_COOLDOWN_MS) return;
        lastToggleMs = now;
        boolean on = flashlight.toggle();
        runOnUiThread(() -> Toast.makeText(this, on ? "Фонарик включён" : "Фонарик выключен", Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        renderer.setTargetFps(settings.getFps());
        renderer.setSmoothingPercent(settings.getSmoothing());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startTracking();
        } else if (!permissionAsked) {
            permissionAsked = true;
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onPause() {
        stopTracking();
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (handTracker != null) handTracker.close();
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    private void startTracking() {
        if (pose != null) return;
        PoseProvider provider = null;

        if (settings.getMode() == SettingsStore.MODE_6DOF) {
            try {
                ArCoreApk.InstallStatus status = ArCoreApk.getInstance().requestInstall(this, !arInstallRequested);
                if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    arInstallRequested = true;
                    return;
                }
                ArCorePose ar = new ArCorePose(this, handTracker);
                ar.start();
                provider = ar;
            } catch (Exception e) {
                Log.e(TAG, "ARCore недоступен", e);
                Toast.makeText(this, "6DoF недоступен на этом телефоне — включён 3DoF", Toast.LENGTH_LONG).show();
            }
        }

        if (provider == null) {
            try {
                ThreeDofPose three = new ThreeDofPose(this);
                three.start();
                provider = three;
                startCameraX();
            } catch (Exception e) {
                Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        pose = provider;
        renderer.setPoseProvider(provider);
    }

    private void stopTracking() {
        flashlight.setTorch(false);
        flashlight.detachCameraControl();
        if (pose != null) {
            renderer.setPoseProvider(null);
            pose.stop();
            pose = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
    }

    private void startCameraX() {
        if (handTracker == null) return;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                if (pose == null) return;
                cameraProvider = future.get();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, image -> {
                    try {
                        if (!handTracker.isBusy()) handTracker.submit(image.toBitmap());
                    } finally {
                        image.close();
                    }
                });

                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis);
                flashlight.attachCameraControl(camera.getCameraControl());
            } catch (Exception e) {
                Log.e(TAG, "Не удалось запустить камеру", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }
}
