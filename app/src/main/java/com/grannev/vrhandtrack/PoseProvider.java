package com.grannev.vrhandtrack;

public interface PoseProvider {

    void start() throws Exception;

    void stop();

    default void onGlReady() {
    }

    default void onSurfaceChanged(int width, int height) {
    }

    boolean getPose(float[] quat, float[] pos);
}
