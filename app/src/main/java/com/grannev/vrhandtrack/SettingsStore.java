package com.grannev.vrhandtrack;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsStore {
    public static final int MODE_3DOF = 3;
    public static final int MODE_6DOF = 6;
    public static final int[] FPS_OPTIONS = {30, 45, 60, 72, 90, 120};

    private final SharedPreferences prefs;

    public SettingsStore(Context ctx) {
        prefs = ctx.getSharedPreferences("vr_settings", Context.MODE_PRIVATE);
    }

    public int getFps() { return prefs.getInt("fps", 60); }
    public void setFps(int fps) { prefs.edit().putInt("fps", fps).apply(); }

    public int getMode() { return prefs.getInt("mode", MODE_3DOF); }
    public void setMode(int mode) { prefs.edit().putInt("mode", mode).apply(); }

    public int getSmoothing() { return prefs.getInt("smoothing", 30); }
    public void setSmoothing(int percent) {
        prefs.edit().putInt("smoothing", Math.max(0, Math.min(100, percent))).apply();
    }
}
