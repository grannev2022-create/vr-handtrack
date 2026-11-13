package com.grannev.vrhandtrack;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.view.Surface;

public class ThreeDofPose implements PoseProvider, SensorEventListener {

    private static final float[] SENSOR_TO_GL = {
            1, 0, 0, 0,
            0, 0, -1, 0,
            0, 1, 0, 0,
            0, 0, 0, 1};

    private final Activity activity;
    private final SensorManager sensorManager;
    private final Sensor sensor;

    private final float[] rot = new float[16];
    private final float[] remapped = new float[16];
    private final float[] world = new float[16];
    private final float[] quat = {0, 0, 0, 1};
    private boolean hasData;

    public ThreeDofPose(Activity activity) {
        this.activity = activity;
        sensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
        Sensor s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) s = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensor = s;
    }

    @Override
    public void start() {
        if (sensor == null) {
            throw new IllegalStateException("В телефоне нет датчика вращения (гироскопа)");
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    public void stop() {
        sensorManager.unregisterListener(this);
        synchronized (quat) {
            hasData = false;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(rot, event.values);

        switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                SensorManager.remapCoordinateSystem(rot,
                        SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped);
                break;
            case Surface.ROTATION_270:
                SensorManager.remapCoordinateSystem(rot,
                        SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped);
                break;
            case Surface.ROTATION_180:
                SensorManager.remapCoordinateSystem(rot,
                        SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped);
                break;
            default:
                System.arraycopy(rot, 0, remapped, 0, 16);
        }

        Matrix.multiplyMM(world, 0, SENSOR_TO_GL, 0, remapped, 0);
        synchronized (quat) {
            Quat.fromMatrix(world, quat);
            hasData = true;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public boolean getPose(float[] outQuat, float[] outPos) {
        synchronized (quat) {
            if (!hasData) return false;
            System.arraycopy(quat, 0, outQuat, 0, 4);
        }
        outPos[0] = 0f;
        outPos[1] = 0f;
        outPos[2] = 0f;
        return true;
    }
}
