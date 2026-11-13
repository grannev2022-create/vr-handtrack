package com.grannev.vrhandtrack;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

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
import java.util.concurrent.atomic.AtomicBoolean;

public class HandTracker {
    private static final String TAG = "HandTracker";

    // Гистерезис: щипок включается при малом расстоянии, выключается при большом
    private static final float PINCH_ON = 0.25f;
    private static final float PINCH_OFF = 0.40f;

    private static final int WRIST = 0;
    private static final int THUMB_TIP = 4;
    private static final int INDEX_TIP = 8;
    private static final int MIDDLE_MCP = 9;

    public interface Listener {
        void onPinchStart();
        void onPinchEnd();
        // Для отрисовки скелета
        void onHandResult(HandLandmarkerResult result, int width, int height);
    }

    private final HandLandmarker landmarker;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private boolean pinching;

    public HandTracker(Context ctx, Listener listener) {
        this.listener = listener;
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build();
        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2) // Разрешаем две руки
                .build();
        landmarker = HandLandmarker.createFromOptions(ctx, options);
    }

    public boolean isBusy() {
        return busy.get();
    }

    public void submit(Bitmap frame) {
        if (!busy.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                process(frame);
            } catch (Exception e) {
                Log.e(TAG, "Ошибка распознавания", e);
            } finally {
                busy.set(false);
            }
        });
    }

    private void process(Bitmap frame) {
        MPImage image = new BitmapImageBuilder(frame).build();
        HandLandmarkerResult result = landmarker.detect(image);

        // Отдаем результат наружу (для отрисовки)
        listener.onHandResult(result, frame.getWidth(), frame.getHeight());

        if (result.landmarks().isEmpty()) {
            setPinching(false);
            return;
        }
        List<NormalizedLandmark> lm = result.landmarks().get(0); // Берем первую руку для щипка
        float w = frame.getWidth();
        float h = frame.getHeight();

        float pinchDist = dist(lm.get(THUMB_TIP), lm.get(INDEX_TIP), w, h);
        float handSize = dist(lm.get(WRIST), lm.get(MIDDLE_MCP), w, h);
        if (handSize < 1e-3f) return;

        float ratio = pinchDist / handSize;
        if (!pinching && ratio < PINCH_ON) {
            setPinching(true);
        } else if (pinching && ratio > PINCH_OFF) {
            setPinching(false);
        }
    }

    private void setPinching(boolean value) {
        if (pinching == value) return;
        pinching = value;
        if (value) listener.onPinchStart();
        else listener.onPinchEnd();
    }

    private static float dist(NormalizedLandmark a, NormalizedLandmark b, float w, float h) {
        float dx = (a.x() - b.x()) * w;
        float dy = (a.y() - b.y()) * h;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public void close() {
        executor.shutdown();
        landmarker.close();
    }
}
