package com.grannev.vrhandtrack;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class HandTracker {
    private static final String TAG = "HandTracker";
    private static final String MODEL = "hand_landmarker.task";
    public static final int MASK_W = 320;
    public static final int MASK_H = 240;
    private static final long HOLD_MS = 150;
    private static final float PALM_METERS = 0.095f;
    private static final int[] TIPS = {8, 12, 16, 20};
    private static final int[] MCPS = {5, 9, 13, 17};

    public interface Listener {
        void onOkGesture();
    }

    public static final class Hand {
        public final float[] lm = new float[42];
        public boolean fist;
        public boolean ok;
        public float palmU, palmV;
        public float depth;
    }

    public static final class Frame {
        public final Hand[] hands;
        public final long timeMs;
        Frame(Hand[] hands, long timeMs) { this.hands = hands; this.timeMs = timeMs; }
    }

    private final Listener listener;
    private final HandLandmarker landmarker;
    private final AtomicReference<Frame> latest = new AtomicReference<>(new Frame(new Hand[2], 0));

    private volatile boolean inFlight;
    private volatile long lastSubmitMs;
    private long lastTimestamp;
    private volatile float alpha = 0.76f;
    private volatile float tanHalfX = 0.65f;
    private volatile float tanHalfY = 0.4875f;

    private final float[][] sm = new float[2][42];
    private final boolean[] smInit = new boolean[2];
    private final boolean[] fistState = new boolean[2];
    private final boolean[] okState = new boolean[2];
    private final float[] depthSm = new float[2];
    private long lastHandsMs;
    private boolean okEdge;

    private final Bitmap mask = Bitmap.createBitmap(MASK_W, MASK_H, Bitmap.Config.ARGB_8888);
    private final Canvas maskCanvas = new Canvas(mask);
    private final Object maskLock = new Object();
    private long maskVersion;

    public HandTracker(Context ctx, Listener listener) {
        this.listener = listener;
        HandLandmarker lm;
        try { lm = create(ctx, true); } catch (Exception e) { lm = create(ctx, false); }
        landmarker = lm;
    }

    private HandLandmarker create(Context ctx, boolean gpu) {
        BaseOptions.Builder base = BaseOptions.builder().setModelAssetPath(MODEL);
        if (gpu) base.setDelegate(Delegate.GPU);
        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base.build()).setNumHands(2)
                .setMinHandDetectionConfidence(0.5f).setMinHandPresenceConfidence(0.5f).setMinTrackingConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onResult)
                .setErrorListener(e -> { inFlight = false; Log.e(TAG, "MediaPipe: " + e.getMessage()); })
                .build();
        return HandLandmarker.createFromOptions(ctx, options);
    }

    public void setSmoothingPercent(int percent) { alpha = 1f - 0.8f * Math.max(0, Math.min(100, percent)) / 100f; }
    public void setFov(float tanX, float tanY) { tanHalfX = tanX; tanHalfY = tanY; }
    public Frame getFrame() { return latest.get(); }
    public Bitmap getMask() { return mask; }
    public Object getMaskLock() { return maskLock; }
    public long getMaskVersion() { return maskVersion; }
    public boolean canAccept() { return !inFlight || SystemClock.uptimeMillis() - lastSubmitMs > 400; }

    public void submit(Bitmap upright) {
        long now = SystemClock.uptimeMillis();
        if (now <= lastTimestamp) now = lastTimestamp + 1;
        lastTimestamp = now;
        inFlight = true;
        lastSubmitMs = now;
        try { landmarker.detectAsync(new BitmapImageBuilder(upright).build(), now); }
        catch (Exception e) { inFlight = false; Log.e(TAG, "detectAsync", e); }
    }

    public void close() { try { landmarker.close(); } catch (Exception ignored) {} }

    private void onResult(HandLandmarkerResult result, MPImage input) {
        inFlight = false;
        long now = SystemClock.uptimeMillis();
        int n = Math.min(2, result.landmarks().size());
        float aspectHW = (float) input.getHeight() / Math.max(1, input.getWidth());

        if (n == 0) {
            if (now - lastHandsMs > HOLD_MS && hasAnyHand()) { resetSlots(); latest.set(new Frame(new Hand[2], now)); clearMask(); }
            return;
        }
        lastHandsMs = now;
        Hand[] hands = new Hand[2];
        boolean[] used = new boolean[2];
        okEdge = false;

        for (int i = 0; i < n; i++) {
            List<NormalizedLandmark> pts = result.landmarks().get(i);
            int slot = slotFor(result, i);
            if (used[slot]) slot = 1 - slot;
            if (used[slot]) continue;
            used[slot] = true;
            hands[slot] = buildHand(slot, pts, aspectHW);
        }
        for (int s = 0; s < 2; s++) { if (!used[s]) resetSlot(s); }
        drawMask(hands);
        latest.set(new Frame(hands, now));
        if (okEdge && listener != null) listener.onOkGesture();
    }

    private static int slotFor(HandLandmarkerResult result, int i) {
        try {
            List<Category> cats = result.handednesses().get(i);
            if (!cats.isEmpty() && "Left".equals(cats.get(0).categoryName())) return 0;
        } catch (Exception ignored) {}
        return 1;
    }

    private Hand buildHand(int slot, List<NormalizedLandmark> pts, float aspectHW) {
        float a = alpha;
        float[] s = sm[slot];
        for (int i = 0; i < 21; i++) {
            float x = pts.get(i).x();
            float y = pts.get(i).y();
            if (!smInit[slot]) { s[i * 2] = x; s[i * 2 + 1] = y; }
            else { s[i * 2] += (x - s[i * 2]) * a; s[i * 2 + 1] += (y - s[i * 2 + 1]) * a; }
        }
        smInit[slot] = true;

        Hand h = new Hand();
        System.arraycopy(s, 0, h.lm, 0, 42);
        float palm = Math.max(dist(s, 0, 9, aspectHW), 0.02f);
        float curl = 0f, ext = 0f;
        for (int k = 0; k < 4; k++) {
            float r = dist(s, TIPS[k], 0, aspectHW) / Math.max(dist(s, MCPS[k], 0, aspectHW), 1e-3f);
            curl += r; if (k > 0) ext += r;
        }
        curl /= 4f; ext /= 3f;

        boolean fist = fistState[slot] ? curl < 1.55f : curl < 1.30f;
        fistState[slot] = fist;
        h.fist = fist;

        float pinch = dist(s, 4, 8, aspectHW) / palm;
        boolean wasOk = okState[slot];
        boolean ok = wasOk ? (pinch < 0.45f && ext > 1.5f) : (pinch < 0.28f && ext > 1.7f);
        okState[slot] = ok;
        h.ok = ok;
        if (ok && !wasOk) okEdge = true;

        h.palmU = (s[0] + s[10] + s[18] + s[26] + s[34]) / 5f;
        h.palmV = (s[1] + s[11] + s[19] + s[27] + s[35]) / 5f;

        float fN = 1f / (2f * tanHalfX);
        float raw = PALM_METERS * fN / palm;
        raw = Math.max(0.15f, Math.min(1.2f, raw));
        depthSm[slot] = depthSm[slot] == 0f ? raw : depthSm[slot] + (raw - depthSm[slot]) * 0.25f;
        h.depth = depthSm[slot];
        return h;
    }

    private static float dist(float[] s, int a, int b, float aspectHW) {
        float dx = s[a * 2] - s[b * 2];
        float dy = (s[a * 2 + 1] - s[b * 2 + 1]) * aspectHW;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private boolean hasAnyHand() { Frame f = latest.get(); return f.hands[0] != null || f.hands[1] != null; }
    private void resetSlot(int s) { smInit[s] = false; fistState[s] = false; okState[s] = false; depthSm[s] = 0f; }
    private void resetSlots() { resetSlot(0); resetSlot(1); }

    private void drawMask(Hand[] hands) {
        synchronized (maskLock) {
            mask.eraseColor(Color.TRANSPARENT);
            for (Hand h : hands) { if (h != null) HandSilhouette.draw(maskCanvas, h.lm, MASK_W, MASK_H); }
            maskVersion++;
        }
    }

    private void clearMask() {
        synchronized (maskLock) { mask.eraseColor(Color.TRANSPARENT); maskVersion++; }
    }
}
