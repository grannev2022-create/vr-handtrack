package com.grannev.vrhandtrack;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import androidx.camera.core.CameraControl;

public class FlashlightController {
    private static final String TAG = "Flashlight";

    private final CameraManager manager;
    private String torchCameraId;
    private CameraControl cameraControl;
    private boolean on;

    public FlashlightController(Context ctx) {
        manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Boolean hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(hasFlash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchCameraId = id;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Не удалось получить список камер", e);
        }
    }

    public synchronized void attachCameraControl(CameraControl control) {
        cameraControl = control;
        if (on) control.enableTorch(true);
    }

    public synchronized void detachCameraControl() {
        cameraControl = null;
    }

    public synchronized boolean isOn() {
        return on;
    }

    public synchronized boolean toggle() {
        setTorch(!on);
        return on;
    }

    public synchronized void setTorch(boolean enable) {
        on = enable;
        if (cameraControl != null) {
            cameraControl.enableTorch(enable);
            return;
        }
        if (torchCameraId == null) return;
        try {
            manager.setTorchMode(torchCameraId, enable);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Не удалось переключить фонарик", e);
        }
    }
}
